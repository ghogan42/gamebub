#!/usr/bin/env python3
"""Run a tiny hand-written GBA program on the Verilator sim, optionally traced.

Assembles ARM/Thumb code with Apple clang (-target armv4t-none-eabi), places it
at 0x08000000, and boots it with a stub BIOS that jumps straight to the ROM (no
BIOS intro). The program should write its results as words to 0x03000000+
(IWRAM); they are printed after the run.

The program is wrapped as:
    _start:                   (ARM, supervisor mode, IRQs off)
        <your code>
    done: b done

Useful macros available in the snippet (mGBA suite style):
    START   ldr r0,=TM0CNT_LO ; ldr r1,=0x800000 ; str r1,[r0]
    END     ldrh r2,[r0] ; strh r1,[r0,#2] ; movs r0,r2     (cycles in r0; ARM or Thumb)

Example:
    cat > /tmp/stm.s <<'EOF'
        ldr r0, =0x04000204 ; ldr r1, =0x4000 ; strh r1, [r0]  @ WAITCNT: prefetch
        ldr sp, =0x03007F00
        ldr r7, =0x03000000
        START
        stmia sp, {r2}
        END
        str r0, [r7]
    EOF
    sim/gba_microbench.py /tmp/stm.s
    sim/gba_microbench.py /tmp/stm.s --trace --trace-levels cart.prefetch:debug > trace.txt

--trace uses a separate sim build (build/sim-gba-trace) generated with
LOG_LEVELS; it is rebuilt when --trace-levels or the sources change. Lines are prefixed by
"---- cycle N" markers (sim.cycle logger).
"""

import argparse
import os
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

FPGA = Path(__file__).resolve().parent.parent
SIM = FPGA / "build/sim-gba/VSimGba"
TRACE_ROOT = FPGA / "build/sim-gba-trace"
SDKROOT = "/Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk"

PRELUDE = """
.syntax unified
.macro START
    ldr r0, =0x04000100
    ldr r1, =0x800000
    str r1, [r0]
.endm
.macro END
    ldrh r2, [r0]
    strh r1, [r0, #2]
    movs r0, r2
.endm
.arm
.text
.global _start
_start:
    b program
    @ Keep the program out of the first 256 bytes (GPIO range: slightly different cart timing).
    .space 0x200
program:
"""
EPILOGUE = """
.arm
.balign 4
done_forever:
    b done_forever
.ltorg
"""


def elf_text(obj: bytes) -> bytes:
    """Extract the .text section from a 32-bit little-endian ELF object."""
    assert obj[:4] == b"\x7fELF" and obj[4] == 1 and obj[5] == 1, "expected ELF32 LE"
    e_shoff, = struct.unpack_from("<I", obj, 0x20)
    e_shentsize, e_shnum, e_shstrndx = struct.unpack_from("<HHH", obj, 0x2E)
    sections = [struct.unpack_from("<IIIIIIIIII", obj, e_shoff + i * e_shentsize) for i in range(e_shnum)]
    strtab = sections[e_shstrndx]
    names_off = strtab[4]
    for sh in sections:
        name = obj[names_off + sh[0]:].split(b"\0", 1)[0]
        if name == b".text":
            return obj[sh[4]:sh[4] + sh[5]]
    sys.exit("ERROR: no .text section")


def check_relocations(obj_path: Path):
    out = subprocess.run(["xcrun", "llvm-objdump", "-r", str(obj_path)], capture_output=True, text=True).stdout
    relocs = [l for l in out.splitlines() if l.strip() and l.strip()[0].isdigit()]
    if relocs:
        sys.exit("ERROR: program has relocations (use only local labels):\n" + "\n".join(relocs))


def assemble(source: str, work: Path) -> bytes:
    asm = work / "prog.s"
    obj = work / "prog.o"
    asm.write_text(PRELUDE + source + "\n" + EPILOGUE)
    subprocess.run(["clang", "-target", "armv4t-none-eabi", "-c", str(asm), "-o", str(obj)], check=True)
    check_relocations(obj)
    return elf_text(obj.read_bytes())


def build_trace_sim(levels: str):
    stamp = TRACE_ROOT / "log_levels.txt"
    binary = TRACE_ROOT / "VSimGba"
    sources = list((FPGA / "src").rglob("*.scala")) + list((FPGA / "sim").glob("**/*.[ch]pp"))
    up_to_date = binary.exists() and all(p.stat().st_mtime < binary.stat().st_mtime for p in sources)
    if up_to_date and stamp.exists() and stamp.read_text() == levels:
        return binary
    print(f"Building trace sim with LOG_LEVELS={levels} ...", file=sys.stderr)
    env = dict(os.environ, LOG_LEVELS=levels, SDKROOT=SDKROOT)
    subprocess.run([sys.executable, str(FPGA / "scripts/build_sim.py"), "gba", "--build-root", str(TRACE_ROOT)],
                   env=env, check=True, stdout=subprocess.DEVNULL)
    stamp.write_text(levels)
    return binary


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("source", type=Path, help="assembly snippet")
    parser.add_argument("--frames", type=int, default=1, help="frames to run (default 1 = 280896 cycles)")
    parser.add_argument("--results", type=int, default=8, help="number of result words to print")
    parser.add_argument("--trace", action="store_true", help="use the trace build; log goes to stdout")
    parser.add_argument("--trace-levels", default="sim.cycle:debug,cart.prefetch:debug,bus:debug")
    parser.add_argument("--sim", type=Path, default=None)
    args = parser.parse_args()

    sim = args.sim or (build_trace_sim(args.trace_levels) if args.trace else SIM)
    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)
        rom = assemble(args.source.read_text(), work)
        (work / "prog.gba").write_bytes(rom + b"\0" * (-len(rom) % 4))
        bios = bytearray(16 * 1024)
        struct.pack_into("<I", bios, 0, 0xE3A0F302)  # mov pc, #0x08000000
        (work / "bios.bin").write_bytes(bios)
        (work / "script.txt").write_text(f"wait {args.frames}\ndump iwram iwram.bin\nquit\n")
        proc = subprocess.run(
            [str(sim), "--bios-path", str(work / "bios.bin"), "--headless",
             "--script", str(work / "script.txt"), str(work / "prog.gba")],
            # Logger output goes to stderr.
            stdout=subprocess.DEVNULL,
            stderr=sys.stdout if args.trace else subprocess.DEVNULL, text=True)
        if proc.returncode:
            sys.exit(f"sim failed ({proc.returncode})")
        iwram = (work / "iwram.bin").read_bytes()
        words = struct.unpack_from(f"<{args.results}I", iwram, 0)
        print("results:", " ".join(f"{w}" for w in words), file=sys.stderr)


if __name__ == "__main__":
    main()
