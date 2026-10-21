"""Real native TCP/store integration on loopback; synthetic services never execute."""

import hashlib
import fcntl
import json
import os
from pathlib import Path
import socket
import struct
import subprocess
import sys
import tempfile
import time
import unittest

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
from native_generation import build

SERVER, STORE = sys.argv[1:3]
del sys.argv[1:3]
TARGETS = {"song-plus-dmi-2021": "1" * 64, "tang-plus-dmi-2021": "2" * 64}


class NativeUpdateTests(unittest.TestCase):
    def setUp(self):
        self.assertTrue(
            Path(SERVER).is_file(), "native update receiver not implemented"
        )
        self.temp = tempfile.TemporaryDirectory(prefix="obd-native-update-")
        self.home = Path(self.temp.name).resolve()
        self.key = Ed25519PrivateKey.from_private_bytes(bytes(range(32)))
        self.public = self.key.public_key().public_bytes(
            serialization.Encoding.Raw, serialization.PublicFormat.Raw
        )
        self.keyfile = self.home / "public.bin"
        self.keyfile.write_bytes(self.public)
        self.process = None
        self.target = "song-plus-dmi-2021"

    def tearDown(self):
        if hasattr(self, "process") and self.process:
            self.process.terminate()
            self.process.wait(timeout=5)
            self.process.stderr.close()
        if hasattr(self, "home"):
            for path in self.home.rglob("*"):
                if path.is_dir() and not path.is_symlink():
                    path.chmod(0o700)
            self.temp.cleanup()

    def common(self):
        return [
            "--root",
            str(self.root),
            "--key",
            str(self.keyfile),
            "--key-sha256",
            hashlib.sha256(self.public).hexdigest(),
            "--target",
            self.target,
            "--baseline",
            TARGETS[self.target],
        ]

    def store(self, *args):
        result = subprocess.run(
            [STORE, *self.common(), *args], capture_output=True, timeout=10
        )
        self.assertEqual(0, result.returncode, result.stderr.decode())
        return json.loads(result.stdout) if result.stdout else None

    def start(self, target=None, restart=False, crash=None):
        if self.process:
            self.process.terminate()
            self.process.wait(timeout=5)
            self.process.stderr.close()
        self.target = target or self.target
        self.root = self.home / ("store-" + self.target)
        if not restart:
            self.store("init")
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            self.port = probe.getsockname()[1]
        self.process = subprocess.Popen(
            [
                SERVER,
                *self.common(),
                "--address",
                "127.0.0.1",
                "--port",
                str(self.port),
            ],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            env=dict(os.environ, **({"OBD_STORE_TEST_CRASH": crash} if crash else {})),
        )
        for _ in range(150):
            if self.process.poll() is not None:
                self.fail(self.process.stderr.read().decode())
            try:
                with socket.create_connection(("127.0.0.1", self.port), timeout=0.1):
                    return
            except OSError:
                time.sleep(0.02)
        self.fail("native receiver did not listen")

    def request(self, body):
        with socket.create_connection(
            ("127.0.0.1", self.port), timeout=5
        ) as connection:
            connection.sendall(json.dumps(body, separators=(",", ":")).encode() + b"\n")
            return json.loads(connection.makefile("rb").readline())

    def package(self, target=None, version="1.0.0", enabled=True):
        target = target or self.target
        profile = bytearray(64)
        profile[:8] = b"OBDPRO1\n"
        profile[8:10] = bytes([1 if target.startswith("song") else 2, int(enabled)])
        profile[12:16] = bytes([127, 0, 0, 1])
        struct.pack_into(
            "<10H", profile, 16, 0, 1280, 720, 0, 0, 1280, 720, 0, 30, 47792
        )
        return build(
            {
                "service.elf": (b"SYNTHETIC SERVICE NEVER EXECUTED", 0o700),
                "profile.dat": (bytes(profile), 0o600),
            },
            target,
            TARGETS[target],
            version,
            self.key,
        )

    def upload(self, raw, ident="a" * 32, **overrides):
        body = dict(
            op="update.upload",
            update_id=ident,
            kind="native_generation",
            bytes=len(raw),
            sha256=hashlib.sha256(raw).hexdigest(),
            target=self.target,
            baseline_sha256=TARGETS[self.target],
        )
        body.update(overrides)
        with socket.create_connection(
            ("127.0.0.1", self.port), timeout=5
        ) as connection:
            connection.sendall(json.dumps(body, separators=(",", ":")).encode() + b"\n")
            stream = connection.makefile("rb")
            ready = json.loads(stream.readline())
            if not ready.get("ready") or ready.get("duplicate"):
                return ready
            for at in range(0, len(raw), 23):
                connection.sendall(raw[at : at + 23])
            return json.loads(stream.readline())

    def test_both_models_install_then_report_only_health_confirmed_success(self):
        for target in TARGETS:
            self.start(target)
            info = self.request(dict(op="update.info"))
            self.assertEqual(3, info["update_protocol"])
            self.assertEqual(target, info["target"])
            self.assertTrue(info["native_generation_available"])
            reply = self.upload(self.package())
            self.assertEqual("ready", reply["stage"])
            result = self.request(
                dict(
                    op="update.apply",
                    update_id="a" * 32,
                    parked=True,
                    confirm="INSTALL_NATIVE_GENERATION",
                )
            )
            self.assertEqual("awaiting_confirmation", result["stage"])
            self.assertFalse(result["applied"])
            selected = self.store("select")
            self.store(
                "confirm-test", selected["package_id"], "a" * 32, "HOST_TEST_HEALTHY"
            )
            confirmed = self.request(dict(op="update.status", update_id="a" * 32))
            self.assertEqual("applied", confirmed["stage"])
            self.assertTrue(confirmed["applied"])

    def test_ready_survives_restart_and_duplicate_never_resends(self):
        self.start()
        raw = self.package()
        self.assertEqual("ready", self.upload(raw)["stage"])
        self.start(restart=True)
        self.assertEqual(
            "ready", self.request(dict(op="update.status", update_id="a" * 32))["stage"]
        )
        self.assertTrue(self.upload(raw)["duplicate"])
        cancelled = self.request(dict(op="update.cancel", update_id="a" * 32))
        self.assertEqual("cancelled", cancelled["stage"])

    def test_wrong_model_and_signature_never_activate(self):
        self.start()
        self.assertFalse(self.upload(self.package(target="tang-plus-dmi-2021"))["ok"])
        self.assertEqual("", self.store("status")["candidate"])

    def test_disabled_profile_is_stageable_but_never_activated(self):
        self.start()
        reply = self.upload(self.package(enabled=False))
        self.assertEqual("ready", reply["stage"])
        self.assertFalse(reply["can_install"])
        reply = self.request(
            dict(
                op="update.apply",
                update_id="a" * 32,
                parked=True,
                confirm="INSTALL_NATIVE_GENERATION",
            )
        )
        self.assertFalse(reply["ok"])
        self.assertEqual("", self.store("status")["candidate"])

    def test_native_receiver_waits_for_listener_availability(self):
        self.root = self.home / "store-network-wait"
        self.store("init")
        with socket.socket() as held:
            held.bind(("127.0.0.1", 0))
            held.listen(1)
            self.port = held.getsockname()[1]
            self.process = subprocess.Popen(
                [
                    SERVER,
                    *self.common(),
                    "--address",
                    "127.0.0.1",
                    "--port",
                    str(self.port),
                ],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.PIPE,
            )
            time.sleep(0.4)
            self.assertIsNone(
                self.process.poll(), "receiver must wait for the NCM/listen address"
            )
        for _ in range(100):
            try:
                self.assertEqual(
                    3, self.request(dict(op="update.info"))["update_protocol"]
                )
                return
            except OSError:
                time.sleep(0.05)
        self.fail("receiver did not recover after bind became available")

    def test_startup_waits_for_short_store_transaction(self):
        self.root = self.home / "store-startup-busy"
        self.store("init")
        self.store("status")
        with (self.root / "store.lock").open("rb") as held:
            fcntl.flock(held, fcntl.LOCK_EX)
            with socket.socket() as probe:
                probe.bind(("127.0.0.1", 0))
                self.port = probe.getsockname()[1]
            self.process = subprocess.Popen(
                [
                    SERVER,
                    *self.common(),
                    "--address",
                    "127.0.0.1",
                    "--port",
                    str(self.port),
                ],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.PIPE,
            )
            time.sleep(0.4)
            self.assertIsNone(
                self.process.poll(),
                "a short startup transaction must not kill the receiver",
            )
        for _ in range(100):
            try:
                self.assertEqual(
                    3, self.request(dict(op="update.info"))["update_protocol"]
                )
                return
            except OSError:
                time.sleep(0.02)
        self.fail("receiver did not recover from the startup transaction")

    def test_installed_version_cannot_be_offered_for_activation_again(self):
        self.start()
        raw = self.package()
        self.upload(raw)
        self.request(
            dict(
                op="update.apply",
                update_id="a" * 32,
                parked=True,
                confirm="INSTALL_NATIVE_GENERATION",
            )
        )
        selected = self.store("select")
        self.store(
            "confirm-test", selected["package_id"], "a" * 32, "HOST_TEST_HEALTHY"
        )
        self.request(dict(op="update.status", update_id="a" * 32))
        replay = self.upload(raw, ident="b" * 32)
        self.assertFalse(replay["can_install"])

    def test_bad_signature_digest_and_duplicate_id_conflict(self):
        self.start()
        for mode, ident in [("signature", "a" * 32), ("digest", "b" * 32)]:
            raw = bytearray(self.package())
            if mode == "signature":
                raw[144] ^= 1
            reply = self.upload(
                bytes(raw),
                ident=ident,
                **({"sha256": "3" * 64} if mode == "digest" else {}),
            )
            self.assertFalse(reply["ok"])
            self.assertEqual("", self.store("status")["candidate"])
            self.request(dict(op="update.cancel", update_id=ident))
        raw = self.package()
        self.upload(raw, ident="c" * 32)
        self.assertEqual(
            "update_id_conflict", self.upload(self.package(), ident="c" * 32)["error"]
        )

    def test_partial_payload_and_lost_final_receipt_are_queryable(self):
        self.start()
        raw = self.package()
        for whole, ident in [(False, "a" * 32), (True, "b" * 32)]:
            declaration = dict(
                op="update.upload",
                update_id=ident,
                kind="native_generation",
                bytes=len(raw),
                sha256=hashlib.sha256(raw).hexdigest(),
                target=self.target,
                baseline_sha256=TARGETS[self.target],
            )
            with socket.create_connection(
                ("127.0.0.1", self.port), timeout=5
            ) as connection:
                connection.sendall(json.dumps(declaration).encode() + b"\n")
                with connection.makefile("rb") as stream:
                    self.assertTrue(json.loads(stream.readline())["ready"])
                    connection.sendall(raw if whole else raw[:37])
                    connection.shutdown(socket.SHUT_RDWR)
            status = self.request(dict(op="update.status", update_id=ident))
            self.assertEqual("ready" if whole else "failed", status["stage"])
            self.request(dict(op="update.cancel", update_id=ident))

    def test_activation_crash_reconciles_without_resubmitting(self):
        for target, (where, expected) in zip(
            TARGETS,
            [
                ("update-before-activate", "recovery_required"),
                ("update-after-activate", "awaiting_confirmation"),
            ],
        ):
            self.start(target=target, crash=where)
            self.upload(
                self.package(),
                ident=("a" if expected == "recovery_required" else "b") * 32,
            )
            ident = ("a" if expected == "recovery_required" else "b") * 32
            with self.assertRaises((json.JSONDecodeError, OSError)):
                self.request(
                    dict(
                        op="update.apply",
                        update_id=ident,
                        parked=True,
                        confirm="INSTALL_NATIVE_GENERATION",
                    )
                )
            self.assertEqual(93, self.process.wait(timeout=5))
            self.start(restart=True)
            status = self.request(dict(op="update.status", update_id=ident))
            self.assertEqual(expected, status["stage"])
            self.assertFalse(status["applied"])

    def test_invalid_json_and_unconfirmed_operator_never_mutate(self):
        self.start()
        raw = self.package()
        self.upload(raw)
        for payload in [
            b'{"op":"update.info","op":"update.apply"}\n',
            b'{"op":"update.apply","update_id":"'
            + b"a" * 32
            + b'","parked":false,"confirm":"INSTALL_NATIVE_GENERATION"}\n',
            b'{"op":"update.upload","bytes":999999999}\n',
            b'{"op":"update.info","path":"../bad"}\n',
        ]:
            with socket.create_connection(
                ("127.0.0.1", self.port), timeout=5
            ) as connection:
                connection.sendall(payload)
                with connection.makefile("rb") as stream:
                    self.assertEqual(
                        "invalid_request", json.loads(stream.readline())["error"]
                    )
        self.assertEqual("", self.store("status")["candidate"])


if __name__ == "__main__":
    unittest.main()
