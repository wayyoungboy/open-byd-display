import hashlib
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import make_preflight_kit as kit


class KitTests(unittest.TestCase):
    def test_apk_identity_signer_and_frozen_input(self):
        certificate = "a" * 64
        saved = []

        def runner(args):
            # The inspected temporary file must contain the exact frozen bytes.
            self.assertEqual(b"frozen apk", Path(args[-1]).read_bytes())
            saved.append(args)
            if "badging" in args:
                return "package: name='org.openbyddisplay.usbtest' versionCode='5' versionName='0.1.4'"
            return "Signer #1 certificate SHA-256 digest: " + certificate

        metadata = kit.apk_metadata(b"frozen apk", Path("sdk"), certificate, runner)
        self.assertEqual("org.openbyddisplay.usbtest", metadata["package"])
        self.assertEqual(2, len(saved))
        with self.assertRaisesRegex(ValueError, "signer"):
            kit.apk_metadata(b"frozen apk", Path("sdk"), "b" * 64, runner)
        with self.assertRaisesRegex(ValueError, "coexisting"):
            kit.apk_metadata(b"apk", Path("sdk"), certificate,
                             lambda args: "package: name='org.openbyddisplay' versionCode='5' versionName='0.1.4'")

    def fixture(self, repo, build, target):
        sources = {"native/core/test.c": b"example", "tools/qnx66_build.py": b"build", "tools/qnx66_elf.py": b"elf", "baselines.json": b"profiles"}
        for name, data in sources.items():
            path = repo / "firmware" / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        header = bytearray(52)
        header[:6] = b"\x7fELF\x01\x01"
        struct.pack_into("<H", header, 18, 40)
        names = {f"obd-qnx66-{kind}-{target}.elf" for kind in ("probe", "inspect", "player")}
        names.add("libopenbyddisplay-video.so.0")
        rows = {}
        for name in names:
            (build / name).write_bytes(header)
            rows[name] = {"bytes": len(header), "sha256": hashlib.sha256(header).hexdigest(), "needed": ["libc.so.3"]}
        report = {"target": target, "artifacts": rows, "firmware_flashable": False, "qnx_execution_verified": False,
                  "hardware_verified": False, "source_files_sha256": {n: hashlib.sha256(b).hexdigest() for n, b in sources.items()}}
        (build / "build-report.json").write_text(json.dumps(report))
        return report

    def test_only_explicit_native_outputs_are_selected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            build = root / "build"
            build.mkdir()
            target = kit.TARGETS[0]
            self.fixture(root, build, target)
            forbidden = build / "link-inputs-not-for-deployment"
            forbidden.mkdir()
            (forbidden / "libc.so.3").write_bytes(b"must not be distributed")
            with patch.object(kit, "REPO", root):
                files = kit.build_files(build, target)
            self.assertEqual(5, len(files))
            self.assertNotIn("libc.so.3", files)
            self.assertFalse(any("link-inputs" in str(p) for p in files.values()))
            frozen = files[next(name for name in files if name.endswith(".elf"))]
            for path in build.glob("*.elf"):
                path.write_bytes(b"replaced after validation")
            self.assertTrue(frozen.startswith(b"\x7fELF"))

    def test_wrong_target_source_digest_or_runtime_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            build = root / "build"
            build.mkdir()
            target = kit.TARGETS[0]
            report = self.fixture(root, build, target)
            with patch.object(kit, "REPO", root):
                with self.assertRaisesRegex(ValueError, "target"):
                    kit.build_files(build, kit.TARGETS[1])
                (root / "firmware/native/core/test.c").write_bytes(b"changed")
                with self.assertRaisesRegex(ValueError, "source_changed"):
                    kit.build_files(build, target)
                self.fixture(root, build, target)
                name = next(iter(report["artifacts"]))
                report["artifacts"][name]["needed"].append("unexpected.so")
                (build / "build-report.json").write_text(json.dumps(report))
                with self.assertRaisesRegex(ValueError, "artifact_mismatch"):
                    kit.build_files(build, target)

    def test_sample_framing_has_checked_lengths_crc_and_eos(self):
        raw = b"".join(b"\0\0\0\1\x09\xf0\0\0\0\1\x65\x80" for _ in range(10))
        stream = kit.framed_sample(raw)
        self.assertEqual(b"BVL1", stream[:4])
        at = 39
        import zlib
        for index in range(10):
            magic, length, sequence, pts, key, crc = struct.unpack_from(">4s5I", stream, at)
            at += 24
            self.assertEqual((b"LHF2", index, index * 200, 1), (magic, sequence, pts, key))
            self.assertEqual(crc, zlib.crc32(stream[at:at+length]))
            at += length
        self.assertEqual(struct.pack(">4s5I", b"LHF2", 0, 0xffffffff, 0, 0, 0), stream[at:])
        with self.assertRaises(ValueError):
            kit.framed_sample(raw[:-14])

    def test_reference_must_contain_ten_decoded_frames(self):
        row = "0, 0, 0, 1, 4608, " + "a" * 32 + "\n"
        self.assertEqual(["a" * 32] * 10, kit.frame_hashes("#header\n" + row * 10))
        for count in (0, 9, 11):
            with self.assertRaisesRegex(ValueError, "ten_decoded_frames"):
                kit.frame_hashes(row * count)


if __name__ == "__main__":
    unittest.main()
