"""Executes actual native POSIX store transactions in fresh private host roots.

All payloads are synthetic data, never executed. No QNX/device/partition access.
"""

import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
from native_generation import build, DOMAIN

BINARY = sys.argv.pop(1)
TARGETS = {"song-plus-dmi-2021": "1" * 64, "tang-plus-dmi-2021": "2" * 64}
NONCE = "a" * 32


class NativeStoreTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="obd-native-store-")
        self.home = Path(self.temporary.name).resolve()
        self.root = self.home / "openbyddisplay"
        self.key = Ed25519PrivateKey.from_private_bytes(bytes(range(32)))
        self.public = self.key.public_key().public_bytes(
            serialization.Encoding.Raw, serialization.PublicFormat.Raw
        )
        self.keyfile = self.home / "public.bin"
        self.keyfile.write_bytes(self.public)
        self.target = "song-plus-dmi-2021"
        self.init()

    def tearDown(self):
        for path in self.home.rglob("*"):
            if path.is_dir() and not path.is_symlink():
                path.chmod(0o700)
        self.temporary.cleanup()

    def args(self, *op, target=None):
        target = target or self.target
        return [
            BINARY,
            "--root",
            str(self.root),
            "--key",
            str(self.keyfile),
            "--key-sha256",
            hashlib.sha256(self.public).hexdigest(),
            "--target",
            target,
            "--baseline",
            TARGETS[target],
            *op,
        ]

    def run_store(self, *op, ok=True, crash=None, target=None):
        env = dict(os.environ)
        if crash:
            env["OBD_STORE_TEST_CRASH"] = crash
        result = subprocess.run(
            self.args(*op, target=target), capture_output=True, timeout=10, env=env
        )
        if crash:
            self.assertEqual(93, result.returncode, result.stderr.decode())
            return None
        self.assertEqual(
            0 if ok else 78, result.returncode, (op, result.stdout, result.stderr)
        )
        return json.loads(result.stdout) if result.stdout else None

    def init(self):
        self.run_store("init")

    def package(self, release="1.0.0", ident="b" * 32, target=None):
        target = target or self.target
        files = {
            "service.elf": (b"SYNTHETIC NOT AN EXECUTABLE " + release.encode(), 0o700),
            "profile.dat": (bytes(64), 0o600),
            "resource.bin": (b"synthetic resource", 0o600),
        }
        raw = build(files, target, TARGETS[target], release, self.key, ident)
        path = self.home / (ident + ".obg")
        path.write_bytes(raw)
        return path

    def confirmed(self, release="1.0.0", ident="b" * 32):
        self.run_store("stage", str(self.package(release, ident)))
        self.run_store("activate", ident, NONCE)
        selected = self.run_store("select")
        self.assertEqual(1, selected["selection"])
        self.run_store("confirm-test", ident, NONCE, "HOST_TEST_HEALTHY")
        return ident

    def test_signed_native_install_and_confirm_each_model(self):
        for target in TARGETS:
            with self.subTest(target=target):
                self.target = target
                self.root = self.home / ("openbyddisplay-" + target)
                self.init()
                ident = self.confirmed()
                status = self.run_store("status")
                self.assertEqual(ident, status["current"])
                self.assertEqual("", status["candidate"])
                self.assertFalse(status["production_flashable"])
                self.assertEqual(0, self.run_store("select")["selection"])

    def test_old_confirmed_generation_survives_failed_trial(self):
        old = self.confirmed()
        new = "c" * 32
        self.run_store("stage", str(self.package("1.0.1", new)))
        self.run_store("activate", new, NONCE)
        self.assertEqual(1, self.run_store("select")["selection"])
        self.assertEqual(old, self.run_store("select")["package_id"])
        status = self.run_store("status")
        self.assertEqual("", status["candidate"])
        self.run_store("activate", new, NONCE, ok=False)
        self.run_store("activate", old, NONCE, ok=False)

    def test_first_failed_trial_returns_oem_and_keeps_watermark(self):
        self.run_store("stage", str(self.package()))
        self.run_store("activate", "b" * 32, NONCE)
        self.run_store("select")
        self.assertEqual(2, self.run_store("select")["selection"])
        self.run_store("activate", "b" * 32, NONCE, ok=False)

    def test_real_ed25519_wrong_signatures_payload_and_targets(self):
        path = self.package()
        original = path.read_bytes()
        for at in (144, len(original) - 1):
            altered = bytearray(original)
            altered[at] ^= 1
            path.write_bytes(altered)
            self.run_store("stage", str(path), ok=False)
        path = self.package(target="tang-plus-dmi-2021")
        self.run_store("stage", str(path), ok=False)
        self.assertEqual([], list(self.root.glob("pending-*")))

    def test_validly_signed_malformed_rows_are_rejected(self):
        path = self.package()
        original = path.read_bytes()
        for kind in ("traversal", "offset", "reserved", "duplicate"):
            raw = bytearray(original)
            if kind == "traversal":
                raw[208:272] = b"../bad" + bytes(58)
            elif kind == "offset":
                struct.pack_into("<I", raw, 208 + 72, 4)
            elif kind == "reserved":
                raw[208 + 76] = 1
            else:
                raw[208 + 128 : 208 + 128 + 64] = raw[208 : 208 + 64]
            count = struct.unpack_from("<I", raw, 12)[0]
            table = raw[208 : 208 + count * 128]
            raw[112:144] = hashlib.sha256(table).digest()
            raw[144:208] = self.key.sign(DOMAIN + raw[:144] + table)
            path.write_bytes(raw)
            self.run_store("stage", str(path), ok=False)

    def test_generation_file_tamper_links_and_extra_files(self):
        path = self.package()
        self.run_store("stage", str(path))
        gen = self.root / ("gen-" + "b" * 32)
        service = gen / "service.elf"
        original = service.read_bytes()
        service.write_bytes(b"x" * len(original))
        self.run_store("activate", "b" * 32, NONCE, ok=False)
        service.write_bytes(original)
        gen.chmod(0o700)
        extra = gen / "extra"
        extra.write_bytes(b"unexpected")
        gen.chmod(0o500)
        self.run_store("activate", "b" * 32, NONCE, ok=False)
        gen.chmod(0o700)
        extra.unlink()
        service.unlink()
        service.symlink_to(path)
        gen.chmod(0o500)
        self.run_store("activate", "b" * 32, NONCE, ok=False)

    def test_missing_corrupt_state_and_foreign_identity_never_reset(self):
        state = self.root / "state.bin"
        raw = state.read_bytes()
        state.write_bytes(raw[:-1] + bytes([raw[-1] ^ 1]))
        self.run_store("status", ok=False)
        self.run_store("init", ok=False)
        state.unlink()
        self.run_store("status", ok=False)
        self.run_store("init", ok=False)
        state.write_bytes(raw)
        state.chmod(0o600)
        self.run_store("status", target="tang-plus-dmi-2021", ok=False)

    def test_confirm_requires_attempt_nonce_and_health(self):
        self.run_store("stage", str(self.package()))
        self.run_store("activate", "b" * 32, NONCE)
        self.run_store("confirm-test", "b" * 32, NONCE, "HOST_TEST_HEALTHY", ok=False)
        self.run_store("select")
        self.run_store(
            "confirm-test", "b" * 32, "c" * 32, "HOST_TEST_HEALTHY", ok=False
        )
        self.run_store("confirm-test", "b" * 32, NONCE, "UNHEALTHY", ok=False)
        self.assertTrue(self.run_store("status")["attempted"])

    def test_stage_process_crashes_preserve_current_and_retry_from_verified_input(self):
        phases = (
            "stage-directory-created",
            "stage-bundle-synced",
            "stage-file-synced",
            "stage-complete-synced",
            "generation-renamed",
            "generation-directory-synced",
        )
        for i, phase in enumerate(phases):
            with self.subTest(phase=phase):
                self.root = self.home / ("openbyddisplay-stage-" + str(i))
                self.init()
                old = self.confirmed()
                new = "c" * 32
                path = self.package("1.0.1", new)
                self.run_store("stage", str(path), crash=phase)
                self.assertEqual(old, self.run_store("status")["current"])
                self.run_store("stage", str(path))
                self.run_store("activate", new, NONCE)
                self.assertEqual(new, self.run_store("select")["package_id"])

    def test_state_commit_process_crashes_and_once_only_handoff(self):
        for i, phase in enumerate(
            ("state-file-synced", "state-renamed", "state-directory-synced")
        ):
            with self.subTest(phase=phase):
                self.root = self.home / ("openbyddisplay-state-" + str(i))
                self.init()
                self.run_store("stage", str(self.package()))
                self.run_store("activate", "b" * 32, NONCE)
                self.run_store("select", crash=phase)
                expected = 1 if phase == "state-file-synced" else 2
                self.assertEqual(expected, self.run_store("select")["selection"])

    def test_first_init_interruption_does_not_reinitialize_existing_root(self):
        self.root = self.home / "openbyddisplay-init-interrupt"
        self.run_store("init", crash="identity-file-synced")
        self.run_store("init", ok=False)
        self.run_store("status", ok=False)


if __name__ == "__main__":
    unittest.main()
