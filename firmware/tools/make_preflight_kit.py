"""Assemble an explicit allowlist of self-developed artifacts; never copy link inputs."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import sys
import tempfile
import zlib

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "headunit/tools"))
from preflight import TARGETS, run_bounded, sha256, unique_object, verify_kit  # noqa: E402


def bounded_bytes(path, maximum):
    if path.is_symlink() or not path.is_file():
        raise ValueError("regular_input_required")
    with path.open("rb") as stream:
        data = stream.read(maximum + 1)
    if len(data) > maximum:
        raise ValueError("input_limit")
    return data


def committed(source, relative):
    return subprocess.check_output(["git", "show", source + ":" + relative], cwd=REPO, timeout=30)


def apk_metadata(data, build_tools, certificate, runner=run_bounded):
    if not re.fullmatch(r"[0-9a-f]{64}", certificate):
        raise ValueError("explicit_signer_sha256_required")
    with tempfile.TemporaryDirectory(prefix="obd-verified-apk-") as tmp:
        frozen = Path(tmp) / "development.apk"
        frozen.write_bytes(data)
        badging = runner([str(build_tools / "aapt"), "dump", "badging", str(frozen)])
        result = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging, re.M)
        if not result or result[1] != "org.openbyddisplay.usbtest":
            raise ValueError("coexisting_development_package_required")
        certs = runner([str(build_tools / "apksigner"), "verify", "--print-certs", str(frozen)])
        signers = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-f]{64})$", certs, re.M)
        if signers != [certificate]:
            raise ValueError("apk_signer_mismatch")
        return {"package": result[1], "version_code": int(result[2]), "version": result[3],
                "signer_sha256": certificate, "sha256": hashlib.sha256(data).hexdigest()}


def build_files(directory, target, source=None):
    report_path = directory / "build-report.json"
    if report_path.is_symlink() or not report_path.is_file() or report_path.stat().st_size > 65536:
        raise ValueError("build_report_invalid")
    raw_report = bounded_bytes(report_path, 65536)
    report = json.loads(raw_report, object_pairs_hook=unique_object)
    if (not isinstance(report, dict) or report.get("target") != target or report.get("firmware_flashable") is not False
            or report.get("qnx_execution_verified") is not False or report.get("hardware_verified") is not False):
        raise ValueError("build_target_or_status_invalid")
    names = {f"obd-qnx66-{kind}-{target}.elf" for kind in ("probe", "inspect", "player")}
    names.add("libopenbyddisplay-video.so.0")
    if not isinstance(report.get("artifacts"), dict) or set(report["artifacts"]) != names:
        raise ValueError("build_artifact_allowlist_mismatch")
    hashes = report.get("source_files_sha256")
    expected_sources = {p.relative_to(REPO / "firmware").as_posix()
                        for directory_ in (REPO / "firmware/native/core", REPO / "firmware/native/qnx66")
                        for p in directory_.rglob("*") if p.suffix in {".c", ".h", ".S"}}
    expected_sources |= {"tools/qnx66_build.py", "tools/qnx66_elf.py", "baselines.json"}
    if not isinstance(hashes, dict) or set(hashes) != expected_sources:
        raise ValueError("build_source_coverage_mismatch")
    for name, digest in hashes.items():
        actual = hashlib.sha256(committed(source, "firmware/" + name)).hexdigest() if source else sha256(REPO / "firmware" / name)
        if digest != actual:
            raise ValueError("build_source_changed: " + name)
    result = {"build-report.json": raw_report}
    for name in sorted(names):
        path = directory / name
        row = report["artifacts"][name]
        if not isinstance(row, dict):
            raise ValueError("build_artifact_row_invalid")
        data = bounded_bytes(path, 16 * 1024 * 1024)
        if (len(data) != row.get("bytes") or hashlib.sha256(data).hexdigest() != row.get("sha256")
                or row.get("needed") != ["libc.so.3"]):
            raise ValueError("build_artifact_mismatch: " + name)
        header = data[:52]
        if (len(header) != 52 or header[:6] != b"\x7fELF\x01\x01"
                or struct.unpack_from("<H", header, 18)[0] != 40):
            raise ValueError("build_not_arm32_le_elf")
        result[name] = data
    return result


def framed_sample(raw):
    starts = list(re.finditer(b"\0\0(?:\0)?\1", raw))
    offsets = [m.start() for m in starts if m.end() < len(raw) and raw[m.end()] & 31 == 9]
    if len(offsets) != 10 or offsets[0] != 0:
        raise ValueError("sample_requires_ten_aud_frames")
    result = bytearray(b'BVL1\0\0\0\x0f{"op":"stream"}' + struct.pack(">4I", 64, 48, 5, 0))
    for sequence, (begin, end) in enumerate(zip(offsets, offsets[1:] + [len(raw)])):
        data = raw[begin:end]
        key = any(raw[m.end()] & 31 == 5 for m in starts if begin <= m.start() < end and m.end() < end)
        result += struct.pack(">4s5I", b"LHF2", len(data), sequence, sequence * 200, int(key), zlib.crc32(data)) + data
    result += struct.pack(">4s5I", b"LHF2", 0, 0xffffffff, 0, 0, 0)
    return bytes(result)


def frame_hashes(text):
    rows = [line.split(",") for line in text.splitlines() if line and not line.startswith("#")]
    if len(rows) != 10 or any(len(row) != 6 or not re.fullmatch(r"[0-9a-f]{32}", row[-1].strip()) for row in rows):
        raise ValueError("sample_requires_ten_decoded_frames")
    return [row[-1].strip() for row in rows]


def create_sample(samples, ffmpeg="ffmpeg"):
    samples.mkdir()
    subprocess.run([ffmpeg, "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=64x48:rate=5",
                    "-frames:v", "10", "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency",
                    "-profile:v", "baseline", "-pix_fmt", "yuv420p", "-g", "5", "-bf", "0",
                    "-x264-params", "aud=1:repeat-headers=1", "-f", "h264",
                    str(samples / "test-64x48-5fps-10frames.h264")], check=True, timeout=30)
    raw = (samples / "test-64x48-5fps-10frames.h264").read_bytes()
    (samples / "test-64x48-5fps-10frames.bvl1").write_bytes(framed_sample(raw))
    subprocess.run([ffmpeg, "-v", "error", "-i", str(samples / "test-64x48-5fps-10frames.h264"),
                    "-vsync", "0", "-f", "framemd5", str(samples / "reference.framemd5")], check=True, timeout=30)
    frame_hashes((samples / "reference.framemd5").read_text())


def make(output, apk, apk_digest, builds, build_tools, certificate, ffmpeg="ffmpeg"):
    apk_data = bounded_bytes(apk, 64 * 1024 * 1024)
    if not re.fullmatch(r"[0-9a-f]{64}", apk_digest) or hashlib.sha256(apk_data).hexdigest() != apk_digest:
        raise ValueError("explicit_apk_digest_mismatch")
    source = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO, text=True).strip()
    if subprocess.check_output(["git", "status", "--porcelain"], cwd=REPO, text=True).strip():
        raise ValueError("commit_reviewed_source_before_packaging")
    apk_info = apk_metadata(apk_data, build_tools, certificate)
    config = committed(source, "headunit/app/build.gradle.kts").decode()
    if (apk_info["version"] != re.search(r'versionName = "([0-9.]+)"', config)[1]
            or apk_info["version_code"] != int(re.search(r'versionCode = ([0-9]+)', config)[1])):
        raise ValueError("apk_version_does_not_match_source")
    inputs = {target: build_files(builds[target], target, source) for target in TARGETS}
    # Exclusive directory creation. A failed partial kit has no valid manifest;
    # never replace or recursively delete an existing operator directory.
    output.mkdir(mode=0o700)

    def write(relative, data):
        destination = output / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        with destination.open("xb") as stream:
            stream.write(data)

    def copy(relative, path):
        write(relative, committed(source, path.relative_to(REPO).as_posix()))

    write("headunit/OpenBYDDisplay-development-usbtest.apk", apk_data)
    for target, files in inputs.items():
        for name, data in files.items():
            write(f"firmware-test/{target}/{name}", data)
    for name in ("preflight.py", "adb_setup.py"):
        copy("tools/" + name, REPO / "headunit/tools" / name)
    copy("tools/send_video.py", REPO / "firmware/tools/send_video.py")
    copy("tools/collect-qnx-readonly.sh", REPO / "firmware/tools/collect-qnx-readonly.sh")
    for name in ("preflight.md", "feature-migration.md", "ambient-light.md", "vehicle-interfaces.md", "native-media.md", "incremental-install.md"):
        copy("docs/" + name, REPO / "docs" / name)
    copy("README.md", REPO / "docs/preflight-kit-readme.md")
    subprocess.run(["git", "archive", "--format=zip", "--prefix=OpenBYDDisplay-source/",
                    "--output=" + str((output / "OpenBYDDisplay-source.zip").resolve()), source], cwd=REPO, check=True, timeout=60)
    samples = output / "samples"
    create_sample(samples, ffmpeg)
    rows = [{"path": p.relative_to(output).as_posix(), "bytes": p.stat().st_size, "sha256": sha256(p)}
            for p in sorted(output.rglob("*")) if p.is_file()]
    manifest = {"format": 1, "source_commit": source, "targets": list(TARGETS),
                "apk_sha256": apk_digest, "apk": apk_info, "files": rows, "firmware_flashable": False,
                "vehicle_verified": False, "includes_vendor_runtime": False,
                "signature_verified_by_kit_checksum": False}
    if (subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO, text=True).strip() != source
            or subprocess.check_output(["git", "status", "--porcelain"], cwd=REPO, text=True).strip()):
        raise ValueError("source_changed_during_packaging")
    (output / "preflight-manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return verify_kit(output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--song-build", type=Path, required=True)
    parser.add_argument("--tang-build", type=Path, required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--apk-sha256", required=True)
    parser.add_argument("--build-tools", type=Path, required=True, help="Android SDK build-tools with aapt and apksigner; maintainer build only")
    parser.add_argument("--certificate-sha256", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--ffmpeg", default="ffmpeg")
    args = parser.parse_args()
    try:
        result = make(args.output, args.apk, args.apk_sha256, dict(zip(TARGETS, (args.song_build, args.tang_build))), args.build_tools, args.certificate_sha256, args.ffmpeg)
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        parser.exit(1, f"Kit build refused: {error}\n")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
