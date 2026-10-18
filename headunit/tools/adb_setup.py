"""Read-only ADB diagnostics by default; opt in to the single startup app-op."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

PACKAGES = ("org.openbyddisplay", "org.openbyddisplay.usbtest")


class Adb:
    def __init__(self, executable: str, serial: str | None = None):
        self.executable = executable
        self.serial = serial

    def run(self, *args: str) -> str:
        command = [self.executable]
        if self.serial:
            command += ["-s", self.serial]
        result = subprocess.run(
            command + list(args), capture_output=True, text=True, timeout=30
        )
        if result.returncode:
            raise RuntimeError(result.stderr.strip() or "ADB command failed")
        return result.stdout.strip()


def select_device(output: str, requested: str | None) -> str:
    devices = {}
    for line in output.splitlines():
        fields = line.split()
        if len(fields) >= 2 and fields[0] != "List":
            devices[fields[0]] = fields[1]
    if requested:
        if devices.get(requested) != "device":
            raise ValueError("指定设备未连接或尚未授权，请先在车机允许USB调试")
        return requested
    if len(devices) != 1 or next(iter(devices.values()), None) != "device":
        raise ValueError("请连接并授权一台设备；存在多设备时必须使用 --serial")
    return next(iter(devices))


def overlay_allowed(output: str) -> bool:
    return re.search(r"SYSTEM_ALERT_WINDOW:\s*allow\b", output) is not None


def inspect(adb: Adb, package: str) -> dict:
    if package not in PACKAGES:
        raise ValueError("Unsupported package")
    sdk = int(adb.run("shell", "getprop", "ro.build.version.sdk"))
    if sdk < 28:
        raise ValueError("当前App需要Android 9/API28或以上")
    if not adb.run("shell", "pm", "path", package).startswith("package:"):
        raise ValueError("未安装所选包；请先安装GitHub Release中的APK")
    mode = adb.run("shell", "cmd", "appops", "get", package, "SYSTEM_ALERT_WINDOW")
    try:
        network = adb.run("shell", "ip", "-o", "-4", "addr", "show")
    except RuntimeError:
        network = "当前系统不提供ip网络诊断"
    return {
        "serial": adb.serial,
        "package": package,
        "android_api": sdk,
        "overlay_appop": mode,
        "network_ipv4": network,
        "vehicle_connection_verified": False,
    }


def configure_startup(adb: Adb, report: dict) -> None:
    package = report["package"]
    if package not in PACKAGES:
        raise ValueError("Unsupported package")
    if report["android_api"] >= 29:
        adb.run(
            "shell", "cmd", "appops", "set", package, "SYSTEM_ALERT_WINDOW", "allow"
        )
        mode = adb.run("shell", "cmd", "appops", "get", package, "SYSTEM_ALERT_WINDOW")
        if not overlay_allowed(mode):
            raise RuntimeError("系统未确认后台打开权限，请在车机设置中核对")
        report["overlay_appop"] = mode
    adb.run("shell", "am", "start", "-n", package + "/org.openbyddisplay.MainActivity")
    report["next_step"] = (
        "在App中开启开机自启，然后重启车机验证；本脚本未开启更新或投屏"
    )


def find_adb(argument: str | None) -> str:
    candidate = argument or shutil.which("adb")
    if candidate:
        return candidate
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        executable = (
            Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
        )
        if executable.is_file():
            return str(executable)
    raise ValueError("找不到ADB，请安装Android platform-tools或通过 --adb 指定路径")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", help="adb executable path")
    parser.add_argument(
        "--serial", help="required when more than one device is connected"
    )
    parser.add_argument("--package", choices=PACKAGES, default=PACKAGES[0])
    parser.add_argument("--configure-autostart", action="store_true")
    args = parser.parse_args()
    try:
        adb = Adb(find_adb(args.adb))
        adb.serial = select_device(adb.run("devices"), args.serial)
        report = inspect(adb, args.package)
        if args.configure_autostart:
            configure_startup(adb, report)
        print(json.dumps(report, ensure_ascii=False, indent=2))
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
        parser.exit(1, str(error) + "\n")


if __name__ == "__main__":
    main()
