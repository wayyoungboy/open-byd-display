"""Host-only signed fixture for the Android client / real native updater test.

No Python update server, vendor code or target execution. The real supervisor
confirms a real host child; its media health reports are explicit ABI doubles.
"""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import signal
import socket
import struct
import subprocess
import sys
import time

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / "firmware/tools"))
from native_generation import build  # noqa: E402


def main(home: Path, target: str):
    targets = {"song-plus-dmi-2021": "1" * 64, "tang-plus-dmi-2021": "2" * 64}
    if target not in targets:
        raise ValueError("test target")
    home = home.resolve(strict=True)
    native = home / "build"
    subprocess.run(
        [
            "cmake",
            "-S",
            str(REPO / "firmware/native"),
            "-B",
            str(native),
            "-DOBD_SANITIZE=OFF",
            "-DCMAKE_BUILD_TYPE=Debug",
        ],
        check=True,
        timeout=90,
    )
    subprocess.run(
        [
            "cmake",
            "--build",
            str(native),
            "--target",
            "native-updater-host",
            "native-store-host",
            "native-supervisor-host",
            "supervisor-child-healthy",
            "-j2",
        ],
        check=True,
        timeout=90,
    )
    key = Ed25519PrivateKey.from_private_bytes(bytes(range(32)))
    public = key.public_key().public_bytes(
        serialization.Encoding.Raw, serialization.PublicFormat.Raw
    )
    (home / "public.bin").write_bytes(public)
    common = [
        "--root",
        str(home / "store"),
        "--key",
        str(home / "public.bin"),
        "--key-sha256",
        hashlib.sha256(public).hexdigest(),
        "--target",
        target,
        "--baseline",
        targets[target],
    ]

    def store(*args):
        for _ in range(100):
            result = subprocess.run(
                [str(native / "native-store-host"), *common, *args],
                capture_output=True,
                text=True,
                timeout=5,
            )
            # Only read-only observations may retry the supervisor's short lock.
            if result.returncode == 75 and args == ("status",):
                time.sleep(0.01)
                continue
            result.check_returncode()
            return json.loads(result.stdout) if result.stdout else None
        raise TimeoutError("store observation remained busy")

    for model in targets:
        profile = bytearray(64)
        profile[:8] = b"OBDPRO1\n"
        profile[8:10] = bytes([1 if model.startswith("song") else 2, 1])
        profile[12:16] = bytes([127, 0, 0, 1])
        struct.pack_into(
            "<10H", profile, 16, 0, 1280, 720, 0, 0, 1280, 720, 0, 30, 47792
        )
        raw = build(
            {
                "service.elf": (
                    (native / "supervisor-child-healthy").read_bytes(),
                    0o700,
                ),
                "profile.dat": (bytes(profile), 0o600),
            },
            model,
            targets[model],
            "1.0.0",
            key,
        )
        (home / f"{model}.obg").write_bytes(raw)
    store("init")
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    child = subprocess.Popen(
        [
            str(native / "native-updater-host"),
            *common,
            "--address",
            "127.0.0.1",
            "--port",
            str(port),
        ]
    )
    manager = None
    try:
        for _ in range(200):
            if child.poll() is not None:
                raise RuntimeError("native receiver exited")
            try:
                with socket.create_connection(("127.0.0.1", port), timeout=0.1):
                    break
            except OSError:
                time.sleep(0.02)
        else:
            raise TimeoutError("native receiver startup")
        ready = home / "ready.json"
        next_file = home / "ready.next"
        next_file.write_text(
            json.dumps({"port": port, "target": target, "baseline": targets[target]})
        )
        next_file.rename(ready)
        for line in sys.stdin:
            request = json.loads(line)
            if request["op"] == "stop":
                return
            if request["op"] != "confirm" or request["id"] != "a" * 32:
                raise ValueError("test operation")
            if manager is not None:
                raise ValueError("manager already started")
            manager = subprocess.Popen(
                [str(native / "native-supervisor-host"), *common, "--watch"]
            )
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                if manager.poll() is not None:
                    raise RuntimeError("supervisor exited before confirmation")
                state = store("status")
                if state["current"]:
                    (home / "confirmed.json").write_text(json.dumps(state))
                    break
                time.sleep(0.02)
            else:
                raise TimeoutError("supervisor did not confirm host media double")
    finally:
        if manager:
            manager.terminate()
            manager.wait(timeout=6)
        child.terminate()
        child.wait(timeout=5)


def stop(_signal, _frame):
    raise SystemExit(0)


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, stop)
    os.umask(0o077)
    main(Path(sys.argv[1]), sys.argv[2])
