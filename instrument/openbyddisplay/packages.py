"""FUP2 interoperability format; independent keys and a signed development target.

This module never executes install.sh. A self-signed package is not a vendor license
and is not accepted by an instrument that does not trust its signing public key.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import struct
import uuid

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import (
    Ed25519PrivateKey,
    Ed25519PublicKey,
)

MAGIC = b"FUP2\r\n\x1a\n"
DOMAIN = b"FISSION-COMPONENT-SCRIPT-V2\n"
MAX_PAYLOAD = 256 * 1024 * 1024
HEADER = struct.Struct("<8sIIQ64s")
TARGETS = json.loads((Path(__file__).resolve().parents[1] / "targets.json").read_text())


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def unique_object(pairs: list[tuple[str, object]]) -> dict:
    result = {}
    for key, value in pairs:
        require(key not in result, "duplicate_json_key")
        result[key] = value
    return result


def safe_path(value: str) -> str:
    require(
        isinstance(value, str) and 1 <= len(value.encode()) <= 239,
        "package_path_invalid",
    )
    require(
        re.fullmatch(r"[A-Za-z0-9_./-]+", value) is not None, "package_path_invalid"
    )
    path = PurePosixPath(value)
    pieces = value.split("/")
    require(
        not path.is_absolute()
        and len(pieces) <= 16
        and all(p not in ("", ".", "..") for p in pieces),
        "package_path_invalid",
    )
    return value


def verify(
    raw: bytes, public_key: Ed25519PublicKey, target: str, hardware_id: str
) -> tuple[dict, dict[str, bytes]]:
    require(len(raw) >= HEADER.size, "package_truncated")
    magic, manifest_size, reserved, payload_size, signature = HEADER.unpack_from(raw)
    require(
        magic == MAGIC and reserved == 0 and 1 <= manifest_size <= 65536,
        "package_header_invalid",
    )
    require(
        1 <= payload_size <= MAX_PAYLOAD
        and len(raw) == HEADER.size + manifest_size + payload_size,
        "package_size_invalid",
    )
    manifest_raw = raw[HEADER.size : HEADER.size + manifest_size]
    public_key.verify(signature, DOMAIN + manifest_raw)
    manifest = json.loads(manifest_raw.decode("utf-8"), object_pairs_hook=unique_object)
    require(
        isinstance(manifest, dict)
        and set(manifest) == {"format", "version", "package_id", "files"},
        "package_manifest_invalid",
    )
    require(
        type(manifest["format"]) is int and manifest["format"] == 2,
        "package_format_invalid",
    )
    version = manifest["version"]
    require(
        isinstance(version, str)
        and re.fullmatch(
            r"(0|[1-9][0-9]{0,4})\.(0|[1-9][0-9]{0,4})\.(0|[1-9][0-9]{0,4})", version
        )
        is not None,
        "package_version_invalid",
    )
    require(all(int(v) <= 65535 for v in version.split(".")), "package_version_invalid")
    require(
        isinstance(manifest["package_id"], str)
        and re.fullmatch(r"[0-9a-f]{32}", manifest["package_id"]) is not None,
        "package_id_invalid",
    )
    rows = manifest["files"]
    require(
        isinstance(rows, list) and 1 <= len(rows) <= 128, "package_file_count_invalid"
    )
    files: dict[str, bytes] = {}
    pos = HEADER.size + manifest_size
    for row in rows:
        require(
            isinstance(row, dict) and set(row) == {"path", "bytes", "sha256"},
            "package_file_invalid",
        )
        path = safe_path(row["path"])
        require(
            not any(
                p == path or p.startswith(path + "/") or path.startswith(p + "/")
                for p in files
            ),
            "package_path_conflict",
        )
        size, digest = row["bytes"], row["sha256"]
        require(
            type(size) is int and 0 <= size <= MAX_PAYLOAD and pos + size <= len(raw),
            "package_file_size_invalid",
        )
        require(
            isinstance(digest, str)
            and re.fullmatch(r"[0-9a-f]{64}", digest) is not None,
            "package_digest_invalid",
        )
        content = raw[pos : pos + size]
        require(hashlib.sha256(content).hexdigest() == digest, "package_hash_mismatch")
        files[path] = content
        pos += size
    require(pos == len(raw), "package_payload_tail")
    script = files.get("install.sh", b"")
    require(
        1 <= len(script) <= 262144
        and b"\0" not in script
        and b"\r" not in script
        and not script.startswith(b"\xef\xbb\xbf"),
        "package_script_invalid",
    )
    # Independent service semantics: signed data is staged, scripts are never executed.
    require(
        "xhh-target.json" in files and len(files["xhh-target.json"]) <= 4096,
        "target_metadata_missing",
    )
    metadata = json.loads(
        files["xhh-target.json"].decode(), object_pairs_hook=unique_object
    )
    require(
        target in TARGETS
        and metadata.get("target") == target
        and metadata.get("hardware_id") == hardware_id,
        "target_or_hardware_mismatch",
    )
    require(
        metadata.get("runtime") == "host-lab"
        and metadata.get("production_flashable") is False,
        "development_package_required",
    )
    return manifest, files


def build(
    source: Path, key: Ed25519PrivateKey, target: str, hardware_id: str, version: str
) -> bytes:
    require(
        target in TARGETS and source.is_dir() and not source.is_symlink(),
        "source_or_target_invalid",
    )
    files = {
        "install.sh": b"#!/bin/sh\n# OpenBYDDisplay lab metadata; never executed by the lab receiver.\nexit 0\n",
        "xhh-target.json": json.dumps(
            {
                "target": target,
                "hardware_id": hardware_id,
                "runtime": "host-lab",
                "production_flashable": False,
            },
            separators=(",", ":"),
        ).encode(),
    }
    for path in sorted(source.rglob("*")):
        require(not path.is_symlink(), "source_symlink_forbidden")
        if path.is_file():
            relative = safe_path("services/" + path.relative_to(source).as_posix())
            require(
                path.stat().st_size <= MAX_PAYLOAD
                and sum(map(len, files.values())) + path.stat().st_size <= MAX_PAYLOAD,
                "payload_too_large",
            )
            files[relative] = path.read_bytes()
    manifest = {
        "format": 2,
        "version": version,
        "package_id": uuid.uuid4().hex,
        "files": [
            {"path": p, "bytes": len(v), "sha256": hashlib.sha256(v).hexdigest()}
            for p, v in files.items()
        ],
    }
    manifest_raw = json.dumps(manifest, separators=(",", ":")).encode()
    payload = b"".join(files.values())
    require(
        len(manifest_raw) <= 65536 and len(payload) <= MAX_PAYLOAD, "package_too_large"
    )
    raw = (
        HEADER.pack(
            MAGIC, len(manifest_raw), 0, len(payload), key.sign(DOMAIN + manifest_raw)
        )
        + manifest_raw
        + payload
    )
    verify(raw, key.public_key(), target, hardware_id)
    return raw


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    keys = sub.add_parser("keygen")
    keys.add_argument("directory", type=Path)
    pack = sub.add_parser("build")
    pack.add_argument("--source", type=Path, required=True)
    pack.add_argument("--key", type=Path, required=True)
    pack.add_argument("--target", choices=TARGETS, required=True)
    pack.add_argument("--hardware-id", default="host-lab")
    pack.add_argument("--version", required=True)
    pack.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "keygen":
        args.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        private_path, public_path = (
            args.directory / "development-private.pem",
            args.directory / "development-public.pem",
        )
        require(
            not private_path.exists() and not public_path.exists(), "key_files_exist"
        )
        key = Ed25519PrivateKey.generate()
        with private_path.open("xb") as output:
            private_path.chmod(0o600)
            output.write(
                key.private_bytes(
                    serialization.Encoding.PEM,
                    serialization.PrivateFormat.PKCS8,
                    serialization.NoEncryption(),
                )
            )
        public_path.write_bytes(
            key.public_key().public_bytes(
                serialization.Encoding.PEM,
                serialization.PublicFormat.SubjectPublicKeyInfo,
            )
        )
        print("Development keys created; private key is not printed or published.")
    else:
        key = serialization.load_pem_private_key(args.key.read_bytes(), password=None)
        require(isinstance(key, Ed25519PrivateKey), "ed25519_key_required")
        raw = build(args.source, key, args.target, args.hardware_id, args.version)
        with args.output.open("xb") as output:
            output.write(raw)
        print(
            json.dumps(
                {
                    "package": args.output.name,
                    "sha256": hashlib.sha256(raw).hexdigest(),
                    "target": args.target,
                    "production_flashable": False,
                }
            )
        )


if __name__ == "__main__":
    main()
