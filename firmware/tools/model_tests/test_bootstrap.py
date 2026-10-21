"""Synthetic boot TAR/ABI fixtures, no third-party files and no script execution."""

import copy
import gzip
import hashlib
import io
import json
from pathlib import Path
import struct
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives import serialization

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import bootstrap_firmware as boot
import model_firmware as model
from test_model_firmware import fixture

START = b'#!/bin/sh\n    # BEGIN FISSION_OPTIONAL_SERVICES_P1\n    # synthetic legacy hook\n    print "SLM"\n'
HMI = b"#!/bin/sh\nexec ./synthetic-hmi\n"
CONFIG = {
    "enabled": False,
    "display": 2,
    "physical_width": 1920,
    "physical_height": 720,
    "x": 700,
    "y": 180,
    "width": 520,
    "height": 360,
    "zorder": 3,
    "trial_seconds": 120,
    "address": "192.168.77.1",
}


def os_sv(extra=()):
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode="w", format=tarfile.USTAR_FORMAT) as archive:
        for name, data in [
            (boot.STARTUP, START),
            ("base/scripts/start-hmi.sh", HMI),
            ("base/other-data", b"keep exact bytes"),
            *extra,
        ]:
            m = tarfile.TarInfo(name)
            m.mode, m.uid, m.gid, m.mtime, m.size = 0o550, 12, 13, 100, len(data)
            archive.addfile(m, io.BytesIO(data))
        link = tarfile.TarInfo("base/preserved-symlink")
        link.type, link.linkname = tarfile.SYMTYPE, "other-data"
        archive.addfile(link)
    raw = output.getvalue()
    compressed = gzip.compress(raw, mtime=0)
    footer = bytearray(512)
    footer[:19] = b"Desay SV Automotive"
    footer[64:67] = b"os\0"
    footer[288:320] = hashlib.md5(compressed).hexdigest().encode()
    struct.pack_into("<I", footer, 320, len(raw))
    return compressed + footer


