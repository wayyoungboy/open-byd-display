import io
from pathlib import Path
import tempfile
import unittest

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

from openbyddisplay.incremental import IncrementalLab, build_increment, PLAN
from openbyddisplay.packages import verify
from openbyddisplay.service import Laboratory


class PowerCut(BaseException):
    pass


class IncrementalTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "source"
        self.source.mkdir()
        (self.source / "receiver").write_bytes(b"independent native service v1")
        (self.source / "receiver").chmod(0o700)
        (self.source / "config").write_text("profile1")
        self.key = Ed25519PrivateKey.generate()
        self.target = "song-plus-dmi-2021"
        self.lab = self.restart()

    def restart(self, fault=lambda point: None):
        return IncrementalLab(
            self.root / "state", self.key.public_key(), self.target, fault=fault
        )

    def package(self, release="0.1.0", base=None, target=None):
        return build_increment(
            self.source, self.key, target or self.target, "host-lab", release, base
        )

    def confirmed(self):
        receipt = self.lab.stage(self.package())
        pending = self.lab.activate(receipt["package_id"])["pending"]
        self.lab.boot()
        self.lab.confirm(receipt["package_id"], pending["nonce"])
        return receipt

    def test_delta_copies_unchanged_files_removes_old_files_and_keeps_modes(self):
        base = self.confirmed()
        (self.source / "config").unlink()
        (self.source / "receiver").write_bytes(b"independent native service v2")
        (self.source / "new-data").write_text("new")
        raw = self.package("0.2.0", base)
        _, payload = verify(raw, self.key.public_key(), self.target, "host-lab")
        self.assertIn(PLAN, payload)
        self.assertNotIn("services/config", payload)
        receipt = self.lab.stage(raw)
        directory = self.lab.generation(receipt["package_id"])[1]
        self.assertFalse((directory / "config").exists())
        self.assertEqual((directory / "receiver").stat().st_mode & 0o777, 0o700)
        self.assertEqual(self.lab.state()["current"], base["package_id"])
        self.assertEqual(
            (self.lab.generation(base["package_id"])[1] / "config").read_text(),
            "profile1",
        )

    def test_unchanged_bytes_are_omitted_from_delta_but_materialized(self):
        base = self.confirmed()
        (self.source / "config").write_text("profile2")
        raw = self.package("0.2.0", base)
        _, payload = verify(raw, self.key.public_key(), self.target, "host-lab")
        self.assertNotIn("services/receiver", payload)
        receipt = self.lab.stage(raw)
        directory = self.lab.generation(receipt["package_id"])[1]
        self.assertEqual(
            (directory / "receiver").read_bytes(),
            (self.source / "receiver").read_bytes(),
        )

    def test_unconfirmed_second_boot_rolls_back_and_keeps_version_watermark(self):
        base = self.confirmed()
        candidate = self.lab.stage(self.package("0.2.0", base))
        self.lab.activate(candidate["package_id"])
        first = self.restart().boot()
        self.assertEqual(first["state"]["current"], candidate["package_id"])
        second = self.restart().boot()
        self.assertEqual(second["state"]["current"], base["package_id"])
        self.assertIsNone(second["state"]["pending"])
        self.assertEqual(second["state"]["highest_version"], "0.2.0")
        with self.assertRaisesRegex(ValueError, "version_not_newer"):
            self.lab.stage(self.package("0.2.0", base))

    def test_first_failed_trial_returns_to_original_system_fallback(self):
        candidate = self.lab.stage(self.package())
        self.lab.activate(candidate["package_id"])
        self.lab.boot()
        result = self.restart().boot()
        self.assertIsNone(result["service_directory"])
        self.assertIsNone(result["state"]["current"])

    def test_only_matching_attempted_trial_can_be_confirmed(self):
        receipt = self.lab.stage(self.package())
        pending = self.lab.activate(receipt["package_id"])["pending"]
        with self.assertRaises(ValueError):
            self.lab.confirm(receipt["package_id"], pending["nonce"])
        self.lab.boot()
        with self.assertRaises(ValueError):
            self.lab.confirm(receipt["package_id"], "0" * 32)
        self.lab.confirm(receipt["package_id"], pending["nonce"])
        self.assertEqual(
            self.restart().boot()["state"]["confirmed"], receipt["package_id"]
        )

    def test_wrong_signature_target_and_base_are_rejected(self):
        with self.assertRaises(InvalidSignature):
            bad = bytearray(self.package())
            bad[24] ^= 1
            self.lab.stage(bytes(bad))
        with self.assertRaises(ValueError):
            self.lab.stage(self.package(target="tang-plus-dmi-2021"))
        self.confirmed()
        with self.assertRaisesRegex(ValueError, "base_mismatch"):
            self.lab.stage(self.package("0.2.0"))

    def test_existing_missing_or_corrupt_state_is_not_reinitialized(self):
        base = self.confirmed()
        state = self.lab.root / "state.json"
        original = state.read_bytes()
        state.unlink()
        with self.assertRaisesRegex(ValueError, "existing_state_missing"):
            self.restart()
        self.assertFalse(state.exists())
        state.write_bytes(b"broken")
        with self.assertRaises(ValueError):
            self.restart()
        state.write_bytes(original)
        self.assertEqual(self.restart().state()["confirmed"], base["package_id"])

    def test_generation_corruption_and_symlinks_cannot_be_activated(self):
        receipt = self.lab.stage(self.package())
        directory = self.lab.generation(receipt["package_id"])[1]
        (directory / "receiver").write_bytes(b"corrupted")
        with self.assertRaises(ValueError):
            self.lab.activate(receipt["package_id"])
        (directory / "receiver").unlink()
        (directory / "receiver").symlink_to(self.source / "receiver")
        with self.assertRaises(ValueError):
            self.lab.activate(receipt["package_id"])
        self.assertIsNone(self.lab.state()["current"])

    def test_power_cut_before_or_after_generation_publish_never_switches_current(self):
        base = self.confirmed()
        for point in ("stage_file", "stage_synced", "stage_published"):

            def cut(actual):
                if actual == point:
                    raise PowerCut()

            lab = self.restart(cut)
            with self.assertRaises(PowerCut):
                lab.stage(self.package("0.2.0", base))
            self.assertEqual(
                self.restart().boot()["state"]["current"], base["package_id"]
            )

    def test_power_cut_after_trial_attempt_requires_rollback(self):
        base = self.confirmed()
        receipt = self.lab.stage(self.package("0.2.0", base))
        self.lab.activate(receipt["package_id"])

        def cut(point):
            if point == "boot_marked":
                raise PowerCut()

        with self.assertRaises(PowerCut):
            self.restart(cut).boot()
        self.assertEqual(self.restart().boot()["state"]["current"], base["package_id"])

    def test_full_bundle_receiver_refuses_incremental_payload(self):
        raw = self.package()
        lab = Laboratory(self.root / "full-lab", self.key.public_key(), self.target)
        with self.assertRaisesRegex(
            ValueError, "incremental_requires_transaction_manager"
        ):
            lab.receive(
                {"update_id": "a" * 32, "kind": "components", "bytes": len(raw)},
                io.BytesIO(raw),
                lambda value: None,
            )
        self.assertEqual(lab.current(), {})


if __name__ == "__main__":
    unittest.main()
