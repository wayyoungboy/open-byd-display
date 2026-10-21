"""Real host fork/exec/pipes and disk transactions; media evidence is an ABI double.

No QNX execution, vendor binary execution, USB operation or visual claim.
"""

import hashlib
import json
from pathlib import Path
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

STORE, SUPERVISOR, HEALTHY, UNHEALTHY = sys.argv[1:5]
del sys.argv[1:5]


class SupervisorTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="obd-native-supervisor-")
        self.home = Path(self.temp.name).resolve()
        self.key = Ed25519PrivateKey.from_private_bytes(bytes(range(32)))
        raw = self.key.public_key().public_bytes(
            serialization.Encoding.Raw, serialization.PublicFormat.Raw
        )
        self.key_path = self.home / "public.bin"
        self.key_path.write_bytes(raw)
        self.pin = hashlib.sha256(raw).hexdigest()
        self.target = "song-plus-dmi-2021"
        self.root = self.home / "store"
        self.processes = []
        self.store("init")

    def tearDown(self):
        for p in self.processes:
            if p.poll() is None:
                p.terminate()
                p.wait(timeout=6)
            p.stdout.close()
            p.stderr.close()
        for p in self.home.rglob("*"):
            if p.is_dir() and not p.is_symlink():
                p.chmod(0o700)
        self.temp.cleanup()

    def args(self, binary):
        return [
            binary,
            "--root",
            str(self.root),
            "--key",
            str(self.key_path),
            "--key-sha256",
            self.pin,
            "--target",
            self.target,
            "--baseline",
            "1" * 64,
        ]

    def store(self, *op):
        r = subprocess.run(self.args(STORE) + list(op), capture_output=True, timeout=8)
        self.assertEqual(0, r.returncode, (r.stdout, r.stderr))
        return json.loads(r.stdout) if r.stdout else None

    def stage(self, healthy=True, model=1, enabled=1, ident="b" * 32, version="1.0.0"):
        profile = bytearray(64)
        profile[:8] = b"OBDPRO1\n"
        profile[8:10] = bytes([model, enabled])
        profile[12:16] = bytes([127, 0, 0, 1])
        struct.pack_into(
            "<10H", profile, 16, 2, 1920, 720, 700, 180, 520, 360, 3, 30, 47792
        )
        package = build(
            {
                "service.elf": (
                    Path(HEALTHY if healthy else UNHEALTHY).read_bytes(),
                    0o700,
                ),
                "profile.dat": (bytes(profile), 0o600),
            },
            self.target,
            "1" * 64,
            version,
            self.key,
            ident,
        )
        p = self.home / (ident + ".obg")
        p.write_bytes(package)
        self.store("stage", str(p))
        self.store("activate", ident, "a" * 32)

    def start(self, watch=False):
        p = subprocess.Popen(
            self.args(SUPERVISOR) + (["--watch"] if watch else []),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        self.processes.append(p)
        return p

    def test_watcher_installs_updates_from_idle_then_replaces_and_rolls_back(self):
        for model, target in [(1, "song-plus-dmi-2021"), (2, "tang-plus-dmi-2021")]:
            self.target = target
            self.root = self.home / (target + "-watch")
            self.store("init")
            p = self.start(watch=True)
            time.sleep(0.15)
            self.assertIsNone(
                p.poll(), "watcher must remain available before first install"
            )
            self.stage(model=model)
            self.wait_state("current", "b" * 32)
            self.stage(model=model, ident="c" * 32, version="1.0.1")
            self.wait_state("current", "c" * 32)
            self.stage(healthy=False, model=model, ident="d" * 32, version="1.0.2")
            self.wait_state("candidate", "", timeout=8)
            self.assertEqual("c" * 32, self.store("status")["current"])
            self.assertIsNone(p.poll())
            p.terminate()
            self.assertEqual(0, p.wait(timeout=6))

    def wait_state(self, key, value, timeout=7):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            r = subprocess.run(
                self.args(STORE) + ["status"], capture_output=True, timeout=8
            )
            if r.returncode == 75:  # Short exclusive select/confirm transaction.
                time.sleep(0.05)
                continue
            self.assertEqual(0, r.returncode, r.stderr)
            state = json.loads(r.stdout)
            if state[key] == value:
                return state
            time.sleep(0.05)
        self.fail((key, value, state))

    def test_each_model_live_child_confirmed_store_remains_available_and_unique(self):
        for model, target in [(1, "song-plus-dmi-2021"), (2, "tang-plus-dmi-2021")]:
            with self.subTest(target=target):
                self.target = target
                self.root = self.home / target
                self.store("init")
                self.stage(model=model)
                p = self.start()
                self.wait_state("current", "b" * 32)
                self.assertIsNone(p.poll())
                duplicate = self.start()
                self.assertEqual(75, duplicate.wait(timeout=4))
                p.terminate()
                self.assertEqual(0, p.wait(timeout=6), p.stderr.read())
                self.assertEqual("b" * 32, self.store("status")["current"])

    def test_unhealthy_first_generation_falls_back_oem(self):
        self.stage(healthy=False)
        p = self.start()
        self.assertEqual(70, p.wait(timeout=8), p.stderr.read())
        s = self.store("status")
        self.assertEqual("", s["current"])
        self.assertEqual("", s["candidate"])

    def test_failed_trial_restarts_only_verified_old_generation(self):
        self.stage()
        p = self.start()
        self.wait_state("current", "b" * 32)
        p.terminate()
        p.wait(timeout=6)
        self.stage(healthy=False, ident="c" * 32, version="1.0.1")
        p = self.start()
        self.wait_state("candidate", "", timeout=8)
        self.assertIsNone(p.poll())
        self.assertEqual("b" * 32, self.store("status")["current"])
        p.terminate()
        p.wait(timeout=6)

    def test_wrong_model_profile_and_disabled_profile_never_exec(self):
        for model, enabled in [(2, 1), (1, 0)]:
            with self.subTest(model=model, enabled=enabled):
                self.root = self.home / f"profile-{model}-{enabled}"
                self.store("init")
                self.stage(model=model, enabled=enabled)
                p = self.start()
                self.assertEqual(78, p.wait(timeout=4))
                self.assertEqual("", self.store("status")["current"])
                self.assertEqual("", self.store("status")["candidate"])

    def test_parent_death_expires_child_lease_and_keeps_recovery_barrier(self):
        self.stage()
        p = self.start()
        self.wait_state("current", "b" * 32)
        p.kill()  # Only the process created by this test, never an imported PID.
        p.communicate(timeout=6)  # Child holds pipes until its lease/EOF exit.
        self.assertTrue((self.root / "supervisor.uncertain").is_file())
        replacement = self.start()
        self.assertEqual(78, replacement.wait(timeout=4))
        self.assertEqual("b" * 32, self.store("status")["current"])

    def test_boot_retries_brief_store_lock_without_reset_or_losing_trial(self):
        self.stage()
        with subprocess.Popen(
            self.args(STORE) + ["hold-lock-test"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        ) as held:
            self.assertIn(b"hold-lock-test", held.stdout.readline())
            busy = subprocess.run(
                self.args(STORE) + ["status"], capture_output=True, timeout=4
            )
            self.assertEqual(75, busy.returncode)
            p = self.start()
            time.sleep(0.1)
            held.terminate()
            held.wait(timeout=4)
        self.wait_state("current", "b" * 32)
        p.terminate()
        self.assertEqual(0, p.wait(timeout=6))

    def test_confirmation_busy_keeps_child_lease_and_health_fresh(self):
        self.stage()
        p = self.start()
        seen = self.wait_state("attempted", 1)
        self.assertEqual("", seen["current"])
        with subprocess.Popen(
            self.args(STORE) + ["hold-lock-test"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        ) as held:
            self.assertIn(b"hold-lock-test", held.stdout.readline())
            time.sleep(1.2)
            self.assertIsNone(p.poll())
            held.terminate()
            held.wait(timeout=4)
        self.wait_state("current", "b" * 32)
        p.terminate()
        self.assertEqual(0, p.wait(timeout=6))


if __name__ == "__main__":
    unittest.main()