class BootstrapTests(unittest.TestCase):
    def setUp(self):
        self.stack = __import__("contextlib").ExitStack()
        self.stack.enter_context(patch.object(boot, "STARTUP_SHA", model.sha(START)))
        self.stack.enter_context(patch.object(boot, "START_HMI_SHA", model.sha(HMI)))
        self.addCleanup(self.stack.close)

    def test_startup_async_insertion_preserves_all_other_records_and_hmi(self):
        for target in ("song-plus-dmi-2021", "tang-plus-dmi-2021"):
            before = os_sv()
            changed, original, evidence = boot.patch_os(before, target)
            self.assertEqual(START, original)
            self.assertTrue(evidence["oem_hmi_start_preserved"])
            _, old_footer, old_records, _ = boot.os_contents(before)
            _, new_footer, new_records, _ = boot.os_contents(changed)
            self.assertEqual(old_footer[:288], new_footer[:288])
            self.assertEqual(old_footer[324:], new_footer[324:])
            for name in old_records.keys() - {boot.STARTUP}:
                self.assertEqual(old_records[name][1], new_records[name][1])
            m, r = new_records[boot.STARTUP]
            text = r[512 : 512 + m.size]
            self.assertEqual(START, text.replace(boot.bridge_script(target), b"", 1))
            self.assertIn(b"2>&1 &\n", text)
            subprocess.run(
                ["sh", "-n"], input=text, check=True, capture_output=True, timeout=5
            )
            with self.assertRaisesRegex(ValueError, "baseline_mismatch"):
                boot.patch_os(changed, target)

    def test_wrong_baseline_or_malformed_os_refused(self):
        with (
            patch.object(boot, "STARTUP_SHA", "0" * 64),
            self.assertRaisesRegex(ValueError, "baseline_mismatch"),
        ):
            boot.patch_os(os_sv(), "song-plus-dmi-2021")
        broken = bytearray(os_sv())
        broken[-512 + 288] ^= 1
        with self.assertRaisesRegex(ValueError, "footer"):
            boot.patch_os(bytes(broken), "song-plus-dmi-2021")
        with self.assertRaisesRegex(ValueError, "os_tar_record"):
            boot.patch_os(os_sv([(boot.STARTUP, b"duplicate")]), "song-plus-dmi-2021")

    def test_profile_explicit_and_model_specific(self):
        song = boot.profile_bytes("song-plus-dmi-2021", CONFIG)
        tang = boot.profile_bytes("tang-plus-dmi-2021", CONFIG)
        self.assertEqual((1, 2), (song[8], tang[8]))
        self.assertEqual(song[:8] + song[9:], tang[:8] + tang[9:])
        for update in (
            {"zorder": 10},
            {"x": 1900},
            {"display": True},
            {"address": "0.0.0.0"},
            {"enabled": 1},
        ):
            bad = dict(CONFIG, **update)
            with self.subTest(update=update), self.assertRaises(ValueError):
                boot.profile_bytes("song-plus-dmi-2021", bad)

    def complete_fixture(self):
        original, profile, artifacts = fixture()
        outer = model.outer_files(original)
        outer["os.sv"] = os_sv()
        original = model.zip_bytes(outer)
        profile = copy.deepcopy(profile)
        profile["original_zip_sha256"] = model.sha(original)
        profile["outer_files"] = {
            n: {"bytes": len(d), "sha256": model.sha(d)} for n, d in outer.items()
        }
        artifacts |= {
            "obd-qnx66-install-song-plus-dmi-2021.elf": b"synthetic installer",
            "obd-qnx66-supervisor-song-plus-dmi-2021.elf": b"synthetic supervisor",
            "obd-qnx66-updater-song-plus-dmi-2021.elf": b"synthetic updater",
        }
        return original, profile, artifacts

    def test_complete_image_only_changes_app_os_and_contains_native_generation(self):
        original, profile, artifacts = self.complete_fixture()
        outer = model.outer_files(original)
        key = Ed25519PrivateKey.generate()
        image, report, package, _ = boot.compose(
            original, artifacts, "song-plus-dmi-2021", profile, CONFIG, key, "a" * 40
        )
        record = json.loads(report)
        self.assertFalse(record["production_flashable"])
        self.assertTrue(record["native_supervisor_implemented"])
        changed = model.outer_files(image)
        for name in outer.keys() - {"app.sv", "os.sv"}:
            self.assertEqual(outer[name], changed[name])
        raw, _, _, _ = model.app_contents(changed["app.sv"])
        with tarfile.open(fileobj=io.BytesIO(raw)) as archive:
            for name in ("boot.sh", "prepare-store.sh"):
                data = archive.extractfile("./" + model.NAMESPACE + "/" + name).read()
                subprocess.run(
                    ["sh", "-n"], input=data, check=True, capture_output=True, timeout=5
                )
                if name == "boot.sh":
                    self.assertIn(b"obd-qnx66-updater-song-plus-dmi-2021.elf", data)
                    self.assertIn(b"--port 47793", data)
                    self.assertIn(b"--watch", data)
            self.assertEqual(
                package,
                archive.extractfile(
                    "./" + model.NAMESPACE + "/initial-generation.obg"
                ).read(),
            )
        self.assertEqual(hashlib.sha256(image).hexdigest(), record["image_sha256"])

    def test_signature_verification_also_checks_preservation_contract(self):
        original, profile, artifacts = self.complete_fixture()
        key = Ed25519PrivateKey.generate()
        image, report, package, config = boot.compose(
            original, artifacts, "song-plus-dmi-2021", profile, CONFIG, key, "a" * 40
        )
        public = key.public_key().public_bytes(
            serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo
        )
        with (
            tempfile.TemporaryDirectory() as temporary,
            patch.object(boot, "profile_for", return_value=profile),
        ):
            root = Path(temporary)
            data = {
                "baseline.zip": original,
                "boot-integrated-bench.zip": image,
                "image-report.json": report,
                "image-report.sig": key.sign(boot.DOMAIN + report),
                "image-public.pem": public,
                "initial-generation.obg": package,
                "profile.json": config,
            }
            for name, value in data.items():
                (root / name).write_bytes(value)
            result = boot.verify(
                root,
                root / "baseline.zip",
                "song-plus-dmi-2021",
                root / "image-public.pem",
                model.sha(public),
            )
            self.assertTrue(result["signature_verified"])
            with self.assertRaisesRegex(ValueError, "trusted_public_pin"):
                boot.verify(
                    root,
                    root / "baseline.zip",
                    "song-plus-dmi-2021",
                    root / "image-public.pem",
                    "0" * 64,
                )
            outer = model.outer_files(image)
            bad = bytearray(outer["app.sv"])
            bad[-512 + 200] ^= 1
            outer["app.sv"] = bytes(bad)
            changed = model.zip_bytes(outer)
            record = json.loads(report)
            record.update(image_sha256=model.sha(changed), image_bytes=len(changed))
            signed = model.encoded(record)
            (root / "boot-integrated-bench.zip").write_bytes(changed)
            (root / "image-report.json").write_bytes(signed)
            (root / "image-report.sig").write_bytes(key.sign(boot.DOMAIN + signed))
            with self.assertRaisesRegex(ValueError, "unrelated_app_footer_changed"):
                boot.verify(
                    root,
                    root / "baseline.zip",
                    "song-plus-dmi-2021",
                    root / "image-public.pem",
                    model.sha(public),
                )

    def test_source_changes_during_build_publish_nothing(self):
        original, profile, artifacts = self.complete_fixture()
        key = Ed25519PrivateKey.generate()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "baseline.zip").write_bytes(original)
            (root / "config.json").write_text(json.dumps(CONFIG))
            (root / "key.pem").write_bytes(
                key.private_bytes(
                    serialization.Encoding.PEM,
                    serialization.PrivateFormat.PKCS8,
                    serialization.NoEncryption(),
                )
            )
            with (
                patch.object(boot, "clean_source", side_effect=["a" * 40, "b" * 40]),
                patch.object(boot, "profile_for", return_value=profile),
                patch.object(boot, "platform_files", return_value=artifacts),
            ):
                with self.assertRaisesRegex(ValueError, "source_changed_during_build"):
                    boot.build(
                        root / "output",
                        root / "baseline.zip",
                        root / "native",
                        "song-plus-dmi-2021",
                        root / "key.pem",
                        root / "config.json",
                    )
            self.assertFalse((root / "output").exists())


if __name__ == "__main__":
    unittest.main()
