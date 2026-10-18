"""Synthetic ELF fixtures and compiled ARM instructions; no vendor files in CI."""

import hashlib
import io
import json
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from qnx66_build import build
from qnx66_elf import link_view, runtime_symbols
from arm_model import probe_model
from elftools.elf.elffile import ELFFile


class ToolchainTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.clang, cls.lld = shutil.which("clang"), shutil.which("ld.lld")
        if not cls.clang or not cls.lld:
            raise RuntimeError("Clang and ld.lld required; tests may not silently skip")
        cls.temporary = tempfile.TemporaryDirectory(prefix="obd-toolchain-test-")
        cls.root = Path(cls.temporary.name)
        source = cls.root / "synthetic.c"
        source.write_text(
            "void _init_libc(void) {}\nvoid exit(int x) { (void)x; for(;;){} }\n"
            "int write(int f,const void*p,unsigned n) {(void)f;(void)p;return (int)n;}\n"
            "int memcmp(const void*a,const void*b,unsigned n) {(void)a;(void)b;(void)n;return 0;}\n"
            "int fixture_bss;\n"
        )
        obj, library = cls.root / "fixture.o", cls.root / "full-libc.so.3"
        subprocess.run(
            [
                cls.clang,
                "--target=armv7-none-eabi",
                "-mfloat-abi=soft",
                "-ffreestanding",
                "-fno-builtin",
                "-fPIC",
                "-c",
                str(source),
                "-o",
                str(obj),
            ],
            check=True,
            capture_output=True,
            timeout=30,
        )
        subprocess.run(
            [
                cls.lld,
                "-m",
                "armelf",
                "--hash-style=gnu",
                "-shared",
                "--soname=libc.so.3",
                str(obj),
                "-o",
                str(library),
            ],
            check=True,
            capture_output=True,
            timeout=30,
        )
        cls.full = library.read_bytes()
        elf = ELFFile(io.BytesIO(cls.full))
        # Keep a harmless metadata section and shstrtab, matching the reference's
        # partially stripped condition, rather than just zeroing section headers.
        headers = [bytes(40)]
        for wanted in (".comment", ".shstrtab"):
            index = next(
                i for i, s in enumerate(elf.iter_sections()) if s.name == wanted
            )
            offset = elf.header.e_shoff + index * 40
            headers.append(cls.full[offset : offset + 40])
        stripped = bytearray(cls.full)
        offset = len(stripped) + (-len(stripped) % 4)
        stripped += b"\0" * (-len(stripped) % 4) + b"".join(headers)
        struct.pack_into("<I", stripped, 32, offset)
        struct.pack_into("<HHH", stripped, 46, 40, 3, 2)
        cls.original = bytes(stripped)
        cls.libc = cls.root / "synthetic-libc.so.3"
        cls.libc.write_bytes(cls.original)
        cls.digest = hashlib.sha256(cls.original).hexdigest()

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def test_link_view_keeps_input_and_load_bytes(self):
        result = link_view(self.original)
        self.assertEqual(self.libc.read_bytes(), self.original)
        # Only the four section-view metadata fields in the ELF header change.
        before, after = (
            bytearray(self.original),
            bytearray(result[: len(self.original)]),
        )
        for offset, size in ((32, 4), (46, 6)):
            before[offset : offset + size] = after[offset : offset + size] = bytes(size)
        self.assertEqual(before, after)
        view = ELFFile(io.BytesIO(result))
        self.assertIsNotNone(view.get_section_by_name(".dynsym"))
        self.assertEqual(runtime_symbols(self.original)[2], runtime_symbols(result)[2])
        self.assertEqual(link_view(self.full), self.full)

    def test_wrong_machine_or_float_abi_rejected(self):
        for offset, value in ((18, 62), (36, 0x05000400)):
            damaged = bytearray(self.original)
            struct.pack_into("<H" if offset == 18 else "<I", damaged, offset, value)
            with self.assertRaises(ValueError):
                link_view(bytes(damaged))

    def test_wrong_dynamic_table_or_missing_export_rejected(self):
        elf = ELFFile(io.BytesIO(self.original))
        dynamic = next(s for s in elf.iter_segments() if s["p_type"] == "PT_DYNAMIC")
        for tag_name, bad_value in (("DT_STRSZ", 0xFFFFFFFF), ("DT_SYMENT", 8)):
            damaged = bytearray(self.original)
            index = next(
                i
                for i, t in enumerate(dynamic.iter_tags())
                if t.entry.d_tag == tag_name
            )
            struct.pack_into(
                "<I", damaged, dynamic["p_offset"] + index * 8 + 4, bad_value
            )
            with self.assertRaises(ValueError):
                link_view(bytes(damaged))
        damaged = self.original.replace(b"_init_libc\0", b"_xxxx_libc\0")
        with self.assertRaises(ValueError):
            link_view(damaged)

    def test_dynamic_segment_cannot_read_past_declared_size(self):
        elf = ELFFile(io.BytesIO(self.original))
        index = next(
            i for i, s in enumerate(elf.iter_segments()) if s["p_type"] == "PT_DYNAMIC"
        )
        damaged = bytearray(self.original)
        struct.pack_into(
            "<I", damaged, elf.header.e_phoff + index * elf.header.e_phentsize + 16, 8
        )
        with self.assertRaisesRegex(ValueError, "bounded DT_NULL"):
            runtime_symbols(bytes(damaged))
        with self.assertRaisesRegex(ValueError, "bounded DT_NULL"):
            link_view(bytes(damaged))

    def test_refusal_does_not_publish_or_replace(self):
        missing = self.root / "refused"
        with self.assertRaises(ValueError):
            build(
                self.libc, "0" * 64, missing, "song-plus-dmi-2021", self.clang, self.lld
            )
        self.assertFalse(missing.exists())
        existing = self.root / "existing"
        existing.mkdir()
        marker = existing / "preserve"
        marker.write_text("existing")
        with self.assertRaises(ValueError):
            build(
                self.libc,
                self.digest,
                existing,
                "song-plus-dmi-2021",
                self.clang,
                self.lld,
            )
        self.assertEqual(marker.read_text(), "existing")

    def test_link_and_execute_arm_in_cpu_only_model(self):
        for target in ("song-plus-dmi-2021", "tang-plus-dmi-2021"):
            output = self.root / target
            report = build(self.libc, self.digest, output, target, self.clang, self.lld)
            self.assertFalse(report["sdk_used"])
            self.assertFalse(report["qnx_execution_verified"])
            self.assertFalse(report["firmware_flashable"])
            self.assertEqual(
                report, json.loads((output / "build-report.json").read_text())
            )
            probe = output / f"obd-qnx66-probe-{target}.elf"
            for misaligned in (False, True):
                result = probe_model(probe, misaligned_stack=misaligned)
                self.assertEqual(result["exit"], 0)
                self.assertEqual(result["json"]["target"], target)
                self.assertTrue(result["json"]["crc32_ok"])
                self.assertTrue(result["json"]["lhf2_header_ok"])
                self.assertFalse(result["json"]["hardware_verified"])
                self.assertFalse(result["json"]["vehicle_visual_verified"])
            self.assertEqual(probe_model(probe, write_failure=True)["exit"], 12)
        self.assertEqual(self.libc.read_bytes(), self.original)


if __name__ == "__main__":
    unittest.main()
