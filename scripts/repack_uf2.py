#!/usr/bin/env python3
"""Repack a folder produced by unpack_uf2.py back into a flashable .uf2.

FAT-region directories are regenerated into a fresh FAT image via
ESP-IDF's fatfsgen.py, sized to match the original partition -- needs
IDF_PATH set (source ~/export-esp.sh, or point IDF_PATH at an esp-idf
checkout; see BUILD_NOTES.md). Raw regions are written back byte-for-byte
from their .bin file, which can be a different size than the original --
useful for swapping in a different-sized app image, but make sure it
still fits the target partition.

Matches the on-flash format the Game Bub DFU bootloader expects
(firmware/handheld-dfu/src/{uf2,virtual_disk}.rs): family_id 0xC47E5767
(ESP32-S3), 256-byte flash payload per 512-byte UF2 block, absolute SPI
flash offsets as block addresses. Same format as pack_uf2.py.

Usage:
    python3 repack_uf2.py extracted/ out.uf2
"""
import argparse
import json
import os
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

UF2_MAGIC_START0 = 0x0A324655
UF2_MAGIC_START1 = 0x9E5D5157
UF2_MAGIC_END = 0x0AB16F30
UF2_FLAG_FAMILY_ID_PRESENT = 0x00002000
FAMILY_ID_ESP32S3 = 0xC47E5767
FLASH_BLOCK_SIZE = 256


def build_fat_image(region_dir, size):
    idf_path = os.environ.get("IDF_PATH")
    if not idf_path:
        sys.exit("IDF_PATH not set -- source ~/export-esp.sh (or set IDF_PATH) to repack FAT regions")
    fatfsgen = Path(idf_path) / "components" / "fatfs" / "fatfsgen.py"
    if not fatfsgen.is_file():
        sys.exit(f"fatfsgen.py not found at {fatfsgen}")

    tmp_fd, tmp_path = tempfile.mkstemp(suffix=".fat.bin")
    os.close(tmp_fd)
    try:
        subprocess.run(
            [
                sys.executable, str(fatfsgen),
                "--output", tmp_path,
                "--partition_size", str(size),
                "--fat_count", "1",
                "--long_name_support",
                str(region_dir),
            ],
            check=True,
        )
        data = Path(tmp_path).read_bytes()
    finally:
        os.unlink(tmp_path)

    if len(data) != size:
        sys.exit(f"fatfsgen produced {len(data)} bytes, expected {size} for {region_dir}")
    return data


def build_chunks(regions):
    chunks = []
    for offset, data in regions:
        assert offset % FLASH_BLOCK_SIZE == 0, f"offset {hex(offset)} not 256-byte aligned"
        for i in range(0, len(data), FLASH_BLOCK_SIZE):
            chunks.append((offset + i, data[i:i + FLASH_BLOCK_SIZE]))
    return chunks


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("in_dir", help="folder produced by unpack_uf2.py (possibly edited)")
    ap.add_argument("out", help="output .uf2 path")
    args = ap.parse_args()

    in_dir = Path(args.in_dir)
    manifest_path = in_dir / "manifest.json"
    if not manifest_path.is_file():
        sys.exit(f"{manifest_path} not found -- {args.in_dir} doesn't look like an unpack_uf2.py output dir")
    manifest = json.loads(manifest_path.read_text())

    regions = []
    for entry in manifest["regions"]:
        offset = entry["offset"]
        if entry["kind"] == "raw":
            data = (in_dir / entry["file"]).read_bytes()
            print(f"  raw region {hex(offset)} <- {entry['file']} ({len(data)} bytes)")
        elif entry["kind"] == "fat":
            region_dir = in_dir / entry["dir"]
            data = build_fat_image(region_dir, entry["size"])
            print(f"  fat region {hex(offset)} <- {entry['dir']}/ ({len(data)} bytes)")
        else:
            sys.exit(f"unknown region kind: {entry['kind']!r}")
        regions.append((offset, data))

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

    print(f"\nWrote {total} blocks ({total * 512} bytes) to {args.out}")


if __name__ == "__main__":
    main()
