"""Compose a local, model-bound boot-integrated bench image from supplied input.

Never execute installers, change mounted targets or distribute vendor payloads.
This implements the async bootstrap bridge and owner-signed native generation;
hardware calibration, QNX execution and actual flashing remain separate gates.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path
import re
import struct
import tarfile
import tempfile
import uuid
import zlib

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import (
    Ed25519PrivateKey,
    Ed25519PublicKey,
)
from cryptography.exceptions import InvalidSignature

from model_firmware import (
    MAX_TAR,
    MAX_ZIP,
    NAMESPACE,
    REPO,
    append_app,
    app_contents,
    bounded_bytes,
    build_files,
    clean_source,
    encoded,
    outer_files,
    profile_for,
    read_baseline,
    require,
    sha,
    zip_bytes,
    unique_object,
)
from native_generation import build as generation_build

STARTUP = "base/scripts/.run-startup.ksh"
STARTUP_SHA = "263ff0236115c523d9c5c7034a65f2e72dfcd71049422118b5a659d23f083fe7"
START_HMI_SHA = "ad6e831c06d0e53e844b5d2959109a590a54c8e8c72f53932b8b43713349d410"
DOMAIN = b"OBD-BOOTSTRAP-IMAGE-V1\n"


def profile_bytes(target, config):
    fields = {
        "enabled",
        "display",
        "physical_width",
        "physical_height",
        "x",
        "y",
        "width",
        "height",
        "zorder",
        "trial_seconds",
        "address",
    }
    require(
        isinstance(config, dict)
        and set(config) in (fields, fields | {"network_runtime_id"})
        and type(config["enabled"]) is bool,
        "explicit_profile",
    )
    require(config["address"] in {"127.0.0.1", "192.168.77.1"}, "profile_address")
    for name in fields - {"enabled", "address"}:
        require(
            type(config[name]) is int and 0 <= config[name] <= 65535, "profile_integer"
        )
    w, h = config["physical_width"], config["physical_height"]
    require(
        0 <= config["display"] <= 16
        and 2 <= w <= 1920
        and 2 <= h <= 1080
        and 2 <= config["width"] <= w
        and 2 <= config["height"] <= h
        and config["x"] <= w - config["width"]
        and config["y"] <= h - config["height"]
        and config["zorder"] <= 9
        and 30 <= config["trial_seconds"] <= 300,
        "profile_bounds",
    )
    require(target in {"song-plus-dmi-2021", "tang-plus-dmi-2021"}, "profile_target")
    raw = bytearray(64)
    raw[:8] = b"OBDPRO1\n"
    raw[8:10] = bytes([1 if target.startswith("song-") else 2, int(config["enabled"])])
    raw[12:16] = bytes(map(int, config["address"].split(".")))
    if "network_runtime_id" in config:
        runtime = config["network_runtime_id"]
        require(
            isinstance(runtime, str)
            and re.fullmatch(r"[0-9a-f]{32}", runtime)
            and runtime != "0" * 32
            and config["address"] == "192.168.77.1",
            "private_network_id",
        )
        raw[10] = 1
        raw[36:52] = bytes.fromhex(runtime)
    struct.pack_into(
        "<10H",
        raw,
        16,
        *(
            config[k]
            for k in (
                "display",
                "physical_width",
                "physical_height",
                "x",
                "y",
                "width",
                "height",
                "zorder",
                "trial_seconds",
            )
        ),
        47792,
    )
    return bytes(raw)


def os_contents(data):
    require(512 < len(data) <= MAX_ZIP, "os_sv_size")
    compressed, footer = data[:-512], data[-512:]
    require(
        footer[:19] == b"Desay SV Automotive"
        and footer[64:67] == b"os\0"
        and hashlib.md5(compressed).hexdigest().encode() == footer[288:320],
        "os_sv_footer",
    )
    size = struct.unpack_from("<I", footer, 320)[0]
    require(0 < size <= MAX_TAR, "os_sv_expansion")
    inflater = zlib.decompressobj(31)
    raw = inflater.decompress(compressed, size + 1)
    require(
        inflater.eof
        and not inflater.unused_data
        and not inflater.unconsumed_tail
        and len(raw) == size,
        "os_sv_length",
    )
    records, end = {}, 0
    with tarfile.open(fileobj=io.BytesIO(raw), mode="r:") as archive:
        for member in archive:
            name = member.name.removeprefix("./").removesuffix("/")
            require(
                name not in records
                and len(records) < 4096
                and member.offset == end
                and member.offset_data == member.offset + 512
                and not member.pax_headers
                and not name.startswith("/")
                and all(p not in {"", ".", ".."} for p in name.split("/")),
                "os_tar_record",
            )
            require(
                member.type
                in {tarfile.REGTYPE, tarfile.DIRTYPE, tarfile.SYMTYPE, tarfile.LNKTYPE}
                and 0 <= member.size <= MAX_TAR,
                "os_tar_type",
            )
            end = member.offset_data + (member.size + 511) // 512 * 512
            require(end <= len(raw), "os_tar_bound")
            records[name] = (member, raw[member.offset : end])
    require(len(raw) - end >= 1024 and not any(raw[end:]), "os_tar_tail")
    return raw, footer, records, end


def bridge_script(target):
    require(target in {"song-plus-dmi-2021", "tang-plus-dmi-2021"}, "bridge_target")
    home = "/" + NAMESPACE
    return (
        "    # BEGIN OPENBYDDISPLAY_ASYNC_V1\n"
        f"    if [ -d {home} ] && [ ! -L {home} ] &&\n"
        f"       [ -f {home}/boot.sh ] && [ ! -L {home}/boot.sh ]; then\n"
        f"        /proc/boot/ksh {home}/boot.sh </dev/null >/dev/null 2>&1 &\n"
        "    fi\n    # END OPENBYDDISPLAY_ASYNC_V1\n\n"
    ).encode()


def patch_os(data, target):
    raw, footer, records, end = os_contents(data)
    require(
        STARTUP in records and "base/scripts/start-hmi.sh" in records, "startup_missing"
    )
    member, record = records[STARTUP]
    original = record[512 : 512 + member.size]
    hmi, hrecord = records["base/scripts/start-hmi.sh"]
    require(
        member.isfile()
        and sha(original) == STARTUP_SHA
        and hmi.isfile()
        and sha(hrecord[512 : 512 + hmi.size]) == START_HMI_SHA,
        "startup_baseline_mismatch",
    )
    anchor = b"    # BEGIN FISSION_OPTIONAL_SERVICES_P1\n"
    require(original.count(anchor) == 1, "startup_anchor")
    changed = original.replace(anchor, bridge_script(target) + anchor, 1)
    # Preserve every unrelated raw TAR record and the original startup metadata.
    # Change only this header's length/checksum and its content/padding.
    header = bytearray(record[:512])
    header[124:136] = f"{len(changed):011o}\0".encode()
    header[148:156] = b"        "
    header[148:156] = f"{sum(header):06o}\0 ".encode()
    replacement = bytes(header) + changed + bytes(-len(changed) % 512)
    after = (
        raw[: member.offset]
        + replacement
        + raw[member.offset + len(record) : end]
        + bytes(1024)
    )
    after += bytes(-len(after) % tarfile.RECORDSIZE)
    output = io.BytesIO()
    with gzip.GzipFile(
        filename="", mode="wb", fileobj=output, compresslevel=6, mtime=0
    ) as stream:
        stream.write(after)
    compressed = output.getvalue()
    footer = bytearray(footer)
    footer[288:320] = hashlib.md5(compressed).hexdigest().encode()
    struct.pack_into("<I", footer, 320, len(after))
    result = compressed + footer
    _, _, check, _ = os_contents(result)
    require(
        set(check) == set(records)
        and all(check[n][1] == r[1] for n, r in records.items() if n != STARTUP),
        "os_unrelated_record_changed",
    )
    return (
        result,
        original,
        {
            "original_startup_sha256": sha(original),
            "startup_sha256": sha(changed),
            "unrelated_os_records_preserved": len(records) - 1,
            "oem_hmi_start_preserved": True,
        },
    )


def platform_files(directory, target, source):
    result = build_files(directory, target, source)
    report = json.loads(result["build-report.json"])
    names = {f"obd-qnx66-{kind}-{target}.elf" for kind in ("install", "supervisor")} | {
        "libopenbyddisplay-platform.so.0"
    }
    require(
        report.get("native_platform_built") is True
        and report.get("host_fault_injection_compiled") is False
        and set(report.get("platform_artifacts", {})) == names,
        "platform_build_required",
    )
    for name in names:
        raw = bounded_bytes(directory / name, 16 * 1024 * 1024)
        row = report["platform_artifacts"][name]
        require(
            sha(raw) == row["sha256"]
            and len(raw) == row["bytes"]
            and row["needed"] == ["libc.so.3"],
            "platform_artifact_mismatch",
        )
        result[name] = raw
    return result


def compose(original, artifacts, target, profile, config, key, source):
    outer, raw, footer, rows, end = read_baseline(original, profile)
    changed_os, startup_backup, evidence = patch_os(outer["os.sv"], target)
    public = key.public_key().public_bytes(
        serialization.Encoding.Raw, serialization.PublicFormat.Raw
    )
    ident, nonce = uuid.uuid4().hex, uuid.uuid4().hex
    native_profile = profile_bytes(target, config)
    payload = generation_build(
        {
            "service.elf": (artifacts[f"obd-qnx66-player-{target}.elf"], 0o700),
            "profile.dat": (native_profile, 0o600),
        },
        target,
        profile["original_zip_sha256"],
        "0.2.0",
        key,
        ident,
    )
    files = dict(artifacts)
    files["update-public.bin"] = public
    files["initial-generation.obg"] = payload
    files["startup-original.ksh"] = startup_backup  # data backup, never executed
    files["Monocypher-LICENCE.md"] = bounded_bytes(
        REPO / "firmware/native/vendor/monocypher/LICENCE.md", 16384
    )
    files["NOTICE"] = bounded_bytes(REPO / "NOTICE", 16384)
    files["LICENSE"] = bounded_bytes(REPO / "LICENSE", 16384)
    home = "/" + NAMESPACE
    root = home + "/store-" + target
    common = (
        f"--root {root} --key {home}/update-public.bin --key-sha256 {sha(public)} "
        f"--target {target} --baseline {profile['original_zip_sha256']}"
    )
    files["boot.sh"] = (
        "#!/bin/sh\numask 077\nunset SOCK || exit 65\n"
        f"[ -d {root} ] && [ ! -L {root} ] || exit 0\n"
        f"exec {home}/obd-qnx66-supervisor-{target}.elf {common}\n"
    ).encode()
    files["prepare-store.sh"] = (
        "#!/bin/sh\n# Explicit operator installation only; never run from boot.sh.\n"
        '[ "$#" -eq 0 ] || exit 64\numask 077\nunset SOCK || exit 65\n'
        f"{home}/obd-qnx66-install-{target}.elf {common} init || exit $?\n"
        f"{home}/obd-qnx66-install-{target}.elf {common} stage {home}/initial-generation.obg || exit $?\n"
        f"exec {home}/obd-qnx66-install-{target}.elf {common} activate {ident} {nonce}\n"
    ).encode()
    files["profile.json"] = encoded(
        {
            "target": target,
            "config": config,
            "source_commit": source,
            "baseline_zip_sha256": profile["original_zip_sha256"],
            "initial_package_id": ident,
            "generation_sha256": sha(payload),
            "raw_public_key_sha256": sha(public),
            "auto_provision": False,
            "usb_mode_auto_change": False,
            "qnx_execution_verified": False,
            "production_flashable": False,
        }
    )
    updated = dict(outer)
    updated["app.sv"] = append_app(raw, footer, end, files)
    updated["os.sv"] = changed_os
    require(
        all(updated[n] == outer[n] for n in outer.keys() - {"app.sv", "os.sv"}),
        "mcu_or_versions_changed",
    )
    image = zip_bytes(updated)
    report = {
        "format": 1,
        "kind": "local-model-boot-integrated-bench-image",
        "target": target,
        "source_commit": source,
        "baseline_zip_sha256": sha(original),
        "image_sha256": sha(image),
        "image_bytes": len(image),
        "self_files": {
            n: {"bytes": len(v), "sha256": sha(v)} for n, v in files.items()
        },
        "original_app_records_preserved": len(rows),
        "startup": evidence,
        "user_reported_original_vehicle_tested": True,
        "native_signed_store_implemented": True,
        "native_supervisor_implemented": True,
        "auto_provision": False,
        "usb_driver_adapter_integrated": False,
        "oem_theme_center_adapter_integrated": False,
        "qnx_execution_verified": False,
        "modified_vehicle_verified": False,
        "production_flashable": False,
    }
    return image, encoded(report), payload, files["profile.json"]


def build(output, baseline, native_build, target, private_key, config_path):
    require(
        not output.exists() and not output.is_symlink(), "exclusive_output_required"
    )
    require(
        not output.resolve().is_relative_to(REPO.resolve()),
        "vendor_output_outside_repository",
    )
    source = clean_source()
    require(re.fullmatch(r"[0-9a-f]{40}", source), "source_commit")
    profile = profile_for(target)
    artifacts = platform_files(native_build, target, source)
    key = serialization.load_pem_private_key(
        bounded_bytes(private_key, 16384), password=None
    )
    require(isinstance(key, Ed25519PrivateKey), "ed25519_key_required")
    config = json.loads(
        bounded_bytes(config_path, 4096), object_pairs_hook=unique_object
    )
    image, report, generation, native_config = compose(
        bounded_bytes(baseline, MAX_ZIP),
        artifacts,
        target,
        profile,
        config,
        key,
        source,
    )
    public = key.public_key().public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo
    )
    # Every input is frozen/verified before the output becomes visible.
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(
        prefix=".obd-bootstrap-", dir=output.parent
    ) as temporary:
        stage = Path(temporary)
        for name, data in {
            "boot-integrated-bench.zip": image,
            "image-report.json": report,
            "image-report.sig": key.sign(DOMAIN + report),
            "image-public.pem": public,
            "initial-generation.obg": generation,
            "profile.json": native_config,
            "NOT-VEHICLE-VERIFIED.txt": b"QNX execution, physical USB/HMI and flashing have not been verified.\n",
        }.items():
            (stage / name).write_bytes(data)
            (stage / name).chmod(0o600)
        require(clean_source() == source, "source_changed_during_build")
        require(not output.exists() and not output.is_symlink(), "output_appeared")
        stage.rename(output)
    return {
        "target": target,
        "image_sha256": sha(image),
        "report_public_pem_sha256": sha(public),
        "production_flashable": False,
    }


def verify(directory, baseline, target, public_key, public_sha):
    public = bounded_bytes(public_key, 16384)
    require(
        re.fullmatch(r"[0-9a-f]{64}", public_sha) and sha(public) == public_sha,
        "trusted_public_pin",
    )
    key = serialization.load_pem_public_key(public)
    require(isinstance(key, Ed25519PublicKey), "ed25519_public_key")
    report_raw = bounded_bytes(directory / "image-report.json", 1024 * 1024)
    key.verify(bounded_bytes(directory / "image-report.sig", 64), DOMAIN + report_raw)
    report = json.loads(report_raw, object_pairs_hook=unique_object)
    require(
        report["target"] == target
        and report["kind"] == "local-model-boot-integrated-bench-image"
        and report["production_flashable"] is False
        and report["qnx_execution_verified"] is False
        and report["modified_vehicle_verified"] is False,
        "signed_report_identity",
    )
    original = bounded_bytes(baseline, MAX_ZIP)
    outer, raw, old_app_footer, rows, end = read_baseline(original, profile_for(target))
    require(sha(original) == report["baseline_zip_sha256"], "report_baseline")
    image = bounded_bytes(directory / "boot-integrated-bench.zip", MAX_ZIP)
    require(
        sha(image) == report["image_sha256"] and len(image) == report["image_bytes"],
        "signed_image_digest",
    )
    changed = outer_files(image)
    require(
        all(changed[n] == outer[n] for n in outer.keys() - {"app.sv", "os.sv"}),
        "unrelated_outer_changed",
    )
    new_raw, new_app_footer, new_rows, _ = app_contents(changed["app.sv"])
    require(
        old_app_footer[:288] == new_app_footer[:288]
        and old_app_footer[324:] == new_app_footer[324:],
        "unrelated_app_footer_changed",
    )
    require(
        raw[:end] == new_raw[:end]
        and all(new_rows[n] == row for n, row in rows.items()),
        "original_app_changed",
    )
    expected = report["self_files"]
    require(
        isinstance(expected, dict)
        and 1 <= len(expected) <= 32
        and all(re.fullmatch(r"[A-Za-z0-9_.-]{1,96}", name) for name in expected),
        "self_file_names",
    )
    require(
        set(new_rows) - set(rows)
        == {NAMESPACE} | {NAMESPACE + "/" + name for name in expected},
        "unexpected_app_paths",
    )
    home = new_rows[NAMESPACE]
    require(
        home["type"] == "directory"
        and home["mode"] == 0o700
        and home["uid"] == home["gid"] == 0,
        "namespace_metadata",
    )
    require(
        expected["update-public.bin"]["sha256"]
        == sha(
            key.public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
        ),
        "generation_key_mismatch",
    )
    for name, row in expected.items():
        member = new_rows[NAMESPACE + "/" + name]
        require(
            member["type"] == "file"
            and member["bytes"] == row["bytes"]
            and member["sha256"] == row["sha256"]
            and member["uid"] == member["gid"] == 0
            and member["mode"] == (0o700 if name.endswith((".sh", ".elf")) else 0o600),
            "self_file_mismatch",
        )
    _, old_footer, old_os, _ = os_contents(outer["os.sv"])
    _, new_footer, new_os, _ = os_contents(changed["os.sv"])
    require(
        old_footer[:288] == new_footer[:288]
        and old_footer[324:] == new_footer[324:]
        and set(old_os) == set(new_os)
        and all(
            new_os[n][1] == record[1] for n, record in old_os.items() if n != STARTUP
        ),
        "unrelated_os_changed",
    )
    member, original_record = old_os[STARTUP]
    original_script = original_record[512 : 512 + member.size]
    startup_evidence = report["startup"]
    updated_member, updated_record = new_os[STARTUP]
    updated_script = updated_record[512 : 512 + updated_member.size]
    require(
        sha(original_script) == STARTUP_SHA
        and sha(updated_script) == startup_evidence["startup_sha256"]
        and updated_script
        == original_script.replace(
            b"    # BEGIN FISSION_OPTIONAL_SERVICES_P1\n",
            bridge_script(target) + b"    # BEGIN FISSION_OPTIONAL_SERVICES_P1\n",
            1,
        ),
        "startup_bridge_changed",
    )
    old_header, new_header = (
        bytearray(original_record[:512]),
        bytearray(updated_record[:512]),
    )
    for start, stop in ((124, 136), (148, 156)):
        old_header[start:stop] = new_header[start:stop] = bytes(stop - start)
    require(
        old_header == new_header
        and not any(updated_record[512 + updated_member.size :]),
        "startup_header_or_padding_changed",
    )
    for name in ("initial-generation.obg", "profile.json"):
        data = bounded_bytes(directory / name, 16 * 1024 * 1024 + 4096)
        require(
            sha(data) == expected[name]["sha256"]
            and len(data) == expected[name]["bytes"],
            "detached_component_changed",
        )
    return {
        "target": target,
        "signature_verified": True,
        "image_sha256": sha(image),
        "oem_hmi_preserved": True,
        "production_flashable": False,
    }


def main():
    p = argparse.ArgumentParser(description=__doc__)
    commands = p.add_subparsers(dest="operation", required=True)
    create = commands.add_parser("build")
    check = commands.add_parser("verify")
    for command in (create, check):
        command.add_argument("--baseline", type=Path, required=True)
        command.add_argument(
            "--target",
            choices=["song-plus-dmi-2021", "tang-plus-dmi-2021"],
            required=True,
        )
    for name in ("output", "native-build", "private-key", "config"):
        create.add_argument("--" + name, type=Path, required=True)
    for name in ("directory", "public-key"):
        check.add_argument("--" + name, type=Path, required=True)
    check.add_argument("--public-pem-sha256", required=True)
    a = p.parse_args()
    try:
        if a.operation == "build":
            result = build(
                a.output, a.baseline, a.native_build, a.target, a.private_key, a.config
            )
        else:
            result = verify(
                a.directory, a.baseline, a.target, a.public_key, a.public_pem_sha256
            )
        print(json.dumps(result, indent=2))
    except (ValueError, OSError, KeyError, struct.error, InvalidSignature) as error:
        p.exit(1, f"Bootstrap image refused: {error}\n")


if __name__ == "__main__":
    main()
