"""Build-only ELF32 views for sectionless target libraries, without an SDK.

Original runtime files are read-only inputs. Views are not deployment libraries.
"""

from __future__ import annotations

import io
import struct

from elftools.elf.elffile import ELFFile

MAX_ELF = 64 * 1024 * 1024
REQUIRED = {"_init_libc", "exit", "write", "memcmp"}


def elf32(data: bytes) -> ELFFile:
    if not 52 <= len(data) <= MAX_ELF:
        raise ValueError("ELF input size out of bounds")
    elf = ELFFile(io.BytesIO(data))
    if (
        elf.elfclass != 32
        or not elf.little_endian
        or elf.header.e_machine != "EM_ARM"
        or elf.header.e_type != "ET_DYN"
        or elf.header.e_flags & 0xFF000400 != 0x05000000
        or not 1 <= elf.num_segments() <= 64
        or elf.num_sections() > 512
    ):
        raise ValueError("Expected little-endian ARM EABI5 base-PCS shared library")
    return elf


def bounded(data: bytes, offset: int, size: int) -> bytes:
    if offset < 0 or size < 0 or offset > len(data) or size > len(data) - offset:
        raise ValueError("ELF range exceeds file")
    return data[offset : offset + size]


def dynamic(elf: ELFFile):
    segments = [s for s in elf.iter_segments() if s.header.p_type == "PT_DYNAMIC"]
    if len(segments) != 1:
        raise ValueError("Expected one PT_DYNAMIC segment")
    if not 8 <= segments[0]["p_filesz"] <= 65536 or segments[0]["p_filesz"] % 8:
        raise ValueError("Invalid dynamic segment size")
    return segments[0]


def layout(data: bytes):
    elf = elf32(data)
    segment = dynamic(elf)
    loads = [s.header for s in elf.iter_segments() if s.header.p_type == "PT_LOAD"]
    if not loads or len(loads) > 16:
        raise ValueError("Invalid target load segments")

    def file_offset(address, size):
        for load in loads:
            delta = address - load.p_vaddr
            if delta >= 0 and delta <= load.p_filesz and size <= load.p_filesz - delta:
                offset = load.p_offset + delta
                bounded(data, offset, size)
                return offset
        raise ValueError("Dynamic table is not file-backed")

    tags = {}
    names = {
        4: "DT_HASH",
        5: "DT_STRTAB",
        6: "DT_SYMTAB",
        10: "DT_STRSZ",
        11: "DT_SYMENT",
        14: "DT_SONAME",
        0x6FFFFEF5: "DT_GNU_HASH",
        0x6FFFFFF0: "DT_VERSYM",
        0x6FFFFFFC: "DT_VERDEF",
        0x6FFFFFFE: "DT_VERNEED",
    }
    records = bounded(data, segment["p_offset"], segment["p_filesz"])
    terminated = False
    for number, value in struct.iter_unpack("<II", records):
        if number == 0:
            terminated = True
            break
        name = names.get(number)
        if name in {"DT_VERSYM", "DT_VERDEF", "DT_VERNEED"}:
            raise ValueError("Versioned runtime symbols are not supported")
        if name in {
            "DT_HASH",
            "DT_GNU_HASH",
            "DT_SONAME",
            "DT_SYMTAB",
            "DT_STRTAB",
            "DT_STRSZ",
            "DT_SYMENT",
        }:
            if name in tags:
                raise ValueError("Duplicate dynamic table tag")
            tags[name] = value
    if not terminated:
        raise ValueError("PT_DYNAMIC has no bounded DT_NULL terminator")
    if not {"DT_SONAME", "DT_SYMTAB", "DT_STRTAB", "DT_STRSZ", "DT_SYMENT"} <= set(
        tags
    ):
        raise ValueError("Expected complete dynamic symbol tables")
    if tags["DT_SYMENT"] != 16 or not 1 <= tags["DT_STRSZ"] <= 4 * 1024 * 1024:
        raise ValueError("Invalid dynamic symbol/string size")
    if "DT_HASH" in tags:
        hash_offset = file_offset(tags["DT_HASH"], 8)
        buckets, count = struct.unpack_from("<II", data, hash_offset)
        if not 1 <= count <= 65536 or not 1 <= buckets <= 65536:
            raise ValueError("Invalid SysV hash dimensions")
        file_offset(tags["DT_HASH"], 8 + 4 * (buckets + count))
    elif "DT_GNU_HASH" in tags:
        address = tags["DT_GNU_HASH"]
        offset = file_offset(address, 16)
        buckets, first, bloom, _ = struct.unpack_from("<4I", data, offset)
        if (
            not 1 <= buckets <= 65536
            or not 1 <= bloom <= 65536
            or not 1 <= first <= 65536
        ):
            raise ValueError("Invalid GNU hash dimensions")
        bucket_address = address + 16 + 4 * bloom
        bucket_offset = file_offset(bucket_address, 4 * buckets)
        values = struct.unpack_from(f"<{buckets}I", data, bucket_offset)
        if any(v and not first <= v < 65536 for v in values):
            raise ValueError("Invalid GNU hash bucket")
        largest = max(values)
        count = first
        if largest:
            chain_address = bucket_address + 4 * buckets
            for index in range(largest, 65536):
                chain_offset = file_offset(chain_address + 4 * (index - first), 4)
                if struct.unpack_from("<I", data, chain_offset)[0] & 1:
                    count = index + 1
                    break
            else:
                raise ValueError("Unterminated GNU hash chain")
    else:
        raise ValueError("Runtime hash table missing")
    symbol_offset = file_offset(tags["DT_SYMTAB"], count * 16)
    string_offset = file_offset(tags["DT_STRTAB"], tags["DT_STRSZ"])
    strings = bounded(data, string_offset, tags["DT_STRSZ"])
    symbols = bytearray(bounded(data, symbol_offset, count * 16))
    for index in range(count):
        name = struct.unpack_from("<I", symbols, index * 16)[0]
        if name >= len(strings) or b"\0" not in strings[name:]:
            raise ValueError("Invalid dynamic symbol name")
    soname = tags["DT_SONAME"]
    if soname >= len(strings) or strings[soname:].split(b"\0", 1)[0] != b"libc.so.3":
        raise ValueError("Expected target libc.so.3 SONAME")
    return elf, segment, loads, tags, string_offset, strings, symbols


