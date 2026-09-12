How to extract and pack a uf2 firmware image

  1. Extract the official UF2

  cd /Users/ghogan42/dev/Gamebub_Firmware
  python3 gamebub/scripts/unpack_uf2.py gamebub-rev4_v1.0.1.uf2 extracted/

  This creates extracted/ with:
  - factory_app.bin — the raw MCU firmware app image.
  - system_data/ — a folder of loose files pulled out of the FAT-formatted system_data partition: boot.bit.hs, gameboy.bit.hs,
  gba.bit.hs, gameboy.bios-dmg.bin, gameboy.bios-cgb.bin, gba.bios.bin, fw_cart_backup.bin.
  - manifest.json — records each region's flash offset/size so repack_uf2.py knows how to put it back together. Don't hand-edit
  this.

  (No dependencies needed for this step — it's pure Python, no mtools/disk mounting required.)

  2. Make your changes

  Swap out whatever you need directly inside extracted/:
  - Replace a bitstream: cp my-new-gba.bit.hs extracted/system_data/gba.bit.hs
  - Replace the firmware app: cp my-new-app.bin extracted/factory_app.bin (can be a different size than the original — just make
  sure it still fits the partition)
  - Add/replace any other file in extracted/system_data/

  3. Repack into a flashable UF2

  Repacking regenerates the FAT filesystem for system_data, which needs ESP-IDF's fatfsgen.py — so IDF_PATH must be set first:

  source ~/export-esp.sh   # sets up the ESP toolchain, including IDF_PATH
  python3 gamebub/scripts/repack_uf2.py extracted/ my-build.uf2

  If source ~/export-esp.sh doesn't set IDF_PATH for you, set it directly:
  export IDF_PATH="$HOME/.espressif/esp-idf/v5.4.3"

  This writes my-build.uf2 — same format as the original, ready to flash.

  4. Flash it

  Put the device in DFU mode (hold Home while powering on), it'll show up as a USB drive named "GAME BUB" — drag my-build.uf2
  onto it.
