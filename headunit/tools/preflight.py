"""Verify an offline kit or collect bounded, read-only Android evidence.

No APK installation, USB/ADB mode changes, vehicle commands, TCP probes, playback,
updates or reboot. Reports remain local; checksums are not package signatures.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shlex
import subprocess
import threading

from adb_setup import PACKAGES, find_adb, select_device

TARGETS = ("song-plus-dmi-2021", "tang-plus-dmi-2021")
LIMIT = 128 * 1024
APK_PATH = "headunit/OpenBYDDisplay-development-usbtest.apk"
REQUIRED_FILES = {
    APK_PATH, "OpenBYDDisplay-source.zip", "README.md",
    "tools/preflight.py", "tools/adb_setup.py", "tools/send_video.py", "tools/collect-qnx-readonly.sh",
    "samples/test-64x48-5fps-10frames.h264", "samples/test-64x48-5fps-10frames.bvl1", "samples/reference.framemd5",
} | {"docs/" + name + ".md" for name in (
    "preflight", "feature-migration", "ambient-light", "vehicle-interfaces", "native-media", "incremental-install"
)} | {f"firmware-test/{target}/{name}" for target in TARGETS for name in (
    "build-report.json", "libopenbyddisplay-video.so.0",
    *(f"obd-qnx66-{kind}-{target}.elf" for kind in ("probe", "inspect", "player")),
)}


def run_bounded(command, timeout=20, limit=LIMIT):
    """Drain a pipe with a hard retained-byte bound; works on Windows and POSIX."""
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    data = bytearray()
    overflow = threading.Event()
    failure = []

    def consume():
        try:
            while True:
                block = process.stdout.read(4096)
                if not block:
                    break
                left = limit + 1 - len(data)
                data.extend(block[:max(0, left)])
                if len(data) > limit:
                    overflow.set()
                    process.kill()
                    break
        except OSError as error:
            failure.append(type(error).__name__)

    reader = threading.Thread(target=consume, daemon=True)
    reader.start()
    timed_out = False
    try:
        process.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        timed_out = True
        process.kill()
        process.wait(timeout=5)
    reader.join(5)
    # A surviving child may keep the pipe open. Do not wait forever or report
    # success; the daemon retains at most limit+1 bytes and dies at process exit.
    if reader.is_alive():
        raise RuntimeError("command_output_not_closed")
    process.stdout.close()
    if timed_out:
        raise RuntimeError("command_timeout")
    if overflow.is_set():
        raise RuntimeError("command_output_limit")
    if failure:
        raise RuntimeError("command_output_read_failed")
    text = data.decode("utf-8", errors="replace").strip()
    if process.returncode:
        raise RuntimeError("command_exit_" + str(process.returncode))
    return text


class ReadOnlyAdb:
    def __init__(self, executable, serial=None, runner=run_bounded):
        self.executable, self.serial, self.runner = executable, serial, runner

    def run(self, *args):
        prefix = [self.executable] + (["-s", self.serial] if self.serial else [])
        return self.runner(prefix + list(args))


def redact(text, serial=None):
    if serial:
        text = text.replace(serial, "[device-redacted]")
    return re.sub(
        r"(?im)^.*\b(?:vin|password|passwd|token|authorization|android_id|serialno|account)\s*[:=].*$",
        "[sensitive-field-redacted]", text,
    )


def collect(adb, target, package):
    if target not in TARGETS or package not in PACKAGES:
        raise ValueError("unsupported_target_or_package")
    api = adb.run("shell", "getprop", "ro.build.version.sdk")
    if not re.fullmatch(r"[0-9]{1,3}", api) or int(api) < 28:
        raise ValueError("not_supported_android_9_plus; device name alone is not Android evidence")
    # Fixed commands only. No user input is concatenated into a remote shell.
    commands = {
        "identity": ("shell", "id"),
        "release": ("shell", "getprop", "ro.build.version.release"),
        "fingerprint": ("shell", "getprop", "ro.build.fingerprint"),
        "board": ("shell", "getprop", "ro.product.board"),
        "abi": ("shell", "getprop", "ro.product.cpu.abilist"),
        "apk_path": ("shell", "pm", "path", package),
        "package": ("shell", "dumpsys", "package", package),
        "appops": ("shell", "cmd", "appops", "get", package),
        "ipv4": ("shell", "ip", "-o", "-4", "addr", "show"),
        "routes": ("shell", "ip", "-4", "route", "show"),
        "display": ("shell", "dumpsys", "display"),
        "codec": ("shell", "dumpsys", "media.codec"),
        "native_ui_support": ("shell", "content", "query", "--uri", "content://carsettings/global",
                              "--projection", "value", "--where", "key = 'support_change_system_uimode'"),
        "native_ui_selection": ("shell", "content", "query", "--uri", "content://carsettings/global",
                                "--projection", "value", "--where", "key = 'sys_screen_mode'"),
    }
    # adb shell joins arguments; quote the two fixed SQL literals explicitly.
    # These commands read only known keys and never enumerate other settings.
    for key in ("native_ui_support", "native_ui_selection"):
        args = commands[key]
        commands[key] = ("shell", " ".join(shlex.quote(x) for x in args[1:]))
    observations = {}
    for key, args in commands.items():
        try:
            value = redact(adb.run(*args), adb.serial)
            observations[key] = {"status": "observed" if value else "empty", "text": value}
        except (OSError, RuntimeError, subprocess.SubprocessError) as error:
            observations[key] = {"status": "unavailable", "error": redact(str(error), adb.serial)[:256]}
    installed = observations["apk_path"].get("text", "").startswith("package:")
    return {
        "format": 1, "collected_utc": datetime.now(timezone.utc).isoformat(),
        "scope": "read-only Android observations; local report only",
        "requested_target": target, "target_hardware_verified": False,
        "package": package, "android_api": int(api), "package_observed": installed,
        "observations": observations,
        "instrument_qnx_entry": "unknown", "instrument_hardware_id": "unknown",
        "instrument_display": "unknown", "qnx_runtime_verified": False,
        "usb_mode_verified": False, "vehicle_visual_verified": False,
        "firmware_flashable": False,
    }


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate_json_key")
        result[key] = value
    return result


def verify_kit(root):
    if root.is_symlink() or not root.is_dir():
        raise ValueError("kit_root_invalid")
    manifest = root / "preflight-manifest.json"
    if manifest.is_symlink() or not manifest.is_file() or manifest.stat().st_size > 65536:
        raise ValueError("kit_manifest_invalid")
    report = json.loads(manifest.read_text(encoding="utf-8"), object_pairs_hook=unique_object)
    if (not isinstance(report, dict) or type(report.get("format")) is not int
            or report.get("format") != 1 or report.get("firmware_flashable") is not False
            or report.get("vehicle_verified") is not False
            or not isinstance(report.get("targets"), list) or len(report["targets"]) != 2
            or set(report["targets"]) != set(TARGETS)
            or not re.fullmatch(r"[0-9a-f]{40}", report.get("source_commit", ""))):
        raise ValueError("kit_schema_invalid")
    rows = report.get("files")
    if not isinstance(rows, list) or not 1 <= len(rows) <= 256:
        raise ValueError("kit_files_invalid")
    seen = set()
    for row in rows:
        if not isinstance(row, dict) or set(row) != {"path", "bytes", "sha256"}:
            raise ValueError("kit_file_row_invalid")
        name = row["path"]
        if (not isinstance(name, str) or not name or "\\" in name or "\x00" in name
                or ":" in name or PurePosixPath(name).is_absolute()
                or any(x in ("", ".", "..") for x in name.split("/"))
                or name in seen or name == "preflight-manifest.json"):
            raise ValueError("kit_path_invalid")
        seen.add(name)
        part = root
        for component in name.split("/"):
            part = part / component
            if part.is_symlink():
                raise ValueError("kit_symlink_forbidden")
        if (not part.is_file() or type(row["bytes"]) is not int or not 0 <= row["bytes"] <= 512 * 1024 * 1024
                or not isinstance(row["sha256"], str) or not re.fullmatch(r"[0-9a-f]{64}", row["sha256"])
                or part.stat().st_size != row["bytes"] or sha256(part) != row["sha256"]):
            raise ValueError("kit_digest_mismatch: " + name)
    actual = set()
    for path in root.rglob("*"):
        if path.is_symlink():
            raise ValueError("kit_symlink_forbidden")
        if path.is_file() and path != manifest:
            actual.add(path.relative_to(root).as_posix())
    if actual != seen:
        raise ValueError("kit_unlisted_or_missing_files")
    if seen != REQUIRED_FILES:
        raise ValueError("kit_required_allowlist_mismatch")
    apk = report.get("apk")
    apk_row = next(row for row in rows if row["path"] == APK_PATH)
    if (not isinstance(apk, dict) or apk.get("package") != "org.openbyddisplay.usbtest"
            or apk.get("sha256") != apk_row["sha256"] or report.get("apk_sha256") != apk_row["sha256"]
            or not re.fullmatch(r"[0-9a-f]{64}", apk.get("signer_sha256", ""))
            or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", apk.get("version", ""))
            or type(apk.get("version_code")) is not int or not 1 <= apk["version_code"] <= 2147483647):
        raise ValueError("kit_apk_metadata_mismatch")
    return {"ok": True, "files_checked": len(seen), "source_commit": report["source_commit"],
            "scope": "local checksums only; not signature or hardware qualification", "firmware_flashable": False}


def write_report(path, report):
    # Existing files, including symlinks, must not be overwritten.
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
        stream.write("\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="action", required=True)
    check = commands.add_parser("verify-kit")
    check.add_argument("root", type=Path)
    inspect = commands.add_parser("collect")
    inspect.add_argument("--adb")
    inspect.add_argument("--serial")
    inspect.add_argument("--target", choices=TARGETS, required=True)
    inspect.add_argument("--package", choices=PACKAGES, default=PACKAGES[1])
    inspect.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.action == "verify-kit":
            result = verify_kit(args.root)
        else:
            if os.path.lexists(args.output) or not args.output.parent.is_dir():
                raise ValueError("output_must_be_new_file_in_existing_directory")
            adb = ReadOnlyAdb(find_adb(args.adb))
            adb.serial = select_device(adb.run("devices"), args.serial)
            result = collect(adb, args.target, args.package)
            write_report(args.output, result)
            result = {"saved": str(args.output), "firmware_flashable": False}
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError, TypeError) as error:
        parser.exit(1, f"Preflight refused: {error}\n")


if __name__ == "__main__":
    main()
