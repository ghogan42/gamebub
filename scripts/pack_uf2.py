#!/usr/bin/env python3
"""Pack one or more (flash-offset, file) regions into a single UF2 file.

Matches the format the Game Bub DFU bootloader expects
(firmware/handheld-dfu/src/{uf2,virtual_disk}.rs): family_id 0xC47E5767
(ESP32-S3), 256-byte flash payload per 512-byte UF2 block, block addresses
are absolute SPI flash offsets. Flash below 0x80000 is write-protected by
the device and will be silently ignored if included here.

Usage:
    python3 pack_uf2.py --out out.uf2 0x600000:system_data.fat.bin
    python3 pack_uf2.py --out out.uf2 0x100000:factory_app.bin 0x600000:system_data.fat.bin
"""
import argparse
import struct

UF2_MAGIC_START0 = 0x0A324655
UF2_MAGIC_START1 = 0x9E5D5157
UF2_MAGIC_END = 0x0AB16F30
UF2_FLAG_FAMILY_ID_PRESENT = 0x00002000
FAMILY_ID_ESP32S3 = 0xC47E5767
FLASH_BLOCK_SIZE = 256


def build_chunks(regions):
    chunks = []
    for offset, data in regions:
        assert offset % FLASH_BLOCK_SIZE == 0, f"offset {hex(offset)} not 256-byte aligned"
        for i in range(0, len(data), FLASH_BLOCK_SIZE):
            chunks.append((offset + i, data[i:i + FLASH_BLOCK_SIZE]))
    return chunks


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", required=True, help="output .uf2 path")
    ap.add_argument("region", nargs="+", help="offset:file pairs, e.g. 0x600000:system_data.fat.bin")
    args = ap.parse_args()

    regions = []
    for r in args.region:
        offset_str, path = r.split(":", 1)
        offset = int(offset_str, 0)
        with open(path, "rb") as f:
            data = f.read()
        regions.append((offset, data))
        print(f"  region {hex(offset)}..{hex(offset + len(data))} ({len(data)} bytes) from {path}")

    chunks = build_chunks(regions)
    total = len(chunks)

    with open(args.out, "wb") as out:
        for block_no, (addr, payload) in enumerate(chunks):
            payload = payload.ljust(FLASH_BLOCK_SIZE, b"\x00")
            block = struct.pack(
                "<8I",
                UF2_MAGIC_START0, UF2_MAGIC_START1,
                UF2_FLAG_FAMILY_ID_PRESENT,
                addr, FLASH_BLOCK_SIZE, block_no, total, FAMILY_ID_ESP32S3,
            )
            block += payload
            block += b"\x00" * (476 - FLASH_BLOCK_SIZE)
            block += struct.pack("<I", UF2_MAGIC_END)
            assert len(block) == 512
            out.write(block)

    print(f"Wrote {total} blocks ({total * 512} bytes) to {args.out}")


if __name__ == "__main__":
    main()
