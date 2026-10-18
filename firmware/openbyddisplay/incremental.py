"""Signed incremental generation and trial-boot laboratory, confined to one private root.

No scripts, services, vehicle partitions, boot records or reboot commands are run.
QNX filesystem/startup integration remains a separate platform contract.
"""

from __future__ import annotations

import argparse
from contextlib import contextmanager
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import uuid

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import (
    Ed25519PrivateKey,
    Ed25519PublicKey,
)

from .packages import (
    DOMAIN,
    HEADER,
    MAGIC,
    MAX_PAYLOAD,
    TARGETS,
    require,
    safe_path,
    unique_object,
    verify,
)

PLAN = "obd-incremental.json"


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode()


def digest(data):
    return hashlib.sha256(data).hexdigest()


def regular(path):
    info = path.lstat()
    require(
        stat.S_ISREG(info.st_mode) and info.st_nlink == 1,
        "regular_private_file_required",
    )
    return info


def version(value):
    require(
        isinstance(value, str)
        and re.fullmatch(
            r"(0|[1-9][0-9]{0,4})\.(0|[1-9][0-9]{0,4})\.(0|[1-9][0-9]{0,4})", value
        ),
        "version_invalid",
    )
    parts = tuple(map(int, value.split(".")))
    require(max(parts) <= 65535, "version_invalid")
    return parts


def file_rows(files):
    return {
        name: {"bytes": len(data), "sha256": digest(data), "mode": mode}
        for name, (data, mode) in files.items()
    }


def source_files(source):
    require(source.is_dir() and not source.is_symlink(), "source_invalid")
    files = {}
    total = 0
    for path in sorted(source.rglob("*")):
        require(not path.is_symlink(), "source_symlink_forbidden")
        if path.is_dir():
            continue
        info = regular(path)
        total += info.st_size
        require(total <= MAX_PAYLOAD and len(files) < 120, "source_limit")
        name = safe_path(path.relative_to(source).as_posix())
        files[name] = (path.read_bytes(), 0o700 if info.st_mode & 0o111 else 0o600)
    require(bool(files), "empty_service_bundle")
    return files


def build_increment(source, key, target, hardware_id, release, base=None):
    require(
        target in TARGETS and re.fullmatch(r"[A-Za-z0-9._-]{1,64}", hardware_id),
        "identity_invalid",
    )
    version(release)
    files = source_files(source)
    rows = file_rows(files)
    if base:
        require(
            base["target"] == target and base["hardware_id"] == hardware_id,
            "base_identity_mismatch",
        )
        require(version(release) > version(base["version"]), "version_not_newer")
    plan = {
        "format": 1,
        "base": {k: base[k] for k in ("package_id", "tree_sha256", "version")}
        if base
        else None,
        "files": rows,
    }
    content = {
        "install.sh": b"#!/bin/sh\n# Data-only incremental lab package. Use the transaction manager.\nexit 1\n",
        "xhh-target.json": canonical(
            {
                "target": target,
                "hardware_id": hardware_id,
                "runtime": "host-lab",
                "production_flashable": False,
            }
        ),
        PLAN: canonical(plan),
    }
    for name, (data, _) in files.items():
        if not base or base["files"].get(name) != rows[name]:
            content["services/" + name] = data
    manifest = {
        "format": 2,
        "version": release,
        "package_id": uuid.uuid4().hex,
        "files": [
            {"path": n, "bytes": len(b), "sha256": digest(b)}
            for n, b in content.items()
        ],
    }
    data, payload = canonical(manifest), b"".join(content.values())
    require(len(data) <= 65536 and len(payload) <= MAX_PAYLOAD, "package_limit")
    raw = (
        HEADER.pack(MAGIC, len(data), 0, len(payload), key.sign(DOMAIN + data))
        + data
        + payload
    )
    verify(raw, key.public_key(), target, hardware_id)
    return raw


