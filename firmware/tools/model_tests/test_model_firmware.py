"""Synthetic inputs only: no vendor archives, target execution or install scripts."""
import copy
import gzip
import hashlib
import io
import json
from pathlib import Path
import struct
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import model_firmware as model


TARGET = "song-plus-dmi-2021"
SOURCE = "a" * 40


def sv(raw, footer=None):
    compressed = gzip.compress(raw, mtime=0)
    footer = bytearray(footer or bytes(512))
    footer[:19] = b"Desay SV Automotive"
    footer[64:68] = b"app\0"
    footer[80:85] = b"kept!"
    footer[288:320] = hashlib.md5(compressed).hexdigest().encode()
    struct.pack_into("<I", footer, 320, len(raw))
    return compressed + footer


def raw_tar(extra=(), hmi=b"independent synthetic hmi", format_=tarfile.USTAR_FORMAT):
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode="w", format=format_) as archive:
        for name in ("base", "base/app", "base/app/rc_hmi"):
            info = tarfile.TarInfo("./" + name)
            info.type = tarfile.DIRTYPE
            info.mode, info.uid, info.gid, info.mtime = 0o755, 1000, 1000, 100
            archive.addfile(info)
        info = tarfile.TarInfo("./base/app/rc_hmi/rc_hmi.exe")
        info.mode, info.uid, info.gid, info.mtime, info.size = 0o500, 0, 0, 101, len(hmi)
        archive.addfile(info, io.BytesIO(hmi))
        for info, data in extra:
            archive.addfile(info, io.BytesIO(data))
    return output.getvalue()


def fixture():
    raw = raw_tar()
    outer = {name: b"unchanged-" + name.encode() for name in model.OUTER}
    outer["app.sv"] = sv(raw)
    original = model.zip_bytes(outer)
    profile = {"original_zip_sha256": model.sha(original), "base_version": "1.0.0",
               "outer_files": {n: {"bytes": len(d), "sha256": model.sha(d)} for n, d in outer.items()},
               "runtime_fingerprints": {"/base/app/rc_hmi/rc_hmi.exe": {
                   "bytes": 25, "sha256": model.sha(b"independent synthetic hmi")},
                   "/proc/boot/libc.so.3": {"bytes": 10, "sha256": "b" * 64}}}
    # Keep the fixture size explicit but derive it to avoid test spelling mistakes.
    profile["runtime_fingerprints"]["/base/app/rc_hmi/rc_hmi.exe"]["bytes"] = len(b"independent synthetic hmi")
    artifacts = {n: b"own synthetic artifact" for n in model.file_names(TARGET) - {"profile.json", "manual-start.sh"}}
    artifacts["build-report.json"] = json.dumps({"runtime_libc_sha256": "b" * 64}).encode()
    return original, profile, artifacts


