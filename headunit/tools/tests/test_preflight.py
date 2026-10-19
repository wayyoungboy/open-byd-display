import hashlib
import json
import shlex
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from preflight import APK_PATH, REQUIRED_FILES, ReadOnlyAdb, TARGETS, collect, redact, run_bounded, verify_kit, write_report

PROGRAM = f"firmware-test/{TARGETS[0]}/obd-qnx66-probe-{TARGETS[0]}.elf"


class PreflightTest(unittest.TestCase):
    def test_collection_is_fixed_read_only_and_retains_unknowns(self):
        calls = []

        def runner(args):
            calls.append(args)
            if args[-1] == "ro.build.version.sdk":
                return "29"
            if args[-3:] == ["pm", "path", "org.openbyddisplay.usbtest"]:
                return "package:/data/app/example/base.apk"
            if "media.codec" in args:
                raise RuntimeError("command_timeout")
            return "value FAKE123\nVIN=private\ntoken=private"

        adb = ReadOnlyAdb("adb", "FAKE123", runner)
        report = collect(adb, TARGETS[0], "org.openbyddisplay.usbtest")
        self.assertTrue(report["package_observed"])
        self.assertFalse(report["firmware_flashable"])
        self.assertFalse(report["target_hardware_verified"])
        self.assertEqual("unknown", report["instrument_qnx_entry"])
        self.assertEqual("unavailable", report["observations"]["codec"]["status"])
        text = json.dumps(report)
        self.assertNotIn("FAKE123", text)
        self.assertNotIn("private", text)
        for call in calls:
            self.assertEqual(["adb", "-s", "FAKE123", "shell"], call[:4])
            self.assertFalse(set(call[4:]) & {"install", "reboot", "push", "tcpip", "set", "su", "start"})
        queries = [call[-1] for call in calls if "content query" in call[-1]]
        self.assertEqual(2, len(queries))
        self.assertTrue(all("--where" in x and "delete" not in x and "insert" not in x for x in queries))
        for query, key in zip(queries, ("support_change_system_uimode", "sys_screen_mode")):
            parts = shlex.split(query)
            self.assertEqual("value", parts[parts.index("--projection") + 1])
            self.assertEqual(f"key = '{key}'", parts[parts.index("--where") + 1])

    def test_device_label_is_not_android_proof(self):
        adb = ReadOnlyAdb("adb", "emulator-5554", lambda args: "NuttX")
        with self.assertRaisesRegex(ValueError, "not_supported_android"):
            collect(adb, TARGETS[0], "org.openbyddisplay.usbtest")

    def test_runner_bounds_output_and_time_and_exit(self):
        self.assertEqual("ok", run_bounded([sys.executable, "-c", "print('ok')"]))
        with self.assertRaisesRegex(RuntimeError, "output_limit"):
            run_bounded([sys.executable, "-c", "print('x'*10000)"], limit=100)
        with self.assertRaisesRegex(RuntimeError, "timeout"):
            run_bounded([sys.executable, "-c", "import time;time.sleep(5)"], timeout=0.1)
        with self.assertRaisesRegex(RuntimeError, "exit_7"):
            run_bounded([sys.executable, "-c", "raise SystemExit(7)"])

    def fixture(self, root):
        rows = []
        digest = hashlib.sha256(b"example only").hexdigest()
        for name in sorted(REQUIRED_FILES):
            path = root / name
            path.parent.mkdir(exist_ok=True, parents=True)
            path.write_bytes(b"example only")
            rows.append({"path": name, "bytes": 12, "sha256": digest})
        manifest = {"format": 1, "targets": list(TARGETS), "files": rows, "source_commit": "a" * 40,
                    "firmware_flashable": False, "vehicle_verified": False, "apk_sha256": digest,
                    "apk": {"package": "org.openbyddisplay.usbtest", "sha256": digest, "signer_sha256": "c" * 64,
                            "version": "0.1.4", "version_code": 5}}
        self.save(root, manifest)
        return manifest

    @staticmethod
    def save(root, manifest):
        (root / "preflight-manifest.json").write_text(json.dumps(manifest), encoding="utf-8")

    def test_verified_bundle_and_no_report_overwrite(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.fixture(root)
            self.assertTrue(verify_kit(root)["ok"])
            target = root / "private.json"
            write_report(target, {"local": True})
            with self.assertRaises(FileExistsError):
                write_report(target, {"local": False})
            self.assertTrue(json.loads(target.read_text())["local"])
            with self.assertRaisesRegex(ValueError, "unlisted"):
                verify_kit(root)

    def test_tamper_missing_file_and_forged_qualification(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            manifest = self.fixture(root)
            (root / PROGRAM).write_bytes(b"changed data")
            with self.assertRaisesRegex(ValueError, "digest"):
                verify_kit(root)
            self.fixture(root)
            manifest["firmware_flashable"] = True
            self.save(root, manifest)
            with self.assertRaisesRegex(ValueError, "schema"):
                verify_kit(root)

    def test_escape_and_symlink_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for name in ("../program.elf", "/program.elf", "a\\program.elf", "C:program.elf", "a//b", "./program.elf"):
                manifest = self.fixture(root)
                manifest["files"][0]["path"] = name
                self.save(root, manifest)
                with self.assertRaisesRegex(ValueError, "path"):
                    verify_kit(root)
            self.fixture(root)
            try:
                (root / "link").symlink_to(root / PROGRAM)
            except (OSError, NotImplementedError):
                return  # Windows may require elevated symlink privileges.
            with self.assertRaisesRegex(ValueError, "symlink"):
                verify_kit(root)

    def test_redaction(self):
        self.assertEqual("[sensitive-field-redacted]", redact("password=do-not-store"))

    def test_required_files_and_coexisting_apk_metadata(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            manifest = self.fixture(root)
            (root / PROGRAM).unlink()
            manifest["files"] = [x for x in manifest["files"] if x["path"] != PROGRAM]
            self.save(root, manifest)
            with self.assertRaisesRegex(ValueError, "required_allowlist"):
                verify_kit(root)
            manifest = self.fixture(root)
            manifest["apk"]["package"] = "org.openbyddisplay"
            self.save(root, manifest)
            with self.assertRaisesRegex(ValueError, "apk_metadata"):
                verify_kit(root)
            self.assertTrue((root / APK_PATH).exists())


if __name__ == "__main__":
    unittest.main()