def plan_of(files):
    require(PLAN in files and len(files[PLAN]) <= 65536, "incremental_plan_missing")
    plan = json.loads(files[PLAN], object_pairs_hook=unique_object)
    require(
        isinstance(plan, dict)
        and set(plan) == {"format", "base", "files"}
        and type(plan["format"]) is int
        and plan["format"] == 1,
        "incremental_plan_invalid",
    )
    rows = plan["files"]
    require(isinstance(rows, dict) and 1 <= len(rows) <= 120, "file_count_invalid")
    total = 0
    for name, row in rows.items():
        safe_path(name)
        require(
            not any(
                other != name
                and (name.startswith(other + "/") or other.startswith(name + "/"))
                for other in rows
            ),
            "path_conflict",
        )
        require(
            isinstance(row, dict) and set(row) == {"bytes", "sha256", "mode"},
            "file_row_invalid",
        )
        require(
            type(row["bytes"]) is int
            and 0 <= row["bytes"] <= MAX_PAYLOAD
            and type(row["mode"]) is int
            and row["mode"] in (0o600, 0o700),
            "file_size_or_mode_invalid",
        )
        require(
            isinstance(row["sha256"], str)
            and re.fullmatch(r"[0-9a-f]{64}", row["sha256"]),
            "file_digest_invalid",
        )
        total += row["bytes"]
    require(total <= MAX_PAYLOAD, "expanded_bundle_too_large")
    base = plan["base"]
    if base is not None:
        require(
            isinstance(base, dict)
            and set(base) == {"package_id", "tree_sha256", "version"},
            "base_invalid",
        )
        require(
            isinstance(base["package_id"], str)
            and re.fullmatch(r"[0-9a-f]{32}", base["package_id"])
            and isinstance(base["tree_sha256"], str)
            and re.fullmatch(r"[0-9a-f]{64}", base["tree_sha256"]),
            "base_invalid",
        )
        version(base["version"])
    require(
        set(files)
        <= {"install.sh", "xhh-target.json", PLAN}
        | {"services/" + name for name in rows},
        "unexpected_payload",
    )
    return plan


def sync_dir(path):
    fd = os.open(path, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def write_new(path, data, mode=0o600):
    with path.open("xb") as stream:
        path.chmod(mode)
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())


