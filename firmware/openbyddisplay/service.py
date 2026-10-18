"""Loopback-only, no-login instrument update laboratory.

This is an executable host model for the future QNX port, not flashable firmware.
It verifies independent signatures/targets, stages data and switches a sandbox
generation pointer. It never executes package scripts or writes vehicle partitions.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import socketserver
import threading
import uuid

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

from .packages import MAX_PAYLOAD, TARGETS, require, unique_object, verify


def sync_directory(path: Path) -> None:
    fd = os.open(path, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def atomic_json(path: Path, value: dict) -> None:
    temp = path.with_name(path.name + ".part")
    with temp.open("w", encoding="utf-8") as output:
        json.dump(value, output, ensure_ascii=False)
        output.flush()
        os.fsync(output.fileno())
    os.replace(temp, path)
    sync_directory(path.parent)


class Laboratory:
    def __init__(
        self,
        root: Path,
        key: Ed25519PublicKey,
        target: str,
        hardware_id: str = "host-lab",
    ) -> None:
        require(target in TARGETS and not root.is_symlink(), "target_or_root_invalid")
        self.root, self.key, self.target, self.hardware_id = (
            root.resolve(),
            key,
            target,
            hardware_id,
        )
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        (self.root / "jobs").mkdir(exist_ok=True, mode=0o700)
        (self.root / "generations").mkdir(exist_ok=True, mode=0o700)
        self.lock = threading.RLock()
        self.active: str | None = None
        self.epoch = uuid.uuid4().hex
        self.cast_epoch = self.epoch
        self.cast_revision = 0
        self.cast_state = "idle"
        self.cast_profile_id = 1
        for path in (self.root / "jobs").glob("*/state.json"):
            state = json.loads(path.read_text())
            if state["stage"] in ("receiving", "validating"):
                state.update(
                    stage="failed",
                    error="upload_interrupted",
                    worker_active=False,
                    ok=False,
                )
                atomic_json(path, state)
            elif state["stage"] == "installing":
                current = self.current()
                applied = current.get("package_id") == state.get(
                    "package_id"
                ) and self.generation_matches(state)
                state.update(
                    stage="applied" if applied else "recovery_required",
                    applied=applied,
                    worker_active=False,
                    ok=applied,
                    error="" if applied else "installation_result_unknown",
                )
                atomic_json(path, state)

    def current(self) -> dict:
        path = self.root / "current.json"
        return json.loads(path.read_text()) if path.exists() else {}

    def generation_matches(self, state: dict) -> bool:
        try:
            manifest, files = verify(
                (self.directory(state["update_id"]) / "package.fup").read_bytes(),
                self.key,
                self.target,
                self.hardware_id,
            )
            require(
                manifest["package_id"] == state["package_id"], "generation_mismatch"
            )
            directory = self.root / "generations" / manifest["package_id"]
            return (
                not directory.is_symlink()
                and directory.is_dir()
                and all(
                    not (directory / name).is_symlink()
                    and (directory / name).read_bytes() == value
                    for name, value in files.items()
                    if name != "install.sh"
                )
            )
        except Exception:
            return False

    def directory(self, job_id: str) -> Path:
        require(
            isinstance(job_id, str)
            and re.fullmatch(r"[0-9a-f]{32}", job_id) is not None,
            "update_id_invalid",
        )
        return self.root / "jobs" / job_id

    def load(self, job_id: str) -> dict:
        path = self.directory(job_id) / "state.json"
        require(path.is_file(), "update_not_found")
        return json.loads(path.read_text())

    def save(self, state: dict) -> dict:
        atomic_json(self.directory(state["update_id"]) / "state.json", state)
        return dict(state)

    def info(self) -> dict:
        return {
            "ok": True,
            "service": "openbyddisplay-lab",
            "update_protocol": 2,
            "receive_available": True,
            "apply_available": True,
            "component_apply_available": True,
            "firmware_apply_available": False,
            "component_key_configured": True,
            "component_signature": "Ed25519-FUP2",
            "component_payload_max_bytes": MAX_PAYLOAD,
            "firmware_max_bytes": 536870912,
            "authentication": "none",
            "target": self.target,
            "hardware_id": self.hardware_id,
            "runtime": "host-lab",
            "production_flashable": False,
        }

    def reserve(self, request: dict) -> tuple[dict, bool]:
        with self.lock:
            job_id = request.get("update_id")
            directory = self.directory(job_id)
            if directory.exists():
                return dict(self.load(job_id), duplicate=True), False
            require(self.active is None, "update_busy")
            require(request.get("kind") == "components", "update_installer_unavailable")
            size = request.get("bytes")
            require(
                type(size) is int and 1 <= size <= MAX_PAYLOAD + 65624,
                "update_size_invalid",
            )
            require(
                not any(
                    json.loads(p.read_text())["stage"]
                    in ("ready", "installing", "recovery_required")
                    for p in (self.root / "jobs").glob("*/state.json")
                ),
                "previous_update_unresolved",
            )
            directory.mkdir(mode=0o700)
            state = {
                "ok": True,
                "update_id": job_id,
                "kind": "components",
                "stage": "receiving",
                "received_bytes": 0,
                "total_bytes": size,
                "worker_active": True,
                "applied": False,
                "runtime": "host-lab",
                "production_flashable": False,
                "error": "",
            }
            self.save(state)
            self.active = job_id
            return state, True

    def receive(self, request: dict, reader, sender) -> None:
        state, new = self.reserve(request)
        if not new:
            sender(state)
            return
        job_id = state["update_id"]
        directory = self.directory(job_id)
        part = directory / "package.part"
        try:
            sender({"ok": True, "ready": True, "update_id": job_id})
            with part.open("xb") as output:
                remaining = state["total_bytes"]
                while remaining:
                    chunk = reader.read(min(65536, remaining))
                    require(bool(chunk), "upload_interrupted")
                    output.write(chunk)
                    remaining -= len(chunk)
                    state["received_bytes"] += len(chunk)
                output.flush()
                os.fsync(output.fileno())
            with self.lock:
                state.update(stage="validating")
                self.save(state)
                manifest, files = verify(
                    part.read_bytes(), self.key, self.target, self.hardware_id
                )
                require(
                    "obd-incremental.json" not in files,
                    "incremental_requires_transaction_manager",
                )
                state.update(
                    stage="ready",
                    worker_active=False,
                    package_id=manifest["package_id"],
                    version=manifest["version"],
                )
                os.replace(part, directory / "package.fup")
                confirmed = self.save(state)
        except Exception:
            with self.lock:
                state.update(
                    ok=False,
                    stage="failed",
                    worker_active=False,
                    error="package_or_transfer_invalid",
                )
                self.save(state)
            raise
        finally:
            part.unlink(missing_ok=True)
            with self.lock:
                self.active = None
        # A lost final receipt must not change a durably committed ready job.
        sender(confirmed)

    def apply(self, job_id: str) -> dict:
        with self.lock:
            state = self.load(job_id)
            if state["stage"] == "applied":
                return dict(state, duplicate=True)
            require(
                state["stage"] == "ready" and not state["worker_active"],
                "update_not_ready",
            )
            manifest, files = verify(
                (self.directory(job_id) / "package.fup").read_bytes(),
                self.key,
                self.target,
                self.hardware_id,
            )
            current = self.current()
            require(
                "obd-incremental.json" not in files,
                "incremental_requires_transaction_manager",
            )
            require(
                tuple(map(int, manifest["version"].split(".")))
                > tuple(map(int, current.get("version", "0.0.0").split("."))),
                "version_not_newer",
            )
            state.update(stage="installing", worker_active=True)
            self.save(state)
            package_id = manifest["package_id"]
            temporary = self.root / "generations" / (package_id + ".part")
            final = self.root / "generations" / package_id
            created_final = False
            try:
                require(
                    not temporary.exists() and not final.exists(), "generation_exists"
                )
                temporary.mkdir(mode=0o700)
                for name, content in files.items():
                    if name == "install.sh":
                        continue  # Intentionally never run package scripts in this host laboratory.
                    path = temporary / name
                    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
                    with path.open("xb") as output:
                        output.write(content)
                        output.flush()
                        os.fsync(output.fileno())
                    path.chmod(0o600)
                directories = [temporary] + [
                    p for p in temporary.rglob("*") if p.is_dir()
                ]
                for directory in sorted(
                    directories, key=lambda p: len(p.parts), reverse=True
                ):
                    sync_directory(directory)
                os.replace(temporary, final)
                created_final = True
                sync_directory(final.parent)
                atomic_json(
                    self.root / "current.json",
                    {
                        "package_id": package_id,
                        "version": manifest["version"],
                        "target": self.target,
                        "hardware_id": self.hardware_id,
                        "runtime": "host-lab",
                        "previous": {
                            k: current[k]
                            for k in ("package_id", "version")
                            if k in current
                        },
                    },
                )
                state.update(stage="applied", applied=True, worker_active=False)
                return self.save(state)
            except Exception:
                # If the pointer was published, do not report a rollback that did not occur.
                published = self.current().get("package_id") == package_id
                state.update(
                    ok=published,
                    stage="applied" if published else "failed",
                    applied=published,
                    worker_active=False,
                    error="" if published else "sandbox_install_failed",
                )
                try:
                    self.save(state)
                finally:
                    shutil.rmtree(temporary, ignore_errors=True)
                    if created_final and not published:
                        shutil.rmtree(final, ignore_errors=True)
                raise

    def cancel(self, job_id: str) -> dict:
        with self.lock:
            state = self.load(job_id)
            require(
                job_id != self.active
                and state["stage"]
                not in ("installing", "applied", "recovery_required"),
                "update_cannot_cancel",
            )
            (self.directory(job_id) / "package.fup").unlink(missing_ok=True)
            state.update(ok=True, stage="cancelled", worker_active=False)
            return self.save(state)


    def cast_status(self) -> dict:
        with self.lock:
            return {
                "ok": True,
                "epoch": self.cast_epoch,
                "revision": self.cast_revision,
                "state": self.cast_state,
                "target_casting": self.cast_state == "playing",
                "selected_profile_id": self.cast_profile_id,
                "heartbeat_protocol": 1,
                "heartbeat_port": 47785,
                "runtime": "host-lab",
            }

    def cast_profiles(self) -> dict:
        return {
            "ok": True,
            "service": "cast_profiles",
            "runtime": "host-lab",
            "profiles": [
                {
                    "profile_id": 1,
                    "theme_id": 4,
                    "x": 0,
                    "y": 0,
                    "width": 1920,
                    "height": 720,
                    "opacity": 255,
                    "radius": 0,
                },
                {
                    "profile_id": 2,
                    "theme_id": 4,
                    "x": 0,
                    "y": 0,
                    "width": 760,
                    "height": 720,
                    "opacity": 255,
                    "radius": 0,
                },
                {
                    "profile_id": 3,
                    "theme_id": 4,
                    "x": 410,
                    "y": 0,
                    "width": 1100,
                    "height": 720,
                    "opacity": 255,
                    "radius": 0,
                },
            ],
        }

    def cast_start(self, request: dict) -> dict:
        require(isinstance(request.get("request_id"), str), "request_id_required")
        profile = request.get("profile_id")
        with self.lock:
            self.cast_state = "playing"
            self.cast_revision += 1
            if isinstance(profile, int):
                self.cast_profile_id = profile
            return {
                "ok": True,
                "op": "cast.start",
                "epoch": self.cast_epoch,
                "revision": self.cast_revision,
                "state": self.cast_state,
                "selected_profile_id": self.cast_profile_id,
                "runtime": "host-lab",
            }

    def cast_stop(self, request: dict) -> dict:
        with self.lock:
            self.cast_state = "idle"
            self.cast_revision += 1
            return {
                "ok": True,
                "op": "cast.stop",
                "epoch": self.cast_epoch,
                "revision": self.cast_revision,
                "state": self.cast_state,
                "target_casting": False,
                "runtime": "host-lab",
            }

    def cast_switch(self, request: dict) -> dict:
        require(isinstance(request.get("request_id"), str), "request_id_required")
        with self.lock:
            self.cast_revision += 1
            return {
                "ok": True,
                "op": "cast.switch",
                "epoch": self.cast_epoch,
                "revision": self.cast_revision,
                "state": self.cast_state,
                "runtime": "host-lab",
            }

    def cast_heartbeat(self, request: dict) -> dict:
        with self.lock:
            require(self.cast_state == "playing", "cast_not_active")
            require(request.get("epoch") == self.cast_epoch, "epoch_mismatch")
            return {
                "ok": True,
                "op": "cast.heartbeat",
                "epoch": self.cast_epoch,
                "revision": self.cast_revision,
                "runtime": "host-lab",
            }


class Handler(socketserver.StreamRequestHandler):
    def send(self, value: dict) -> None:
        self.wfile.write((json.dumps(value, separators=(",", ":")) + "\n").encode())
        self.wfile.flush()

    def handle(self) -> None:
        self.connection.settimeout(30)
        try:
            line = self.rfile.readline(4097)
            require(len(line) <= 4096 and line.endswith(b"\n"), "request_invalid")
            request = json.loads(line.decode("utf-8"), object_pairs_hook=unique_object)
            require(
                isinstance(request, dict)
                and not {"token", "client_time"}.intersection(request),
                "no_commercial_auth_fields",
            )
            lab = self.server.lab
            op = request.get("op")
            if op == "update.info":
                self.send(lab.info())
            elif op == "version":
                self.send(
                    {
                        "ok": True,
                        "release": "0.1.0",
                        "query_protocol": 2,
                        "cast_protocol": 1,
                        "service": "openbyddisplay-lab",
                    }
                )
            elif op == "cast.status":
                self.send(lab.cast_status())
            elif op == "cast.profile.get":
                self.send(lab.cast_profiles())
            elif op == "cast.start":
                self.send(lab.cast_start(request))
            elif op == "cast.stop":
                self.send(lab.cast_stop(request))
            elif op == "cast.switch":
                self.send(lab.cast_switch(request))
            elif op == "cast.heartbeat":
                self.send(lab.cast_heartbeat(request))
            elif op == "cast.arm":
                self.send({"ok": True, "op": "cast.arm", "runtime": "host-lab"})
            elif op == "cast.set":
                self.send({"ok": True, "op": "cast.set", "runtime": "host-lab"})
            elif op == "cast.keepalive":
                self.send({"ok": True, "op": "cast.keepalive", "runtime": "host-lab"})
            elif op in {
                "music.show",
                "music.hide",
                "navigation.show",
                "navigation.hide",
                "music.config.set",
                "navigation.config.set",
            }:
                self.send({"ok": True, "op": op, "runtime": "host-lab", "overlay": True})
            elif op == "update.upload":
                lab.receive(request, self.rfile, self.send)
            elif op == "update.status":
                with lab.lock:
                    self.send(lab.load(request.get("update_id")))
            elif op == "update.cancel":
                self.send(lab.cancel(request.get("update_id")))
            elif op == "update.apply":
                require(
                    request.get("parked") is True
                    and request.get("confirm") == "INSTALL_COMPONENTS",
                    "installation_intent_required",
                )
                self.send(lab.apply(request.get("update_id")))
            else:
                raise ValueError("unknown_op")
        except Exception as error:
            code = (
                str(error)
                if isinstance(error, ValueError)
                and re.fullmatch(r"[a-z_]{1,80}", str(error))
                else "request_or_package_invalid"
            )
            try:
                self.send({"ok": False, "error": code})
            except OSError:
                pass


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, port: int, lab: Laboratory) -> None:
        self.lab = lab
        super().__init__(("127.0.0.1", port), Handler)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state", type=Path, required=True)
    parser.add_argument("--public-key", type=Path, required=True)
    parser.add_argument("--target", choices=TARGETS, required=True)
    parser.add_argument("--hardware-id", default="host-lab")
    parser.add_argument("--port", type=int, default=47785)
    parser.add_argument("--ready-file", type=Path)
    args = parser.parse_args()
    key = serialization.load_pem_public_key(args.public_key.read_bytes())
    require(isinstance(key, Ed25519PublicKey), "ed25519_public_key_required")
    lab = Laboratory(args.state, key, args.target, args.hardware_id)
    with Server(args.port, lab) as server:
        info = {
            "port": server.server_address[1],
            "target": args.target,
            "runtime": "host-lab",
            "production_flashable": False,
        }
        if args.ready_file:
            atomic_json(args.ready_file, info)
        print(json.dumps(info), flush=True)
        server.serve_forever()


if __name__ == "__main__":
    main()
