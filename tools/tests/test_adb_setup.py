import importlib.util
from pathlib import Path
import unittest
from unittest.mock import Mock

spec = importlib.util.spec_from_file_location(
    "adb_setup", Path(__file__).parents[1] / "adb_setup.py"
)
setup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(setup)


class AdbSetupTests(unittest.TestCase):
    def test_multiple_devices_require_explicit_serial(self):
        output = "List of devices attached\na\tdevice\nb\tdevice\n"
        with self.assertRaises(ValueError):
            setup.select_device(output, None)
        self.assertEqual("b", setup.select_device(output, "b"))

    def test_unauthorized_and_offline_devices_are_rejected(self):
        for state in ("unauthorized", "offline"):
            with self.assertRaises(ValueError):
                setup.select_device("a\t" + state, "a")

    def test_inspection_never_changes_device_settings(self):
        adb = Mock(serial="test")
        adb.run.side_effect = [
            "35",
            "package:/data/app/base.apk",
            "No operations.",
            "lo inet 127.0.0.1",
        ]
        report = setup.inspect(adb, setup.PACKAGES[0])
        self.assertFalse(report["vehicle_connection_verified"])
        self.assertFalse(
            any(
                "set" in call.args or "start" in call.args
                for call in adb.run.call_args_list
            )
        )

    def test_permission_refusal_does_not_open_app_or_claim_success(self):
        adb = Mock()
        adb.run.side_effect = ["", "SYSTEM_ALERT_WINDOW: deny"]
        with self.assertRaises(RuntimeError):
            setup.configure_startup(
                adb, {"package": setup.PACKAGES[0], "android_api": 35}
            )
        self.assertFalse(any("start" in call.args for call in adb.run.call_args_list))

    def test_configuration_does_not_grant_unrelated_privileges_or_start_updates(self):
        adb = Mock()
        adb.run.side_effect = ["", "SYSTEM_ALERT_WINDOW: allow; time=1", "Starting"]
        report = {"package": setup.PACKAGES[0], "android_api": 35}
        setup.configure_startup(adb, report)
        self.assertEqual(3, adb.run.call_count)
        self.assertEqual("am", adb.run.call_args.args[1])
        self.assertNotIn("--ez", adb.run.call_args.args)

    def test_android_nine_does_not_need_overlay_appop(self):
        adb = Mock()
        setup.configure_startup(adb, {"package": setup.PACKAGES[1], "android_api": 28})
        self.assertEqual(1, adb.run.call_count)
        self.assertIn(
            "org.openbyddisplay.usbtest/org.openbyddisplay.MainActivity",
            adb.run.call_args.args,
        )


if __name__ == "__main__":
    unittest.main()