class IncrementalLab:
    def __init__(
        self, root, key, target, hardware_id="host-lab", fault=lambda point: None
    ):
        require(
            target in TARGETS and re.fullmatch(r"[A-Za-z0-9._-]{1,64}", hardware_id),
            "identity_invalid",
        )
        require(not root.is_symlink(), "root_symlink")
        created = not root.exists()
        root.mkdir(parents=True, exist_ok=True, mode=0o700)
        require(
            root.is_dir() and root.stat().st_mode & 0o077 == 0, "private_root_required"
        )
        self.root, self.key, self.target, self.hardware_id, self.fault = (
            root.resolve(),
            key,
            target,
            hardware_id,
            fault,
        )
        if created:
            write_new(
                self.root / ".incremental-lab",
                b"OpenBYDDisplay incremental laboratory v1\n",
            )
        marker = self.root / ".incremental-lab"
        require(
            regular(marker).st_size <= 64
            and marker.read_bytes() == b"OpenBYDDisplay incremental laboratory v1\n",
            "lab_marker_required",
        )
        with self.locked():
            generations = self.root / "generations"
            require(not generations.is_symlink(), "generation_root_symlink")
            generations.mkdir(exist_ok=True, mode=0o700)
            if not (self.root / "state.json").exists():
                require(
                    created and not os.path.lexists(self.root / "state.json"),
                    "existing_state_missing",
                )
                self.save(
                    {
                        "format": 1,
                        "target": target,
                        "hardware_id": hardware_id,
                        "current": None,
                        "confirmed": None,
                        "pending": None,
                        "highest_version": "0.0.0",
                    }
                )
            self.state()

    @contextmanager
    def locked(self):
        fd = os.open(self.root / "lock", os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX)
            yield
        finally:
            os.close(fd)

    def save(self, state):
        temporary = self.root / (".state-" + uuid.uuid4().hex)
        write_new(temporary, canonical(state))
        os.replace(temporary, self.root / "state.json")
        sync_dir(self.root)

    def state(self):
        path = self.root / "state.json"
        regular(path)
        state = json.loads(path.read_bytes(), object_pairs_hook=unique_object)
        require(
            isinstance(state, dict)
            and set(state)
            == {
                "format",
                "target",
                "hardware_id",
                "current",
                "confirmed",
                "pending",
                "highest_version",
            },
            "state_invalid",
        )
        require(
            type(state["format"]) is int
            and state["format"] == 1
            and state["target"] == self.target
            and state["hardware_id"] == self.hardware_id,
            "state_identity_mismatch",
        )
        version(state["highest_version"])
        for field in ("current", "confirmed"):
            require(
                state[field] is None
                or isinstance(state[field], str)
                and re.fullmatch(r"[0-9a-f]{32}", state[field]),
                "state_generation_invalid",
            )
        pending = state["pending"]
        if pending is not None:
            require(
                isinstance(pending, dict)
                and set(pending) == {"package_id", "nonce", "attempted"}
                and pending["package_id"] == state["current"]
                and isinstance(pending["nonce"], str)
                and re.fullmatch(r"[0-9a-f]{32}", pending["nonce"])
                and type(pending["attempted"]) is bool,
                "pending_invalid",
            )
        else:
            require(
                state["current"] == state["confirmed"], "state_pointer_inconsistent"
            )
        return state

    def generation(self, package_id):
        require(
            isinstance(package_id, str) and re.fullmatch(r"[0-9a-f]{32}", package_id),
            "generation_id_invalid",
        )
        directory = self.root / "generations" / package_id
        require(directory.is_dir() and not directory.is_symlink(), "generation_missing")
        package = directory / "package.fup"
        require(regular(package).st_size <= MAX_PAYLOAD + 65624, "package_limit")
        manifest, payload = verify(
            package.read_bytes(), self.key, self.target, self.hardware_id
        )
        require(manifest["package_id"] == package_id, "generation_mismatch")
        plan = plan_of(payload)
        expected = set(plan["files"])
        actual = set()
        require(
            (directory / "files").is_dir() and not (directory / "files").is_symlink(),
            "generation_files_missing",
        )
        for p in (directory / "files").rglob("*"):
            require(not p.is_symlink(), "generation_symlink")
            if p.is_dir():
                continue
            info = regular(p)
            name = p.relative_to(directory / "files").as_posix()
            require(name in expected, "unexpected_generation_file")
            row = plan["files"][name]
            require(
                info.st_size == row["bytes"]
                and stat.S_IMODE(info.st_mode) == row["mode"]
                and digest(p.read_bytes()) == row["sha256"],
                "generation_hash_or_mode_mismatch",
            )
            actual.add(name)
        require(
            actual == expected and not (directory / "files").is_symlink(),
            "generation_files_missing",
        )
        receipt = {
            "package_id": package_id,
            "version": manifest["version"],
            "target": self.target,
            "hardware_id": self.hardware_id,
            "files": plan["files"],
            "tree_sha256": digest(canonical(plan["files"])),
        }
        return receipt, directory / "files"

    def stage(self, raw):
        manifest, payload = verify(raw, self.key, self.target, self.hardware_id)
        plan = plan_of(payload)
        with self.locked():
            state = self.state()
            require(state["pending"] is None, "trial_unresolved")
            require(
                version(manifest["version"]) > version(state["highest_version"]),
                "version_not_newer",
            )
            baseline, base_dir = (
                self.generation(state["confirmed"])
                if state["confirmed"]
                else (None, None)
            )
            expected_base = (
                {k: baseline[k] for k in ("package_id", "tree_sha256", "version")}
                if baseline
                else None
            )
            require(plan["base"] == expected_base, "base_mismatch")
            changed = {
                n
                for n, row in plan["files"].items()
                if not baseline or baseline["files"].get(n) != row
            }
            require(
                {
                    n.removeprefix("services/")
                    for n in payload
                    if n.startswith("services/")
                }
                == changed,
                "delta_payload_mismatch",
            )
            package_id = manifest["package_id"]
            final = self.root / "generations" / package_id
            require(not os.path.lexists(final), "generation_exists")
            temporary = self.root / "generations" / (".part-" + uuid.uuid4().hex)
            temporary.mkdir(mode=0o700)
            try:
                data_dir = temporary / "files"
                data_dir.mkdir(mode=0o700)
                for name, row in plan["files"].items():
                    data = (
                        payload["services/" + name]
                        if name in changed
                        else (base_dir / name).read_bytes()
                    )
                    require(
                        len(data) == row["bytes"] and digest(data) == row["sha256"],
                        "delta_file_mismatch",
                    )
                    path = data_dir / name
                    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
                    write_new(path, data, row["mode"])
                    self.fault("stage_file")
                write_new(temporary / "package.fup", raw)
                for directory in sorted(
                    [temporary] + [p for p in temporary.rglob("*") if p.is_dir()],
                    key=lambda p: len(p.parts),
                    reverse=True,
                ):
                    sync_dir(directory)
                self.fault("stage_synced")
                os.rename(temporary, final)
                sync_dir(final.parent)
                self.fault("stage_published")
            except Exception:
                shutil.rmtree(temporary, ignore_errors=True)
                raise
            return self.generation(package_id)[0]

    def activate(self, package_id):
        with self.locked():
            state = self.state()
            require(state["pending"] is None, "trial_unresolved")
            receipt, _ = self.generation(package_id)
            require(
                version(receipt["version"]) > version(state["highest_version"]),
                "version_not_newer",
            )
            # Recheck the signed base at activation, not just staging.
            _, payload = verify(
                (self.root / "generations" / package_id / "package.fup").read_bytes(),
                self.key,
                self.target,
                self.hardware_id,
            )
            baseline = (
                self.generation(state["confirmed"])[0] if state["confirmed"] else None
            )
            require(
                plan_of(payload)["base"]
                == (
                    {k: baseline[k] for k in ("package_id", "tree_sha256", "version")}
                    if baseline
                    else None
                ),
                "base_mismatch",
            )
            state.update(
                current=package_id,
                highest_version=receipt["version"],
                pending={
                    "package_id": package_id,
                    "nonce": uuid.uuid4().hex,
                    "attempted": False,
                },
            )
            self.save(state)
            self.fault("activate_published")
            return state

    def boot(self):
        with self.locked():
            state = self.state()
            pending = state["pending"]
            if pending and pending["attempted"]:
                state.update(current=state["confirmed"], pending=None)
                self.save(state)
            elif pending:
                try:
                    self.generation(state["current"])
                except Exception:
                    state.update(current=state["confirmed"], pending=None)
                    self.save(state)
                else:
                    pending["attempted"] = True
                    self.save(state)
                    self.fault("boot_marked")
            receipt, directory = (
                self.generation(state["current"]) if state["current"] else (None, None)
            )
            return {
                "state": state,
                "service_directory": str(directory) if directory else None,
                "receipt": receipt,
                "production_flashable": False,
            }

    def confirm(self, package_id, nonce):
        with self.locked():
            state = self.state()
            pending = state["pending"]
            require(
                pending
                and pending["attempted"]
                and pending["package_id"] == package_id
                and pending["nonce"] == nonce,
                "trial_confirmation_mismatch",
            )
            self.generation(package_id)
            state.update(confirmed=package_id, pending=None)
            self.save(state)
            return state


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--public-key", type=Path, required=True)
    parser.add_argument("--target", choices=TARGETS, required=True)
    parser.add_argument("--hardware-id", default="host-lab")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("boot")
    sub.add_parser("status")
    build = sub.add_parser("build")
    build.add_argument("--source", type=Path, required=True)
    build.add_argument("--key", type=Path, required=True)
    build.add_argument("--version", required=True)
    build.add_argument("--output", type=Path, required=True)
    stage = sub.add_parser("stage")
    stage.add_argument("package", type=Path)
    activate = sub.add_parser("activate")
    activate.add_argument("package_id")
    confirm = sub.add_parser("confirm")
    confirm.add_argument("package_id")
    confirm.add_argument("nonce")
    args = parser.parse_args()
    key = serialization.load_pem_public_key(args.public_key.read_bytes())
    require(isinstance(key, Ed25519PublicKey), "ed25519_required")
    lab = IncrementalLab(args.root, key, args.target, args.hardware_id)
    if args.command == "build":
        private = serialization.load_pem_private_key(
            args.key.read_bytes(), password=None
        )
        require(isinstance(private, Ed25519PrivateKey), "ed25519_required")
        with lab.locked():
            state = lab.state()
            require(state["pending"] is None, "trial_unresolved")
            base = lab.generation(state["confirmed"])[0] if state["confirmed"] else None
            raw = build_increment(
                args.source, private, args.target, args.hardware_id, args.version, base
            )
        verify(raw, key, args.target, args.hardware_id)
        write_new(args.output, raw)
        result = {
            "package": args.output.name,
            "sha256": digest(raw),
            "production_flashable": False,
        }
    elif args.command == "stage":
        require(regular(args.package).st_size <= MAX_PAYLOAD + 65624, "package_limit")
        result = lab.stage(args.package.read_bytes())
    elif args.command == "activate":
        result = lab.activate(args.package_id)
    elif args.command == "confirm":
        result = lab.confirm(args.package_id, args.nonce)
    elif args.command == "boot":
        result = lab.boot()
    else:
        with lab.locked():
            result = lab.state()
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
