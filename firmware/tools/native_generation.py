"""Owner-signed native generations, independent of third-party FUP installers.

Flat files, full manifest and per-file hashes. No install scripts. A new wire
format; do not upload it to existing FUP2/ZIP receiver endpoints.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import uuid

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

DOMAIN = b"OBD-NATIVE-GENERATION-V1\n"
MAX_PAYLOAD = 16 * 1024 * 1024


def build(files, target, baseline, version, key, package_id=None):
    if target not in {"song-plus-dmi-2021", "tang-plus-dmi-2021"} or not re.fullmatch(
        r"[0-9a-f]{64}", baseline
    ):
        raise ValueError("identity")
    if not re.fullmatch(
        r"(0|[1-9][0-9]{0,4})\.(0|[1-9][0-9]{0,4})\.(0|[1-9][0-9]{0,4})", version
    ):
        raise ValueError("version")
    parts = tuple(map(int, version.split(".")))
    if (
        max(parts) > 65535
        or not 2 <= len(files) <= 16
        or not {"service.elf", "profile.dat"} <= files.keys()
    ):
        raise ValueError("file_count_or_version")
    package_id = package_id or uuid.uuid4().hex
    if not re.fullmatch(r"[0-9a-f]{32}", package_id) or package_id == "0" * 32:
        raise ValueError("package_id")
    payload, table = bytearray(), bytearray()
    for name, (data, mode) in sorted(files.items()):
        if (
            not re.fullmatch(r"[A-Za-z0-9_-][A-Za-z0-9_.-]{0,62}", name)
            or name in {"bundle.obg", "state.bin", "identity.bin", "store.lock"}
            or mode not in {0o600, 0o700}
            or not data
            or (name == "service.elf" and mode != 0o700)
            or (name == "profile.dat" and mode != 0o600)
        ):
            raise ValueError("file")
        row = bytearray(128)
        row[: len(name)] = name.encode()
        struct.pack_into("<III", row, 64, len(data), mode, len(payload))
        row[96:128] = hashlib.sha256(data).digest()
        table += row
        payload += data
    if not 0 < len(payload) <= MAX_PAYLOAD:
        raise ValueError("payload_limit")
    header = bytearray(208)
    header[:8] = b"OBDGEN1\n"
    struct.pack_into("<II", header, 8, len(payload), len(files))
    header[16 : 16 + len(target)] = target.encode()
    header[48:80] = bytes.fromhex(baseline)
    header[80:96] = bytes.fromhex(package_id)
    struct.pack_into("<3H", header, 96, *parts)
    struct.pack_into("<I", header, 104, len(table))
    header[112:144] = hashlib.sha256(table).digest()
    header[144:208] = key.sign(DOMAIN + header[:144] + table)
    return bytes(header + table + payload)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--target", required=True)
    parser.add_argument("--baseline-sha256", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--private-key", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    files = {}
    entries = []
    total = 0
    for path in args.source.iterdir():
        if len(entries) >= 16 or path.is_symlink() or not path.is_file():
            parser.error("flat regular source files required")
        info = path.stat()
        total += info.st_size
        if not 0 < info.st_size <= MAX_PAYLOAD or total > MAX_PAYLOAD:
            parser.error("source payload limit")
        entries.append((path, info))
    for path, info in entries:
        with path.open("rb") as stream:
            raw = stream.read(info.st_size + 1)
        after = path.stat()
        if len(raw) != info.st_size or (
            info.st_dev,
            info.st_ino,
            info.st_size,
            info.st_mtime_ns,
        ) != (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns):
            parser.error("source changed while reading")
        files[path.name] = (raw, 0o700 if info.st_mode & 0o111 else 0o600)
    with args.private_key.open("rb") as stream:
        pem = stream.read(16385)
    if len(pem) > 16384:
        parser.error("key input limit")
    key = serialization.load_pem_private_key(pem, password=None)
    if not isinstance(key, Ed25519PrivateKey):
        parser.error("Ed25519 key required")
    raw = build(files, args.target, args.baseline_sha256, args.version, key)
    with args.output.open("xb") as stream:
        stream.write(raw)
    print(
        json.dumps(
            {
                "sha256": hashlib.sha256(raw).hexdigest(),
                "bytes": len(raw),
                "production_flashable": False,
            }
        )
    )


if __name__ == "__main__":
    main()
