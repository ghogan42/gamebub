@ Gamebub cartridge bus diagnostic ROM (for flash carts / physical cartridges).
@
@ Build a .gba with:  sim/cartdiag.py out.gba
@ Contains "FLASH1M_V103" so flash carts emulate a 128 KiB Flash save.
@
@ Screen (mode 3), one line per test, hex values:
@ ID lines show 3 bytes: device (1st read of 0E000001), maker (0E000000), device (2nd read).
@   line 0: Flash ID, commands from ROM code (exactly like Pokemon), WAITCNT=4017 (SRAM 8 waits)
@   line 1: Flash ID, commands from IWRAM code,                      WAITCNT=4017
@   line 2: Flash ID, commands from ROM code,                        WAITCNT=4014 (SRAM 4 waits)
@   line 3: Flash ID, commands from ROM code,                        WAITCNT=0003 (no prefetch)
@ (e.g. Macronix: 09 C2 09, Sanyo: 13 62 13, EZ-Flash Omega on a GBA: 1B 32 1B)
@   line 4: ROM checksum 0x08010000-0x0801FFFF, WAITCNT=0000 (reference)
@   line 5: ROM checksum, WAITCNT=4014 (prefetch, 3/1 waits)
@   line 6: ROM checksum, WAITCNT=4317
@   line 7: expected ROM checksum (computed when building the ROM)
@ A bar left of each line: green = matches expectation (ID line: 0xC2 maker or 0x62 maker), red = not.

.syntax unified
.arm
.text
.global _start
_start:
    b .Lreset
    .space 0xBC            @ header (filled in by cartdiag.py)
    @ Keep code out of 0xC4-0xC9 (cartridge GPIO/RTC registers on some carts).
    .space 0x140
.Lreset:
    @ IRQs off (BIOS leaves them off), stack in IWRAM
    ldr sp, =0x03007F00
    @ Copy IWRAM routines
.Lcopy_base:
    sub r0, pc, #8                                  @ r0 = .Lcopy_base
    ldr r1, =(.Liwram_code_start - .Lcopy_base)
    add r0, r0, r1
    ldr r1, =0x03000000
    ldr r2, =(.Liwram_code_end - .Liwram_code_start)
    add r2, r0, r2
1:  ldr r3, [r0], #4
    str r3, [r1], #4
    cmp r0, r2
    blo 1b
    @ Mode 3, BG2 on
    ldr r0, =0x04000000
    ldr r1, =0x0403
    strh r1, [r0]
    @ Clear screen (dark blue)
    ldr r0, =0x06000000
    ldr r1, =0x28002800
    mov r2, #(240*160*2/4)
2:  str r1, [r0], #4
    subs r2, r2, #1
    bne 2b

    @ ---- line 0: ID from ROM code, WAITCNT 4017
    ldr r0, =0x4017
    mov lr, pc
    b .Lset_waitcnt
    mov lr, pc
    b .Lflash_id_rom
    mov r1, #0
    mov lr, pc
    b .Lshow_id
    @ ---- line 1: ID from IWRAM code, WAITCNT 4017
    ldr r0, =0x4017
    mov lr, pc
    b .Lset_waitcnt
    ldr r3, =0x03000000 + (.Lflash_id_iwram - .Liwram_code_start)
    mov lr, pc
    bx r3
    mov r1, #1
    mov lr, pc
    b .Lshow_id
    @ ---- line 2: ID from ROM code, WAITCNT 4014
    ldr r0, =0x4014
    mov lr, pc
    b .Lset_waitcnt
    mov lr, pc
    b .Lflash_id_rom
    mov r1, #2
    mov lr, pc
    b .Lshow_id
    @ ---- line 3: ID from ROM code, WAITCNT 0003
    mov r0, #3
    mov lr, pc
    b .Lset_waitcnt
    mov lr, pc
    b .Lflash_id_rom
    mov r1, #3
    mov lr, pc
    b .Lshow_id
    @ ---- line 4-6: ROM checksums
    mov r0, #0
    mov lr, pc
    b .Lset_waitcnt
    mov lr, pc
    b .Lrom_checksum
    mov r6, r0              @ reference
    mov r1, #4
    ldr r2, .Lexpected_sum
    mov lr, pc
    b .Lshow_sum
    ldr r0, =0x4014
    mov lr, pc
    b .Lset_waitcnt
    mov lr, pc
    b .Lrom_checksum
    mov r1, #5
    ldr r2, .Lexpected_sum
    mov lr, pc
    b .Lshow_sum
    ldr r0, =0x4317
    mov lr, pc
    b .Lset_waitcnt
    mov lr, pc
    b .Lrom_checksum
    mov r1, #6
    ldr r2, .Lexpected_sum
    mov lr, pc
    b .Lshow_sum
    mov r0, #0
    mov lr, pc
    b .Lset_waitcnt
    ldr r0, .Lexpected_sum
    mov r1, #7
    ldr r2, .Lexpected_sum
    mov lr, pc
    b .Lshow_sum
