#!/usr/bin/env python3
"""Build the cartridge bus diagnostic ROM (see cartdiag.s for what it shows).

    sim/cartdiag.py cartdiag.gba
"""
import random
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from gba_microbench import elf_text, check_relocations  # noqa: E402

LOGO_SOURCE = HERE.parent.parent.parent / "suite" / "suite.gba"


def main():
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "cartdiag.gba")
    with tempfile.TemporaryDirectory() as tmp:
        obj = Path(tmp) / "cartdiag.o"
        subprocess.run(["clang", "-target", "armv4t-none-eabi", "-c", str(HERE / "cartdiag.s"), "-o", str(obj)],
                       check=True)
        check_relocations(obj)
        code = bytearray(elf_text(obj.read_bytes()))
    assert len(code) < 0x10000, "code too large"
    rom = bytearray(0x20000)
    rom[:len(code)] = code
    # Test data for the ROM checksum.
    rng = random.Random(1234)
    for off in range(0x10000, 0x20000, 4):
        struct.pack_into("<I", rom, off, rng.getrandbits(32))
    checksum = sum(struct.unpack_from("<16384I", rom, 0x10000)) & 0xFFFFFFFF
    marker = struct.pack("<I", 0xC0DEF00D)
    pos = rom.find(marker)
    assert pos >= 0 and rom.find(marker, pos + 1) < 0
    struct.pack_into("<I", rom, pos, checksum)
    # Header: Nintendo logo from an existing ROM, title, fixed byte, complement check.
    rom[0x04:0xA0] = LOGO_SOURCE.read_bytes()[0x04:0xA0]
    rom[0xA0:0xAC] = b"CARTDIAG".ljust(12, b"\0")
    rom[0xAC:0xB0] = b"CDIA"
    rom[0xB0:0xB2] = b"01"
    rom[0xB2] = 0x96
    rom[0xBD] = (-(sum(rom[0xA0:0xBD]) + 0x19)) & 0xFF
    out.write_bytes(rom)
    print(f"wrote {out} ({len(rom)} bytes), expected ROM checksum {checksum:08X}")


if __name__ == "__main__":
    main()
