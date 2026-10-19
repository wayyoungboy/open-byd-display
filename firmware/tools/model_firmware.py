"""Local baseline-derived research candidates. Never install, flash or run scripts.

Contains vendor input when built locally: NEVER upload candidates to GitHub.
Preserves all original TAR records; appends only the independent service directory.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import struct
import subprocess
import tarfile
import zipfile
import zlib

from cryptography.hazmat.primitives import serialization
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey

from make_preflight_kit import REPO, bounded_bytes, build_files, committed, unique_object

MAX_ZIP = 512 * 1024 * 1024
MAX_TAR = 512 * 1024 * 1024
NAMESPACE = "base/app/openbyddisplay"
OUTER = {"app.sv", "os.sv", "mcu.sv", "kzbVersion", "RcAppVersion", "version_svp"}
DOMAIN = b"OBD-BASELINE-CANDIDATE-V1\n"


def require(value, error):
    if not value:
        raise ValueError(error)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def encoded(value):
    return (json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(",", ":")) + "\n").encode()


def profile_for(target):
    profiles = json.loads((REPO / "firmware/baselines.json").read_bytes(), object_pairs_hook=unique_object)["profiles"]
    require(target in profiles, "unknown_model")
    return profiles[target]


def outer_files(data):
    require(0 < len(data) <= MAX_ZIP, "zip_size")
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        infos = archive.infolist()
        names = [i.filename for i in infos]
        require(len(names) == len(set(names)) and set(names) == OUTER, "zip_member_allowlist")
        require(sum(i.file_size for i in infos) <= MAX_ZIP, "zip_expansion_limit")
        result = {}
        for info in infos:
            require(not info.flag_bits & 1 and info.compress_type in {0, 8}
                    and 0 < info.file_size <= MAX_ZIP and not info.is_dir()
                    and (info.external_attr >> 16) & 0o170000 in {0, 0o100000}, "zip_member_type")
            with archive.open(info) as stream:
                content = stream.read(info.file_size + 1)
            require(len(content) == info.file_size, "zip_member_length")
            result[info.filename] = content
    return result


def normalized(name):
    require(isinstance(name, str) and name.startswith("./"), "tar_name_prefix")
    value = name[2:]
    require(re.fullmatch(r"[A-Za-z0-9_./-]+", value) is not None, "tar_name_chars")
    value = value.removesuffix("/")
    require(all(p not in {"", ".", ".."} for p in value.split("/"))
            and not PurePosixPath(value).is_absolute(), "tar_unsafe_path")
    return value


def app_contents(data):
    require(512 < len(data) <= MAX_ZIP, "sv_size")
    compressed, footer = data[:-512], data[-512:]
    require(footer[:19] == b"Desay SV Automotive" and footer[64:68] == b"app\0", "sv_footer")
    require(hashlib.md5(compressed).hexdigest().encode() == footer[288:320], "sv_compressed_md5")
    size = struct.unpack_from("<I", footer, 320)[0]
    require(0 < size <= MAX_TAR, "sv_expansion_limit")
    inflater = zlib.decompressobj(31)
    raw = inflater.decompress(compressed, size + 1)
    require(inflater.eof and not inflater.unused_data and not inflater.unconsumed_tail
            and len(raw) == size and len(raw) % 512 == 0, "sv_gzip_length_or_tail")
    rows, end = {}, 0
    with tarfile.open(fileobj=io.BytesIO(raw), mode="r:") as archive:
        for member in archive:
            name = normalized(member.name)
            require(name not in rows and len(rows) < 1024, "tar_duplicate_or_count")
            # These two supplied baselines only contain plain files/directories.
            # Unknown links/PAX/special records need an explicit new profile.
            require(member.type in {tarfile.REGTYPE, tarfile.DIRTYPE} and not member.pax_headers
                    and not member.linkname and member.offset == end
                    and member.offset_data == member.offset + 512, "tar_record_type_or_gap")
            require(member.size >= 0 and member.size <= MAX_TAR
                    and (member.isfile() or member.size == 0), "tar_member_size")
            for parent in PurePosixPath(name).parents:
                if str(parent) != ".":
                    require(str(parent) in rows and rows[str(parent)]["type"] == "directory", "tar_parent_not_directory")
            end = member.offset_data + (member.size + 511) // 512 * 512
            require(end <= len(raw), "tar_member_overrun")
            content = raw[member.offset_data:member.offset_data + member.size]
            rows[name] = {"bytes": member.size, "sha256": sha(content),
                          "type": "file" if member.isfile() else "directory", "mode": member.mode,
                          "uid": member.uid, "gid": member.gid, "mtime": member.mtime}
    require(len(raw) - end >= 1024 and not any(raw[end:]), "tar_end_marker_or_hidden_data")
    return raw, footer, rows, end


def read_baseline(data, profile):
    require(sha(data) == profile["original_zip_sha256"], "wrong_model_baseline_zip")
    outer = outer_files(data)
    require(set(profile["outer_files"]) == OUTER, "baseline_profile_members")
    for name, content in outer.items():
        row = profile["outer_files"][name]
        require(len(content) == row["bytes"] and sha(content) == row["sha256"], "baseline_member_digest")
    raw, footer, rows, end = app_contents(outer["app.sv"])
    require(NAMESPACE not in rows and not any(p.startswith(NAMESPACE + "/") for p in rows), "baseline_namespace_exists")
    for path, fingerprint in profile["runtime_fingerprints"].items():
        if path.startswith("/base/app/"):
            row = rows[path[1:]]
            require(row["bytes"] == fingerprint["bytes"] and row["sha256"] == fingerprint["sha256"], "baseline_runtime_fingerprint")
    return outer, raw, footer, rows, end


def file_names(target):
    return {f"obd-qnx66-{kind}-{target}.elf" for kind in ("probe", "inspect", "player")} | {
        "libopenbyddisplay-video.so.0", "build-report.json", "profile.json", "manual-start.sh"}


def service_files(artifacts, target, profile, source):
    require(set(artifacts) == file_names(target) - {"profile.json", "manual-start.sh"}, "native_file_allowlist")
    report = json.loads(artifacts["build-report.json"], object_pairs_hook=unique_object)
    require(report["runtime_libc_sha256"] == profile["runtime_fingerprints"]["/proc/boot/libc.so.3"]["sha256"], "native_runtime_baseline")
    files = dict(artifacts)
    files["profile.json"] = encoded({"format": 1, "target": target, "base_version": profile["base_version"],
        "baseline_zip_sha256": profile["original_zip_sha256"], "source_commit": source,
        "service_port": 47792, "autostart_enabled": False, "hardware_verified": False,
        "production_flashable": False, "runtime_fingerprints": profile["runtime_fingerprints"]})
    files["manual-start.sh"] = ("#!/bin/sh\n# Manual developer entry only; no installation, USB setup or OEM HMI writes.\n"
        "# ADDRESS PORT SESSION DISPLAY PHYS_W PHYS_H X Y W H Z (eleven arguments)\n"
        "[ \"$#\" -eq 11 ] || exit 64\n"
        "PATH=/proc/boot:/bin:/usr/bin; export PATH\n"
        "unset SOCK || exit 65\n"
        f'exec /{NAMESPACE}/obd-qnx66-player-{target}.elf --baseline-serve "$@"\n').encode()
    return files


def append_app(raw, footer, end, files):
    data = bytearray(raw[:end])
    entries = [(NAMESPACE, b"", True)] + [(NAMESPACE + "/" + name, content, False) for name, content in sorted(files.items())]
    for name, content, directory in entries:
        require(len(content) <= 16 * 1024 * 1024, "self_file_size")
        info = tarfile.TarInfo("./" + name)
        info.type = tarfile.DIRTYPE if directory else tarfile.REGTYPE
        info.mode = 0o700 if directory or name.endswith((".elf", ".sh")) else 0o600
        info.uid = info.gid = info.mtime = 0
        info.size = len(content)
        data += info.tobuf(format=tarfile.USTAR_FORMAT) + content + bytes(-len(content) % 512)
    data += bytes(1024)
    data += bytes(-len(data) % tarfile.RECORDSIZE)
    require(len(data) <= MAX_TAR, "candidate_expansion_limit")
    output = io.BytesIO()
    with gzip.GzipFile(filename="", mode="wb", fileobj=output, compresslevel=6, mtime=0) as stream:
        stream.write(data)
    compressed = output.getvalue()
    updated = bytearray(footer)
    updated[288:320] = hashlib.md5(compressed).hexdigest().encode()
    struct.pack_into("<I", updated, 320, len(data))
    return compressed + updated


def zip_bytes(files):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
        for name, content in sorted(files.items()):
            info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = 0o100600 << 16
            archive.writestr(info, content)
    return output.getvalue()


def check_candidate(original, candidate, target, profile, expected_files):
    outer, raw, footer, rows, end = read_baseline(original, profile)
    changed = outer_files(candidate)
    require(all(changed[n] == outer[n] for n in OUTER - {"app.sv"}), "oem_outer_member_changed")
    new_raw, new_footer, new_rows, _ = app_contents(changed["app.sv"])
    require(footer[:288] == new_footer[:288] and footer[324:] == new_footer[324:], "sv_unrelated_footer_changed")
    require(new_raw[:end] == raw[:end], "oem_tar_record_changed")
    require(all(new_rows.get(n) == row for n, row in rows.items()), "oem_member_changed")
    require(set(expected_files) == file_names(target), "self_file_allowlist")
    expected_names = {NAMESPACE} | {NAMESPACE + "/" + name for name in expected_files}
    require(set(new_rows) - set(rows) == expected_names, "candidate_unexpected_paths")
    directory = new_rows[NAMESPACE]
    require(directory["type"] == "directory" and directory["mode"] == 0o700
            and directory["uid"] == directory["gid"] == 0, "self_directory_metadata")
    for name, row in expected_files.items():
        actual = new_rows[NAMESPACE + "/" + name]
        require(actual["type"] == "file" and actual["bytes"] == row["bytes"] and actual["sha256"] == row["sha256"]
                and actual["mode"] == (0o700 if name.endswith((".elf", ".sh")) else 0o600)
                and actual["uid"] == actual["gid"] == 0, "self_file_mismatch")
    return {"original_tar_records_sha256": sha(raw[:end]), "original_tar_record_bytes": end,
            "original_members_preserved": len(rows), "self_files_added": len(expected_files)}


def compose(original, target, profile, artifacts, source):
    outer, raw, footer, _, end = read_baseline(original, profile)
    files = service_files(artifacts, target, profile, source)
    changed = dict(outer)
    changed["app.sv"] = append_app(raw, footer, end, files)
    candidate = zip_bytes(changed)
    additions = {n: {"bytes": len(d), "sha256": sha(d)} for n, d in files.items()}
    evidence = check_candidate(original, candidate, target, profile, additions)
    report = {"format": 1, "kind": "local-baseline-extension-research-candidate", "source_commit": source,
              "target": target, "base_version": profile["base_version"],
              "baseline_zip_sha256": sha(original), "candidate_zip_sha256": sha(candidate),
              "candidate_zip_bytes": len(candidate), "self_files": additions, "evidence": evidence,
              "user_reported_baseline_tested": True, "candidate_vehicle_verified": False,
              "qnx_execution_verified": False, "production_flashable": False,
              "autostart_enabled": False, "contains_vendor_baseline": True,
              "public_distribution_allowed_by_this_tool": False, "service_port": 47792,
              "oem_hmi_usb_and_original_services_preserved": True,
              "native_production_installer_implemented": False}
    return candidate, encoded(report)


def clean_source():
    require(not subprocess.check_output(["git", "status", "--porcelain"], cwd=REPO), "commit_source_before_build")
    return subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO, text=True).strip()


def build(output, baseline, native_build, target, private_key):
    source = clean_source()
    require(not output.resolve().is_relative_to(REPO.resolve()), "vendor_candidate_must_stay_outside_repository")
    require(not output.exists() and not output.is_symlink(), "output_exists")
    profile = profile_for(target)
    # Freeze and validate every input before publishing any candidate directory.
    original = bounded_bytes(baseline, MAX_ZIP)
    artifacts = build_files(native_build, target, source)
    require(committed(source, "firmware/baselines.json") == (REPO / "firmware/baselines.json").read_bytes(), "profile_source_changed")
    candidate, report = compose(original, target, profile, artifacts, source)
    key = serialization.load_pem_private_key(bounded_bytes(private_key, 16384), password=None)
    require(isinstance(key, Ed25519PrivateKey), "ed25519_key_required")
    public = key.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo)
    require(clean_source() == source, "source_changed_during_build")
    output.mkdir(mode=0o700)
    outputs = {"NOT-FOR-FLASH.txt": "本地研究候选，含用户提供的原固件。未接管启动，未完成独立安装/恢复，不是可刷交付。\n请勿上传公开仓库/Release，也不要放入自动刷机目录。\n".encode(),
               "candidate-public.pem": public, "firmware-candidate.zip": candidate,
               "candidate-report.json": report, "candidate-report.sig": key.sign(DOMAIN + report)}
    for name, content in outputs.items():
        with (output / name).open("xb") as stream:
            stream.write(content)
        (output / name).chmod(0o600)
    return {"target": target, "candidate_sha256": sha(candidate), "trusted_public_pem_sha256": sha(public),
            "production_flashable": False}


def verify(directory, baseline, target, public_key, public_digest):
    public = bounded_bytes(public_key, 16384)
    require(re.fullmatch(r"[0-9a-f]{64}", public_digest) and sha(public) == public_digest, "trusted_key_fingerprint")
    key = serialization.load_pem_public_key(public)
    require(isinstance(key, Ed25519PublicKey), "ed25519_key_required")
    report_raw = bounded_bytes(directory / "candidate-report.json", 1024 * 1024)
    signature = bounded_bytes(directory / "candidate-report.sig", 64)
    key.verify(signature, DOMAIN + report_raw)
    report = json.loads(report_raw, object_pairs_hook=unique_object)
    profile = profile_for(target)
    require(report["format"] == 1 and report["target"] == target
            and report["kind"] == "local-baseline-extension-research-candidate"
            and report["baseline_zip_sha256"] == profile["original_zip_sha256"]
            and report["production_flashable"] is False and report["candidate_vehicle_verified"] is False
            and report["autostart_enabled"] is False and report["service_port"] == 47792
            and re.fullmatch(r"[0-9a-f]{40}", report["source_commit"]), "report_identity_or_status")
    candidate = bounded_bytes(directory / "firmware-candidate.zip", MAX_ZIP)
    require(sha(candidate) == report["candidate_zip_sha256"] and len(candidate) == report["candidate_zip_bytes"], "candidate_signature_digest")
    evidence = check_candidate(bounded_bytes(baseline, MAX_ZIP), candidate, target, profile, report["self_files"])
    require(evidence == report["evidence"], "candidate_evidence_changed")
    return {"signature_verified": True, "target": target, "baseline_preserved": True, "production_flashable": False,
            "candidate_sha256": sha(candidate), "evidence": evidence}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="op", required=True)
    make = sub.add_parser("build")
    make.add_argument("--native-build", type=Path, required=True)
    make.add_argument("--private-key", type=Path, required=True)
    make.add_argument("--output", type=Path, required=True)
    check = sub.add_parser("verify")
    check.add_argument("--directory", type=Path, required=True)
    check.add_argument("--public-key", type=Path, required=True)
    check.add_argument("--expected-public-key-sha256", required=True)
    for p in (make, check):
        p.add_argument("--baseline", type=Path, required=True)
        p.add_argument("--target", choices=("song-plus-dmi-2021", "tang-plus-dmi-2021"), required=True)
    args = parser.parse_args()
    try:
        if args.op == "build":
            result = build(args.output, args.baseline, args.native_build, args.target, args.private_key)
        else:
            result = verify(args.directory, args.baseline, args.target, args.public_key, args.expected_public_key_sha256)
    except (ValueError, KeyError, TypeError, OSError, InvalidSignature, zlib.error, tarfile.TarError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        parser.exit(1, f"Candidate refused: {error}\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