3:  b 3b

.Lexpected_sum:
    .word 0xC0DEF00D        @ patched by cartdiag.py

@ r0 = WAITCNT value
.Lset_waitcnt:
    ldr r1, =0x04000204
    strh r0, [r1]
    bx lr

@ Flash ID with the command writes executed from ROM (Pokemon's ReadFlashId), the
@ two ID reads from IWRAM. Returns r0 = (byte1 << 8) | byte0.
.Lflash_id_rom:
    push {r4-r7, lr}
    ldr r4, =0x0E005555
    ldr r5, =0x0E002AAA
    mov r6, #0xAA
    mov r7, #0x55
    strb r6, [r4]
    strb r7, [r5]
    mov r0, #0x90
    strb r0, [r4]
    ldr r0, =20000
1:  subs r0, r0, #1
    bne 1b
    ldr r3, =0x03000000 + (.Lread_id_iwram - .Liwram_code_start)
    mov lr, pc
    bx r3
    mov r1, r0
    strb r6, [r4]
    strb r7, [r5]
    mov r0, #0xF0
    strb r0, [r4]
    strb r0, [r4]
    ldr r0, =20000
2:  subs r0, r0, #1
    bne 2b
    mov r0, r1
    pop {r4-r7, lr}
    bx lr

@ Checksum (sum of words) of ROM 0x08010000..0x0801FFFF. Returns r0.
.Lrom_checksum:
    ldr r1, =0x08010000
    ldr r2, =0x08020000
    mov r0, #0
1:  ldr r3, [r1], #4
    add r0, r0, r3
    cmp r1, r2
    blo 1b
    bx lr

@ .Lshow_id: r0 = id, r1 = line. Green bar if maker byte (r0 & 0xFF? low=byte0) is C2/62/32/BF/1F.
.Lshow_id:
    push {r4, r5, lr}
    mov r4, r0
    mov r5, r1
    lsr r2, r0, #8
    and r2, r2, #0xFF
    mov r3, #0
    cmp r2, #0xC2
    moveq r3, #1
    cmp r2, #0x62
    moveq r3, #1
    cmp r2, #0x32
    moveq r3, #1
    cmp r2, #0xBF
    moveq r3, #1
    cmp r2, #0x1F
    moveq r3, #1
    lsr r2, r0, #16            @ device bytes must agree
    and r12, r0, #0xFF
    cmp r2, r12
    movne r3, #0
    mov r0, r1
    mov r1, r3
    mov lr, pc
    b .Ldraw_bar
    @ print 6 hex digits: device (1st), maker, device (2nd)
    lsl r0, r4, #8
    mov r1, r5
    mov r2, #6
    pop {r4, r5, lr}
    b .Ldraw_hex

@ .Lshow_sum: r0 = value, r1 = line, r2 = expected
.Lshow_sum:
    push {r4, r5, lr}
    mov r4, r0
    mov r5, r1
    cmp r0, r2
    moveq r1, #1
    movne r1, #0
    mov r0, r5
    mov lr, pc
    b .Ldraw_bar
    mov r0, r4
    mov r1, r5
    mov r2, #8
    pop {r4, r5, lr}
    b .Ldraw_hex

@ .Ldraw_bar: r0 = line, r1 = ok
.Ldraw_bar:
    ldr r2, =0x03E0           @ green
    cmp r1, #0
    ldreq r2, =0x001F         @ red
    ldr r3, =(240*2*18)
    mul r3, r0, r3
    ldr r12, =0x06000000 + 240*2*8
    add r12, r12, r3
    mov r3, #8                @ rows
1:  mov r1, #0
2:  add r0, r12, r1, lsl #1
    strh r2, [r0]
    add r1, r1, #1
    cmp r1, #10
    blo 2b
    add r12, r12, #480
    subs r3, r3, #1
    bne 1b
    bx lr

@ .Ldraw_hex: r0 = value (top-aligned: first digit in bits 31-28), r1 = line, r2 = digits
.Ldraw_hex:
    push {r4-r8, lr}
    mov r4, r0
    mov r5, r2
    ldr r3, =(240*2*18)
    mul r6, r1, r3
    ldr r7, =0x06000000 + 240*2*8 + 16*2
    add r6, r6, r7            @ top-left of first char
1:  lsr r0, r4, #28
    lsl r4, r4, #4
    adr r1, .Lfont
    ldr r1, [r1, r0, lsl #2]  @ 4x7 glyph, 28 bits, row-major from bit 27
    mov r8, #0                @ row
2:  mov r2, #0                @ col
3:  mov r3, #27
    sub r3, r3, r8, lsl #2
    sub r3, r3, r2
    mov r0, r1, lsr r3
    tst r0, #1
    ldrne r0, =0x7FFF
    ldreq r0, =0x2800
    @ 2x scale
    add r3, r6, r2, lsl #2
    mov r7, #960
    mla r3, r8, r7, r3
    strh r0, [r3]
    strh r0, [r3, #2]
    add r3, r3, #480
    strh r0, [r3]
    strh r0, [r3, #2]
    add r2, r2, #1
    cmp r2, #4
    blo 3b
    add r8, r8, #1
    cmp r8, #7
    blo 2b
    add r6, r6, #(5*2*2)      @ next char (4px + 1 gap, 2x)
    subs r5, r5, #1
    bne 1b
    pop {r4-r8, lr}
    bx lr

@ 4x7 hex .Lfont, 28 bits per glyph (row 0 = bits 27..24)
.Lfont:
    .word 0b0110100110011001100110010110 @ 0
    .word 0b0010011000100010001000100111 @ 1
    .word 0b0110100100010010010010001111 @ 2
    .word 0b0110100100010110000110010110 @ 3
    .word 0b0001001101011001111100010001 @ 4
    .word 0b1111100011100001000110010110 @ 5
    .word 0b0110100010001110100110010110 @ 6
    .word 0b1111000100010010010001000100 @ 7
    .word 0b0110100110010110100110010110 @ 8
    .word 0b0110100110010111000100010110 @ 9
    .word 0b0110100110011111100110011001 @ A
    .word 0b1110100110011110100110011110 @ B
    .word 0b0110100110001000100010010110 @ C
    .word 0b1110100110011001100110011110 @ D
    .word 0b1111100010001110100010001111 @ E
    .word 0b1111100010001110100010001000 @ F

.ltorg
.balign 4
.Lsave_type_string:
    .ascii "FLASH1M_V103"
    .balign 4

@ ---------------- copied to IWRAM 0x03000000 ----------------
.balign 4
.Liwram_code_start:
@ returns r0 = (ID byte at 0x0E000001 << 8) | byte at 0x0E000000
.Lread_id_iwram:
    ldr r2, .Liw_flash0
    ldrb r0, [r2, #1]
    ldrb r3, [r2]
    ldrb r1, [r2, #1]
    orr r0, r3, r0, lsl #8
    orr r0, r1, r0, lsl #8
    bx lr
@ Whole ID sequence from IWRAM (no ROM fetches in between)
.Lflash_id_iwram:
    push {r4-r7}
    ldr r4, .Liw_5555
    ldr r5, .Liw_2AAA
    mov r6, #0xAA
    mov r7, #0x55
    strb r6, [r4]
    strb r7, [r5]
    mov r0, #0x90
    strb r0, [r4]
    ldr r0, .Liw_delay
1:  subs r0, r0, #1
    bne 1b
    ldr r2, .Liw_flash0
    ldrb r1, [r2, #1]
    ldrb r3, [r2]
    orr r1, r3, r1, lsl #8
    ldrb r3, [r2, #1]
    orr r1, r3, r1, lsl #8
    strb r6, [r4]
    strb r7, [r5]
    mov r0, #0xF0
    strb r0, [r4]
    strb r0, [r4]
    ldr r0, .Liw_delay
2:  subs r0, r0, #1
    bne 2b
    mov r0, r1
    pop {r4-r7}
    bx lr
.Liw_flash0: .word 0x0E000000
.Liw_5555:   .word 0x0E005555
.Liw_2AAA:   .word 0x0E002AAA
.Liw_delay:  .word 20000
.balign 4
.Liwram_code_end:
