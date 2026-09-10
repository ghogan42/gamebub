#!/usr/bin/env python3
"""Extract a Game Bub .uf2 firmware image into a folder of loose files, so
individual pieces (e.g. one bitstream, one BIOS file) can be swapped out
and the folder repacked into a new, flashable .uf2 with `repack_uf2.py`.

Understands two kinds of regions inside a .uf2 (grouped from contiguous
UF2 blocks):
  - "raw" byte ranges (e.g. the MCU app image) -> written out as a single
    .bin file.
  - FAT12/16-formatted regions (e.g. the system_data partition, which
    holds the FPGA bitstreams / BIOS files / cart backup as individual
    files) -> walked and each file extracted individually into a
    subdirectory.

No external dependencies beyond the standard library -- this implements a
minimal read-only FAT12/16 parser (including VFAT long filenames) so it
doesn't need `mtools` or OS-level disk mounting, neither of which reliably
handle this device's FAT image on every platform.

Limitations: root directory only (no subdirectories) -- matches the flat
layout ESP-IDF's fatfsgen.py produces, which is what this device's
system_data partition actually contains.

Usage:
    python3 unpack_uf2.py firmware.uf2 extracted/
"""
import argparse
import json
import struct
from pathlib import Path

UF2_MAGIC_START0 = 0x0A324655
UF2_MAGIC_START1 = 0x9E5D5157
UF2_MAGIC_END = 0x0AB16F30
BLOCK_SIZE = 512
HEADER_SIZE = 32

# Friendly names for known Game Bub partition offsets (see partitions.csv),
# purely cosmetic -- extraction/repacking works for arbitrary offsets too.
KNOWN_OFFSETS = {
    0x100000: "factory_app",
    0x600000: "system_data",
}


def read_uf2_blocks(path):
    data = Path(path).read_bytes()
    if len(data) % BLOCK_SIZE != 0:
        raise ValueError(f"{path}: size {len(data)} is not a multiple of {BLOCK_SIZE}")
    blocks = []
    for i in range(0, len(data), BLOCK_SIZE):
        block = data[i:i + BLOCK_SIZE]
        magic0, magic1, _flags, addr, payload_size, block_no, _total, _family = \
            struct.unpack("<8I", block[:HEADER_SIZE])
        if magic0 != UF2_MAGIC_START0 or magic1 != UF2_MAGIC_START1:
            raise ValueError(f"block {block_no}: bad start magic")
        end_magic, = struct.unpack("<I", block[-4:])
        if end_magic != UF2_MAGIC_END:
            raise ValueError(f"block {block_no}: bad end magic")
        payload = block[HEADER_SIZE:HEADER_SIZE + payload_size]
        blocks.append((addr, payload))
    return blocks


def group_regions(blocks):
    """Coalesce blocks into contiguous (offset, bytes) regions."""
    blocks = sorted(blocks, key=lambda b: b[0])
    regions = []
    cur_start = None
    cur_bytes = bytearray()
    prev_end = None
    for addr, payload in blocks:
        if prev_end is not None and addr == prev_end:
            cur_bytes += payload
        else:
            if cur_start is not None:
                regions.append((cur_start, bytes(cur_bytes)))
            cur_start = addr
            cur_bytes = bytearray(payload)
        prev_end = addr + len(payload)
    if cur_start is not None:
        regions.append((cur_start, bytes(cur_bytes)))
    return regions


def looks_like_fat(data):
    if len(data) < 512 or data[0] not in (0xEB, 0xE9):
        return False
    return data[54:57] == b"FAT"


