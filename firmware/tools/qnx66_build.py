"""Build independent ARM32 probe/core against an explicitly fingerprinted local libc.

No QNX SDK, vendor CRT, firmware modification, installation or target execution.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tempfile

from elftools.elf.elffile import ELFFile
from elftools.common.exceptions import ELFError

from qnx66_elf import MAX_ELF, link_view, runtime_symbols

ROOT = Path(__file__).resolve().parents[1]
TARGETS = {"song-plus-dmi-2021", "tang-plus-dmi-2021"}


def baseline_header(target):
    profiles = json.loads((ROOT / "baselines.json").read_text())["profiles"]
    profile = profiles[target]
    files = profile["runtime_fingerprints"]
    required = {"/base/app/rc_hmi/rc_hmi.exe", "/base/app/rc_hmi/ui.so",
                "/base/app/rc_hmi/bic_byd_stha.kzb", "/proc/boot/libc.so.3"}
    if set(files) != required or not re.fullmatch(r"[0-9a-f]{64}", profile["original_zip_sha256"]):
        raise ValueError("Invalid baseline fingerprints")
    if not re.fullmatch(r"[0-9.\-]+", profile["base_version"]):
        raise ValueError("Invalid baseline version")
    lines = [f'#define OBD_BASE_VERSION "{profile["base_version"]}"',
             f'#define OBD_BASE_ZIP_SHA256 "{profile["original_zip_sha256"]}"',
             'static const obd_baseline_file obd_compiled_baseline_files[4]={']
    for path, row in sorted(files.items()):
        if (type(row["bytes"]) is not int or not 0 < row["bytes"] <= 32 * 1024 * 1024
                or not re.fullmatch(r"[0-9a-f]{64}", row["sha256"])):
            raise ValueError("Invalid baseline file")
        lines.append(f'{{"{path}",{row["bytes"]},"{row["sha256"]}"}},')
    return "\n".join(lines + ['};', ''])


def command(args):
    result = subprocess.run(args, capture_output=True, text=True, timeout=90)
    if result.returncode:
        raise RuntimeError(f"Command failed: {args[0]}\n{result.stderr[:8192]}")
    return result.stdout.strip()


def executable(value):
    resolved = shutil.which(value)
    if not resolved:
        raise ValueError(f"Tool not found: {value}")
    # LLVM's multicall driver selects ELF/Mach-O/COFF by the invocation name.
    # Resolving Linux's ld.lld symlink to the generic lld breaks that dispatch.
    return str(Path(resolved).absolute())


def inspect(data, exports, kind):
    elf = ELFFile(io.BytesIO(data))
    if (
        elf.elfclass != 32
        or not elf.little_endian
        or elf.header.e_machine != "EM_ARM"
        or elf.header.e_type != ("ET_EXEC" if kind == "probe" else "ET_DYN")
        or elf.header.e_flags & 0xFF000400 != 0x05000000
    ):
        raise ValueError("Unexpected output ELF ABI")
    dynamic = elf.get_section_by_name(".dynamic")
    needed = [t.needed for t in dynamic.iter_tags() if t.entry.d_tag == "DT_NEEDED"]
    if needed != ["libc.so.3"]:
        raise ValueError(f"Unexpected target dependencies: {needed}")
    imports = sorted(
        {
            s.name
            for s in elf.get_section_by_name(".dynsym").iter_symbols()
            if s.name and s.entry.st_shndx == "SHN_UNDEF"
        }
    )
    if not set(imports) <= exports:
        raise ValueError(
            f"Imports do not resolve to the supplied runtime function exports: {sorted(set(imports) - exports)}"
        )
    if elf.get_section_by_name(".gnu.hash") or elf.get_section_by_name(".relr.dyn"):
        raise ValueError("Unexpected modern dynamic table encoding")
    relocation_types = sorted(
        {
            r.entry.r_info_type
            for s in elf.iter_sections()
            if s.header.sh_type in {"SHT_REL", "SHT_RELA"}
            for r in s.iter_relocations()
        }
    )
    if not set(relocation_types) <= {2, 21, 22, 23}:
        raise ValueError(
            f"Unsupported ARM dynamic relocation types: {relocation_types}"
        )
    for segment in elf.iter_segments():
        if (
            segment.header.p_type in {"PT_LOAD", "PT_GNU_STACK"}
            and segment["p_flags"] & 3 == 3
        ):
            raise ValueError("Unexpected writable/executable segment")
    if kind == "probe":
        interpreters = [
            s.get_interp_name()
            for s in elf.iter_segments()
            if s.header.p_type == "PT_INTERP"
        ]
        if interpreters != ["/usr/lib/ldqnx.so.2"]:
            raise ValueError("Wrong target interpreter")
        if not {"_init_libc", "exit", "write"} <= set(imports):
            raise ValueError("Probe startup imports missing")
        if not any(
            s["p_type"] == "PT_LOAD"
            and s["p_flags"] & 1
            and s["p_vaddr"] <= elf.header.e_entry < s["p_vaddr"] + s["p_memsz"]
            for s in elf.iter_segments()
        ):
            raise ValueError("Entry is outside executable memory")
    return {
        "bytes": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
        "needed": needed,
        "imports": imports,
        "arm_relocations": relocation_types,
        "entry": hex(elf.header.e_entry),
    }


def build(libc, expected_digest, output, target, clang, lld):
    if target not in TARGETS or not re.fullmatch(r"[0-9a-f]{64}", expected_digest):
        raise ValueError("Explicit research target and lowercase SHA256 required")
    if os.path.lexists(output):
        raise ValueError("Output directory already exists; choose a new directory")
    if not libc.is_file() or libc.stat().st_size > MAX_ELF:
        raise ValueError("Invalid libc input")
    with libc.open("rb") as stream:
        original = stream.read(MAX_ELF + 1)
    if len(original) > MAX_ELF:
        raise ValueError("Runtime input grew beyond size limit")
    if hashlib.sha256(original).hexdigest() != expected_digest:
        raise ValueError("Target libc digest mismatch")
    _, _, exports = runtime_symbols(original)
    clang, lld = executable(clang), executable(lld)
    compiler_version, linker_version = (
        command([clang, "--version"]),
        command([lld, "--version"]),
    )
    if "clang" not in compiler_version.lower() or "LLD" not in linker_version:
        raise ValueError("Clang and the LLVM ELF linker are required")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(
        prefix=".obd-qnx66-", dir=output.parent
    ) as temporary:
        stage = Path(temporary)
        intermediate = stage / "link-inputs-not-for-deployment"
        intermediate.mkdir(mode=0o700)
        view = intermediate / "libc.so.3"
        view.write_bytes(link_view(original))
        native = ROOT / "native"
        source_paths = sorted(
            [path for directory in (native / "core", native / "qnx66")
             for path in directory.rglob("*") if path.suffix in {".c", ".h", ".S"}]
            + [Path(__file__).resolve(), ROOT / "tools/qnx66_elf.py", ROOT / "baselines.json"]
        )
        source_hashes = {path.relative_to(ROOT).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
                         for path in source_paths}
        (intermediate / "obd_baseline_generated.h").write_text(baseline_header(target))
        flags = [
            clang,
            "--target=armv7-none-eabi",
            "-march=armv7-a",
            "-marm",
            "-mfloat-abi=soft",
            "-ffreestanding",
            "-fno-builtin",
            "-fno-stack-protector",
            "-fno-unwind-tables",
            "-fno-asynchronous-unwind-tables",
            "-fno-exceptions",
            "-fPIC",
            "-Oz",
            "-Wall",
            "-Wextra",
            "-Werror",
            "-I",
            str(native / "qnx66/include"),
            "-I",
            str(native / "core"),
            "-I", str(intermediate),
        ]
        objects = {}
        for name, source in {
            "core": native / "core/lhf2.c",
            "trial": native / "core/trial.c",
            "sha256": native / "core/sha256.c",
            "baseline": native / "core/baseline.c",
            "baseline_platform": native / "qnx66/baseline_platform.c",
            "readonly": native / "qnx66/readonly.c",
            "entry": native / "qnx66/start.S",
            "startup": native / "qnx66/start.c",
            "probe": native / "qnx66/probe.c",
            "media": native / "qnx66/media.c",
            "media_load": native / "qnx66/media_load.c",
            "player": native / "qnx66/player.c",
            "aeabi": native / "qnx66/aeabi.c",
            "network": native / "qnx66/network.c",
            "io": native / "qnx66/io.c",
            "stream": native / "qnx66/stream.c",
            "inspect": native / "qnx66/inspect.c",
            "inspect_main": native / "qnx66/inspect_main.c",
        }.items():
            obj = intermediate / f"{name}.o"
            extra = [] if source.suffix == ".S" else ["-std=c99", "-pedantic"]
            if name == "io":
                # Speed optimization lowers constant nanosecond division inline;
                # size optimization can introduce an unavailable AEABI divide helper.
                extra += ["-O2"]
            command(
                flags
                + extra
                + [f'-DOBD_TARGET_ID="{target}"', "-c", str(source), "-o", str(obj)]
            )
            objects[name] = str(obj)
        link_flags = [
            lld,
            "-m",
            "armelf",
            "--hash-style=sysv",
            "--no-undefined",
            "--fatal-warnings",
            "--no-rosegment",
            "--build-id=sha1",
            "-z",
            "now",
            "-z",
            "noexecstack",
            "-z",
            "max-page-size=4096",
            "-z",
            "common-page-size=4096",
        ]
        library_name = "libopenbyddisplay-video.so.0"
        library = stage / library_name
        version_map = intermediate / "exports.map"
        version_map.write_text(
            "{ global: obd_crc32; obd_lhf2_*; obd_media_*; obd_stream_*; obd_trial_*; obd_platform_inspect; local: *; };\n"
        )
        command(
            link_flags
            + [
                "-shared",
                "--soname=" + library_name,
                "--version-script=" + str(version_map),
                objects["core"],
                objects["media"],
                objects["media_load"],
                objects["aeabi"],
                objects["network"],
                objects["io"],
                objects["stream"],
                objects["trial"],
                objects["inspect"],
                str(view),
                "-o",
                str(library),
            ]
        )
        probe_name = f"obd-qnx66-probe-{target}.elf"
        probe = stage / probe_name
        command(
            link_flags
            + [
                "-e",
                "_start",
                "--image-base=0x08048000",
                "--dynamic-linker=/usr/lib/ldqnx.so.2",
                objects["entry"],
                objects["startup"],
                objects["probe"],
                objects["core"],
                objects["sha256"],
                objects["aeabi"],
                str(view),
                "-o",
                str(probe),
            ]
        )
        # EF_ARM_HASENTRY is present in the reference's ARM executable headers.
        probe_data = bytearray(probe.read_bytes())
        struct.pack_into(
            "<I", probe_data, 36, struct.unpack_from("<I", probe_data, 36)[0] | 2
        )
        probe.write_bytes(probe_data)
        probe.chmod(0o755)
        player_name = f"obd-qnx66-player-{target}.elf"
        player = stage / player_name
        command(
            link_flags
            + [
                "-e",
                "_start",
                "--image-base=0x08048000",
                "--dynamic-linker=/usr/lib/ldqnx.so.2",
                objects["entry"],
                objects["startup"],
                objects["player"],
                objects["core"],
                objects["media"],
                objects["media_load"],
                objects["aeabi"],
                objects["network"],
                objects["io"],
                objects["stream"],
                objects["sha256"],
                objects["baseline"],
                objects["baseline_platform"],
                objects["readonly"],
                str(view),
                "-o",
                str(player),
            ]
        )
        player_data = bytearray(player.read_bytes())
        struct.pack_into(
            "<I", player_data, 36, struct.unpack_from("<I", player_data, 36)[0] | 2
        )
        player.write_bytes(player_data)
        player.chmod(0o755)
        inspect_name = f"obd-qnx66-inspect-{target}.elf"
        inspector = stage / inspect_name
        command(link_flags + [
            "-e", "_start", "--image-base=0x08048000",
            "--dynamic-linker=/usr/lib/ldqnx.so.2",
            objects["entry"], objects["startup"], objects["inspect_main"],
            objects["inspect"], objects["media_load"], objects["aeabi"],
            str(view), "-o", str(inspector),
        ])
        inspect_data = bytearray(inspector.read_bytes())
        struct.pack_into("<I", inspect_data, 36, struct.unpack_from("<I", inspect_data, 36)[0] | 2)
        inspector.write_bytes(inspect_data)
        inspector.chmod(0o755)
        report = {
            "format": 1,
            "target": target,
            "source_files_sha256": source_hashes,
            "runtime_libc_sha256": expected_digest,
            "compiler": compiler_version,
            "linker": linker_version,
            "artifacts": {
                probe_name: inspect(bytes(probe_data), exports, "probe"),
                library_name: inspect(library.read_bytes(), exports, "library"),
                player_name: inspect(bytes(player_data), exports, "probe"),
                inspect_name: inspect(bytes(inspect_data), exports, "probe"),
            },
            "sdk_used": False,
            "vendor_crt_copied": False,
            "qnx_execution_verified": False,
            "hardware_verified": False,
            "firmware_flashable": False,
            "vehicle_visual_verified": False,
            "startup_scope": "minimal C entry; no executable constructors, C++ or TLS objects; loaded media libraries and their threads require QNX verification",
            "link_inputs": "Local build-only views, not runtime replacement libraries; do not deploy or distribute.",
        }
        (stage / "build-report.json").write_text(json.dumps(report, indent=2) + "\n")
        # Only the original source remains authoritative; it is never modified.
        with libc.open("rb") as stream:
            final_input = stream.read(MAX_ELF + 1)
        if hashlib.sha256(final_input).hexdigest() != expected_digest:
            raise ValueError("Runtime input changed during build")
        if any(hashlib.sha256(path.read_bytes()).hexdigest() != source_hashes[path.relative_to(ROOT).as_posix()]
               for path in source_paths):
            raise ValueError("Native source changed during build")
        if os.path.lexists(output):
            raise ValueError("Output appeared during build; refusing replacement")
        os.rename(stage, output)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--libc", type=Path, required=True)
    parser.add_argument("--expected-libc-sha256", required=True)
    parser.add_argument("--target", choices=sorted(TARGETS), required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--clang", default="clang")
    parser.add_argument("--lld", default="ld.lld")
    args = parser.parse_args()
    try:
        report = build(
            args.libc,
            args.expected_libc_sha256,
            args.output,
            args.target,
            args.clang,
            args.lld,
        )
    except (ValueError, OSError, RuntimeError, ELFError, struct.error) as error:
        parser.exit(1, f"Build refused: {error}\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