class ModelFirmwareTests(unittest.TestCase):
    def test_preserves_raw_oem_records_and_five_outer_files(self):
        original, profile, artifacts = fixture()
        candidate, report = model.compose(original, TARGET, profile, artifacts, SOURCE)
        record = json.loads(report)
        self.assertFalse(record["production_flashable"])
        self.assertTrue(record["user_reported_baseline_tested"])
        self.assertFalse(record["candidate_vehicle_verified"])
        self.assertFalse(record["autostart_enabled"])
        outer, raw, footer, rows, end = model.read_baseline(original, profile)
        changed = model.outer_files(candidate)
        new, new_footer, _, _ = model.app_contents(changed["app.sv"])
        self.assertEqual(raw[:end], new[:end])
        self.assertEqual(footer[:288], new_footer[:288])
        self.assertEqual(footer[324:], new_footer[324:])
        for n in model.OUTER - {"app.sv"}:
            self.assertEqual(outer[n], changed[n])
        # Ordinary readers MUST see the additions, before the old TAR zero tail.
        with tarfile.open(fileobj=io.BytesIO(new)) as t:
            own = [m for m in t if m.name.startswith("./" + model.NAMESPACE)]
        self.assertEqual(8, len(own))
        self.assertEqual(len(rows), record["evidence"]["original_members_preserved"])
        self.assertEqual((candidate, report), model.compose(original, TARGET, profile, artifacts, SOURCE))

    def test_wrong_model_or_changed_baseline_rejected(self):
        original, profile, artifacts = fixture()
        wrong = copy.deepcopy(profile)
        wrong["original_zip_sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "wrong_model"):
            model.compose(original, TARGET, wrong, artifacts, SOURCE)
        wrong = copy.deepcopy(profile)
        wrong["runtime_fingerprints"]["/base/app/rc_hmi/rc_hmi.exe"]["sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "runtime_fingerprint"):
            model.compose(original, TARGET, wrong, artifacts, SOURCE)
        artifacts["build-report.json"] = b'{"runtime_libc_sha256":"wrong"}'
        with self.assertRaisesRegex(ValueError, "native_runtime"):
            model.compose(original, TARGET, profile, artifacts, SOURCE)

    def test_changed_oem_member_or_unrelated_footer_refused(self):
        original, profile, artifacts = fixture()
        candidate, report = model.compose(original, TARGET, profile, artifacts, SOURCE)
        expected = json.loads(report)["self_files"]
        outer = model.outer_files(candidate)
        for name in ("os.sv", "mcu.sv", "RcAppVersion", "kzbVersion", "version_svp"):
            altered = dict(outer)
            altered[name] += b"changed"
            with self.assertRaisesRegex(ValueError, "outer_member"):
                model.check_candidate(original, model.zip_bytes(altered), TARGET, profile, expected)
        changed = bytearray(outer["app.sv"])
        changed[-512+100] ^= 1
        outer["app.sv"] = bytes(changed)
        with self.assertRaisesRegex(ValueError, "footer_changed"):
            model.check_candidate(original, model.zip_bytes(outer), TARGET, profile, expected)

    def test_hidden_tar_tail_links_duplicates_and_traversal_refused(self):
        for name, kind in (("./base/app/rc_hmi/rc_hmi.exe", tarfile.REGTYPE),
                           ("./base/app/link", tarfile.SYMTYPE),
                           ("./base/app/../escape", tarfile.REGTYPE),
                           ("/absolute", tarfile.REGTYPE),
                           ("./base/app/missing/file", tarfile.REGTYPE)):
            info = tarfile.TarInfo(name)
            info.type = kind
            info.linkname = "rc_hmi" if kind == tarfile.SYMTYPE else ""
            with self.subTest(name=name), self.assertRaises(ValueError):
                model.app_contents(sv(raw_tar([(info, b"")])))
        raw = raw_tar()
        hidden = raw + raw[:512]
        with self.assertRaisesRegex(ValueError, "hidden_data"):
            model.app_contents(sv(hidden))

    def test_sv_bad_digest_size_gzip_crc_and_concatenation_refused(self):
        original, _, _ = fixture()
        data = model.outer_files(original)["app.sv"]
        for offset in (0, len(data)-512+288, len(data)-512+320):
            broken = bytearray(data)
            broken[offset] ^= 1
            with self.subTest(offset=offset), self.assertRaises((ValueError, model.zlib.error)):
                model.app_contents(bytes(broken))
        footer = bytearray(data[-512:])
        compressed = data[:-512] + gzip.compress(b"extra")
        footer[288:320] = hashlib.md5(compressed).hexdigest().encode()
        with self.assertRaisesRegex(ValueError, "tail"):
            model.app_contents(compressed + footer)
        with patch.object(model, "MAX_TAR", 512), self.assertRaisesRegex(ValueError, "limit"):
            model.app_contents(data)

    def test_unsupported_tar_extension_headers_are_not_silently_merged(self):
        info = tarfile.TarInfo("./base/app/" + "a" * 120)
        with self.assertRaisesRegex(ValueError, "record_type_or_gap"):
            model.app_contents(sv(raw_tar([(info, b"")], format_=tarfile.GNU_FORMAT)))
        info = tarfile.TarInfo("./base/app/plain")
        info.pax_headers = {"comment": "synthetic extension"}
        with self.assertRaisesRegex(ValueError, "record_type_or_gap"):
            model.app_contents(sv(raw_tar([(info, b"")], format_=tarfile.PAX_FORMAT)))

    def test_extra_native_or_tar_member_not_allowed(self):
        original, profile, artifacts = fixture()
        artifacts["libc.so.3"] = b"must never copy vendor runtime"
        with self.assertRaisesRegex(ValueError, "native_file_allowlist"):
            model.compose(original, TARGET, profile, artifacts, SOURCE)
        del artifacts["libc.so.3"]
        candidate, report = model.compose(original, TARGET, profile, artifacts, SOURCE)
        expected = json.loads(report)["self_files"]
        del expected["profile.json"]
        with self.assertRaisesRegex(ValueError, "self_file_allowlist"):
            model.check_candidate(original, candidate, TARGET, profile, expected)

    def test_zip_duplicate_and_expansion_bound(self):
        original, _, _ = fixture()
        with patch.object(model, "MAX_ZIP", 1), self.assertRaisesRegex(ValueError, "zip_size"):
            model.outer_files(original)
        out = io.BytesIO()
        with zipfile.ZipFile(out, "w") as z:
            for name in model.OUTER:
                z.writestr(name, b"data")
            import warnings
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                z.writestr("app.sv", b"duplicate")
        with self.assertRaisesRegex(ValueError, "allowlist"):
            model.outer_files(out.getvalue())

    def test_detached_signature_anchor_and_candidate_tampering(self):
        original, profile, artifacts = fixture()
        candidate, report = model.compose(original, TARGET, profile, artifacts, SOURCE)
        key = Ed25519PrivateKey.generate()
        public = key.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo)
        with tempfile.TemporaryDirectory() as tmp, patch.object(model, "profile_for", return_value=profile):
            root = Path(tmp)
            (root / "original.zip").write_bytes(original)
            (root / "public.pem").write_bytes(public)
            (root / "candidate-report.json").write_bytes(report)
            (root / "candidate-report.sig").write_bytes(key.sign(model.DOMAIN + report))
            (root / "firmware-candidate.zip").write_bytes(candidate)
            args = (root, root / "original.zip", TARGET, root / "public.pem", model.sha(public))
            self.assertTrue(model.verify(*args)["signature_verified"])
            with self.assertRaisesRegex(ValueError, "key_fingerprint"):
                model.verify(*args[:-1], "0" * 64)
            (root / "candidate-report.json").write_bytes(report + b" ")
            with self.assertRaises(InvalidSignature):
                model.verify(*args)
            (root / "candidate-report.json").write_bytes(report)
            (root / "firmware-candidate.zip").write_bytes(candidate + b"bad")
            with self.assertRaisesRegex(ValueError, "signature_digest"):
                model.verify(*args)

    def test_manual_entry_only_passes_quoted_arguments_and_no_installer(self):
        _, profile, artifacts = fixture()
        files = model.service_files(artifacts, TARGET, profile, SOURCE)
        self.assertIn(b'--baseline-serve "$@"', files["manual-start.sh"])
        metadata = json.loads(files["profile.json"])
        self.assertFalse(metadata["autostart_enabled"])
        self.assertEqual(47792, metadata["service_port"])
        self.assertNotIn("install.sh", files)


if __name__ == "__main__":
    unittest.main()