class Fat1x:
    """Minimal read-only FAT12/16 reader (root directory only)."""

    def __init__(self, data):
        self.data = data
        bpb = data[:512]
        self.bytes_per_sector = struct.unpack_from("<H", bpb, 0x0B)[0]
        self.sectors_per_cluster = bpb[0x0D]
        self.reserved_sectors = struct.unpack_from("<H", bpb, 0x0E)[0]
        self.num_fats = bpb[0x10]
        self.root_entry_count = struct.unpack_from("<H", bpb, 0x11)[0]
        total_sectors_16 = struct.unpack_from("<H", bpb, 0x13)[0]
        self.fat_size = struct.unpack_from("<H", bpb, 0x16)[0]
        total_sectors_32 = struct.unpack_from("<I", bpb, 0x20)[0]
        self.total_sectors = total_sectors_16 or total_sectors_32

        self.fat_start = self.reserved_sectors
        self.root_dir_start = self.fat_start + self.num_fats * self.fat_size
        self.root_dir_sectors = (
            (self.root_entry_count * 32) + (self.bytes_per_sector - 1)
        ) // self.bytes_per_sector
        self.data_start = self.root_dir_start + self.root_dir_sectors

        data_sectors = self.total_sectors - self.data_start
        cluster_count = data_sectors // self.sectors_per_cluster
        self.fat_bits = 12 if cluster_count < 4085 else 16

    def _sectors(self, sector, count):
        start = sector * self.bytes_per_sector
        return self.data[start:start + count * self.bytes_per_sector]

    def _fat_entry(self, cluster):
        fat = self._sectors(self.fat_start, self.fat_size)
        if self.fat_bits == 16:
            return struct.unpack_from("<H", fat, cluster * 2)[0]
        # FAT12: 12-bit entries packed two per three bytes.
        offset = cluster + (cluster // 2)
        raw = struct.unpack_from("<H", fat, offset)[0]
        return (raw >> 4) if cluster % 2 else (raw & 0x0FFF)

    def _read_chain(self, start_cluster, size):
        out = bytearray()
        cluster = start_cluster
        eoc = 0xFF8 if self.fat_bits == 12 else 0xFFF8
        while 2 <= cluster < eoc and len(out) < size:
            sector = self.data_start + (cluster - 2) * self.sectors_per_cluster
            out += self._sectors(sector, self.sectors_per_cluster)
            cluster = self._fat_entry(cluster)
        return bytes(out[:size])

    def list_root_files(self):
        """Yields (filename, file_bytes) for each regular file in the root dir."""
        root = self._sectors(self.root_dir_start, self.root_dir_sectors)
        lfn_parts = []
        for i in range(0, len(root), 32):
            entry = root[i:i + 32]
            first = entry[0]
            if first == 0x00:
                break
            if first == 0xE5:
                lfn_parts = []
                continue
            attr = entry[11]
            if attr == 0x0F:
                # VFAT long-filename fragment; entries appear in reverse
                # (last part first), so just accumulate for now.
                chars = entry[1:11] + entry[14:26] + entry[28:32]
                part = chars.decode("utf-16-le", errors="ignore").split("\x00", 1)[0]
                part = part.rstrip("￿")
                lfn_parts.append(part)
                continue
            if attr & 0x08 or attr & 0x10:
                # Volume label or subdirectory -- not handled.
                lfn_parts = []
                continue
            short_name = entry[0:8].decode("ascii", errors="replace").rstrip()
            short_ext = entry[8:11].decode("ascii", errors="replace").rstrip()
            short = short_name + ("." + short_ext if short_ext else "")
            name = "".join(reversed(lfn_parts)) if lfn_parts else short
            lfn_parts = []
            first_cluster = struct.unpack_from("<H", entry, 0x1A)[0]
            size = struct.unpack_from("<I", entry, 0x1C)[0]
            file_bytes = self._read_chain(first_cluster, size) if size else b""
            yield name, file_bytes


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("uf2", help="input .uf2 file")
    ap.add_argument("out_dir", help="output directory (created if missing)")
    args = ap.parse_args()

    regions = group_regions(read_uf2_blocks(args.uf2))
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    manifest = {"source": str(args.uf2), "regions": []}

    for offset, data in regions:
        label = KNOWN_OFFSETS.get(offset, f"region_{offset:08x}")
        print(f"region {hex(offset)}..{hex(offset + len(data))} ({len(data)} bytes) -> {label}")

        if looks_like_fat(data):
            fat = Fat1x(data)
            region_dir = out_dir / label
            region_dir.mkdir(parents=True, exist_ok=True)
            for name, file_bytes in fat.list_root_files():
                (region_dir / name).write_bytes(file_bytes)
                print(f"  {name} ({len(file_bytes)} bytes)")
            manifest["regions"].append({
                "offset": offset,
                "size": len(data),
                "kind": "fat",
                "dir": label,
            })
        else:
            filename = f"{label}.bin"
            (out_dir / filename).write_bytes(data)
            manifest["regions"].append({
                "offset": offset,
                "size": len(data),
                "kind": "raw",
                "file": filename,
            })

    manifest_path = out_dir / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2))
    print(f"\nWrote {manifest_path}")
    print("Edit files in place, then repack with repack_uf2.py.")


if __name__ == "__main__":
    main()