def runtime_symbols(data: bytes):
    elf, segment, _, _, _, strings, symbols = layout(data)
    exports = set()
    for index in range(len(symbols) // 16):
        name, _, _, info, visibility, section = struct.unpack_from(
            "<IIIBBH", symbols, index * 16
        )
        if (
            section
            and info & 15 == 2
            and info >> 4 in {1, 2}
            and visibility & 3 in {0, 3}
        ):
            exports.add(strings[name:].split(b"\0", 1)[0].decode("ascii"))
    if not REQUIRED <= exports:
        raise ValueError("Target libc is missing probe ABI exports")
    return elf, segment, exports


def link_view(data: bytes) -> bytes:
    """Append a section view; preserve original program bytes except ELF sh* fields.

    Symbols point at the corresponding original PT_LOAD region. No fake function
    bodies, target code patches, authentication changes or copied CRT are added.
    """
    runtime_symbols(data)
    elf, segment, loads, tags, string_offset, strings, symbols = layout(data)
    if elf.get_section_by_name(".dynsym") is not None:
        return data
    count = len(symbols) // 16

    # sh_name/type/flags/address/file-offset/size/link/info/alignment/entry-size.
    sections = [("", 0, 0, 0, 0, 0, 0, 0, 0, 0)]
    regions = []
    for index, load in enumerate(loads):
        if load.p_filesz > load.p_memsz:
            raise ValueError("Load file size exceeds memory size")
        bounded(data, load.p_offset, load.p_filesz)
        flags = 2 | (1 if load.p_flags & 2 else 0) | (4 if load.p_flags & 1 else 0)
        for kind, start, size, offset in (
            (1, load.p_vaddr, load.p_filesz, load.p_offset),
            (
                8,
                load.p_vaddr + load.p_filesz,
                load.p_memsz - load.p_filesz,
                load.p_offset + load.p_filesz,
            ),
        ):
            if size:
                regions.append((start, start + size, len(sections)))
                sections.append(
                    (
                        f".target{index}_{kind}",
                        kind,
                        flags,
                        start,
                        offset,
                        size,
                        0,
                        0,
                        1,
                        0,
                    )
                )
    for index in range(count):
        name, value, _, info, _, section = struct.unpack_from(
            "<IIIBBH", symbols, index * 16
        )
        if name >= len(strings) or b"\0" not in strings[name:]:
            raise ValueError("Invalid dynamic symbol name")
        if section == 0 or section >= 0xFF00:
            continue
        if info & 15 == 6:
            raise ValueError("TLS symbol remapping is not implemented")
        address = value & ~1 if info & 15 == 2 else value
        matches = [n for start, end, n in regions if start <= address < end]
        if not matches:
            # Linker-defined end symbols may be exactly at a load region's end.
            matches = [n for _, end, n in regions if address == end]
        if len(matches) != 1:
            raise ValueError("Defined symbol has no unambiguous load region")
        struct.pack_into("<H", symbols, index * 16 + 14, matches[0])

    result = bytearray(data)
    result += b"\0" * (-len(result) % 4)
    shadow_offset = len(result)
    result += symbols
    first_global = next((i for i in range(count) if symbols[i * 16 + 12] >> 4), count)
    if any(not symbols[i * 16 + 12] >> 4 for i in range(first_global, count)):
        raise ValueError("Local symbols occur after global symbols")
    string_index = len(sections)
    sections.append(
        (".dynstr", 3, 2, tags["DT_STRTAB"], string_offset, len(strings), 0, 0, 1, 0)
    )
    sections.append(
        (
            ".dynsym",
            11,
            0,
            0,
            shadow_offset,
            len(symbols),
            string_index,
            first_global,
            4,
            16,
        )
    )
    sections.append(
        (
            ".dynamic",
            6,
            3,
            segment["p_vaddr"],
            segment["p_offset"],
            segment["p_filesz"],
            string_index,
            0,
            4,
            8,
        )
    )
    names = bytearray(b"\0")
    name_offsets = {"": 0}
    for name in [s[0] for s in sections[1:]] + [".shstrtab"]:
        name_offsets[name] = len(names)
        names += name.encode("ascii") + b"\0"
    name_index = len(sections)
    sections.append((".shstrtab", 3, 0, 0, len(result), len(names), 0, 0, 1, 0))
    result += names
    result += b"\0" * (-len(result) % 4)
    table_offset = len(result)
    for name, *fields in sections:
        result += struct.pack("<10I", name_offsets[name], *fields)
    struct.pack_into("<I", result, 32, table_offset)
    struct.pack_into("<HHH", result, 46, 40, len(sections), name_index)
    return bytes(result)
