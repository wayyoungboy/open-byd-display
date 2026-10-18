"""CPU-only ABI model. QNX loader, libc initialization and OS calls are NOT run."""

import io
import json
import struct

from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM, UC_HOOK_CODE, UC_MODE_ARM
from unicorn.arm_const import (
    UC_ARM_REG_LR,
    UC_ARM_REG_PC,
    UC_ARM_REG_R0,
    UC_ARM_REG_R1,
    UC_ARM_REG_R2,
    UC_ARM_REG_R3,
    UC_ARM_REG_SP,
)


def probe_model(path, *, misaligned_stack=True, partial_write=7, write_failure=False):
    elf = ELFFile(io.BytesIO(path.read_bytes()))
    cpu = Uc(UC_ARCH_ARM, UC_MODE_ARM)
    loads = [s for s in elf.iter_segments() if s["p_type"] == "PT_LOAD"]
    bottom = min(s["p_vaddr"] for s in loads) & ~4095
    top = (max(s["p_vaddr"] + s["p_memsz"] for s in loads) + 4095) & ~4095
    cpu.mem_map(bottom, top - bottom)
    for segment in loads:
        cpu.mem_write(segment["p_vaddr"], segment.data())
    cpu.mem_map(0x70000000, 65536)
    cpu.mem_map(0x71000000, 4096)
    stack = 0x70008000 + (4 if misaligned_stack else 0)
    argv, envp = stack + 4, stack + 16
    auxv, callback = stack + 24, 0x71000F00
    cpu.mem_write(
        stack, struct.pack("<8I", 2, 0x70000100, 0x70000120, 0, 0x70000140, 0, 0, 0)
    )
    cpu.mem_write(0x70000100, b"probe\0")
    cpu.mem_write(0x70000120, b"--self-test\0")
    cpu.mem_write(0x70000140, b"OBD_TEST=1\0")
    symbols = elf.get_section_by_name(".dynsym")
    callbacks = {}
    for section in elf.iter_sections():
        if section.header.sh_type != "SHT_REL":
            continue
        for relocation in section.iter_relocations():
            if relocation.entry.r_info_type != 22:
                raise RuntimeError("Model only implements ARM JUMP_SLOT")
            name = symbols.get_symbol(relocation.entry.r_info_sym).name
            address = 0x71000000 + len(callbacks) * 16
            cpu.mem_write(address, struct.pack("<I", 0xE12FFF1E))  # bx lr
            cpu.mem_write(relocation.entry.r_offset, struct.pack("<I", address))
            callbacks[address] = name
    state = {"initialized": False, "exit": None, "output": bytearray()}

    def hook(machine, address, size, user_data):
        if address not in callbacks:
            return
        name = callbacks[address]
        args = [
            machine.reg_read(r)
            for r in (UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_R2, UC_ARM_REG_R3)
        ]
        sp = machine.reg_read(UC_ARM_REG_SP)
        if sp & 7:
            raise RuntimeError("Compiled external call used an unaligned stack")
        if name == "_init_libc":
            fifth = struct.unpack("<I", machine.mem_read(sp, 4))[0]
            if (
                args != [2, argv, envp, auxv]
                or fifth != callback
                or state["initialized"]
            ):
                raise RuntimeError("Incorrect initial-stack or loader callback ABI")
            state["initialized"] = True
            result = 0
        elif not state["initialized"]:
            raise RuntimeError("Called runtime before initialization")
        elif name == "memcmp":
            left = bytes(machine.mem_read(args[0], args[2]))
            right = bytes(machine.mem_read(args[1], args[2]))
            result = (left > right) - (left < right)
        elif name == "write":
            if args[0] != 1:
                raise RuntimeError("Probe wrote outside stdout")
            result = -1 if write_failure else min(args[2], partial_write)
            if result > 0:
                state["output"].extend(machine.mem_read(args[1], result))
        elif name == "exit":
            state["exit"] = args[0]
            machine.emu_stop()
            return
        else:
            raise RuntimeError(f"Unexpected import: {name}")
        machine.reg_write(UC_ARM_REG_R0, result & 0xFFFFFFFF)
        machine.reg_write(UC_ARM_REG_PC, machine.reg_read(UC_ARM_REG_LR))

    cpu.hook_add(UC_HOOK_CODE, hook)
    cpu.reg_write(UC_ARM_REG_SP, stack)
    cpu.reg_write(UC_ARM_REG_R0, callback)
    cpu.emu_start(elf.header.e_entry, 0, timeout=5_000_000, count=200000)
    if state["exit"] is None:
        raise RuntimeError("Probe did not exit within CPU model budget")
    if state["exit"] == 0:
        state["json"] = json.loads(state["output"])
    return state
