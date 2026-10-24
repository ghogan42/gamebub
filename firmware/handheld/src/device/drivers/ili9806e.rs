use std::time::{Duration, Instant};
use thiserror::Error;

use embedded_hal::digital::OutputPin;
use embedded_hal::spi::SpiDevice;

/// Duration between successive calls to sleep in / sleep out.
const SLEEP_CHANGE_DELAY: Duration = Duration::from_millis(120);

#[derive(Debug, Error)]
pub enum Error {
    #[error("reset error")]
    ResetError,
    #[error("spi error")]
    SpiError,
}

pub struct ILI9806E<PinReset: OutputPin, Spi: SpiDevice> {
    pin_reset: PinReset,
    spi: Spi,
    last_sleep_change: Instant,
}

impl<PinReset, Spi> ILI9806E<PinReset, Spi>
where
    Spi: SpiDevice,
    PinReset: OutputPin,
{
    pub fn new(pin_reset: PinReset, spi: Spi) -> Self {
        Self {
            pin_reset,
            spi,
            last_sleep_change: Instant::now(),
        }
    }

    pub fn init(&mut self) -> Result<(), Error> {
        // Reset the display.
        self.pin_reset.set_high().map_err(|_| Error::ResetError)?;
        std::thread::sleep(Duration::from_millis(1));
        self.pin_reset.set_low().map_err(|_| Error::ResetError)?;
        std::thread::sleep(Duration::from_millis(5));
        self.pin_reset.set_high().map_err(|_| Error::ResetError)?;
        self.last_sleep_change = Instant::now();
        std::thread::sleep(Duration::from_millis(5));

        // Change to page 1
        self.write_cmd(0xFF, &[0xFF, 0x98, 0x06, 0x04, 0x01])?;
        // Output SDA
        self.write_cmd(0x08, &[0x10])?;
        // Display function control 1: DE mode
        self.write_cmd(0x20, &[0x00])?;
        // Display function control 2:
        // Set VSYNC/HSYNC active high (0b11xx)
        // Set data fetched at clock falling edge (0bxx1x)
        // Set data enable polarity active high (0bxxx1)
        self.write_cmd(0x21, &[0b1111])?;
        // Resolution control
        self.write_cmd(0x30, &[0x02])?;
        // Inversion setting: 2 dot
        self.write_cmd(0x31, &[0x02])?;

        // Vendor parameters
        // Source timing adjust
        self.write_cmd(0x60, &[0x07])?;
        self.write_cmd(0x61, &[0x06])?;
        self.write_cmd(0x62, &[0x06])?;
        self.write_cmd(0x63, &[0x04])?;
        // Power control
        self.write_cmd(0x40, &[0x14])?;
        self.write_cmd(0x41, &[0x33])?;
        self.write_cmd(0x42, &[0x01])?;
        self.write_cmd(0x43, &[0x09])?;
        self.write_cmd(0x44, &[0x06])?;
        self.write_cmd(0x45, &[0x0A])?;
        self.write_cmd(0x46, &[0x44])?;
        self.write_cmd(0x47, &[0x44])?;

        self.write_cmd(0x50, &[0x78])?;
        self.write_cmd(0x51, &[0x78])?;
        self.write_cmd(0x52, &[0x00])?;
        self.write_cmd(0x53, &[0x3A])?;
        self.write_cmd(0x54, &[0x00])?;
        self.write_cmd(0x55, &[0x3A])?;

        // LVD detect
        self.write_cmd(0x57, &[0x50])?;
        // Positive gamma control
        self.write_cmd(0xA0, &[0x00])?;
        self.write_cmd(0xA1, &[0x13])?;
        self.write_cmd(0xA2, &[0x19])?;
        self.write_cmd(0xA3, &[0x0C])?;
        self.write_cmd(0xA4, &[0x06])?;
        self.write_cmd(0xA5, &[0x0A])?;
        self.write_cmd(0xA6, &[0x06])?;
        self.write_cmd(0xA7, &[0x04])?;
        self.write_cmd(0xA8, &[0x09])?;
        self.write_cmd(0xA9, &[0x08])?;
        self.write_cmd(0xAA, &[0x12])?;
        self.write_cmd(0xAB, &[0x06])?;
        self.write_cmd(0xAC, &[0x0E])?;
        self.write_cmd(0xAD, &[0x0E])?;
        self.write_cmd(0xAE, &[0x09])?;
        self.write_cmd(0xAF, &[0x00])?;
        // Negative gamma correction
        self.write_cmd(0xC0, &[0x00])?;
        self.write_cmd(0xC1, &[0x0D])?;
        self.write_cmd(0xC2, &[0x18])?;
        self.write_cmd(0xC3, &[0x0D])?;
        self.write_cmd(0xC4, &[0x06])?;
        self.write_cmd(0xC5, &[0x09])?;
        self.write_cmd(0xC6, &[0x07])?;
        self.write_cmd(0xC7, &[0x05])?;
        self.write_cmd(0xC8, &[0x08])?;
        self.write_cmd(0xC9, &[0x0E])?;
        self.write_cmd(0xCA, &[0x12])?;
        self.write_cmd(0xCB, &[0x09])?;
        self.write_cmd(0xCC, &[0x0E])?;
        self.write_cmd(0xCD, &[0x0E])?;
        self.write_cmd(0xCE, &[0x08])?;
        self.write_cmd(0xCF, &[0x00])?;

        // Change to page 7
        self.write_cmd(0xFF, &[0xFF, 0x98, 0x06, 0x04, 0x07])?;
        self.write_cmd(0x17, &[0x32])?;
        self.write_cmd(0x18, &[0x1D])?;
        self.write_cmd(0x26, &[0xB2])?;
        self.write_cmd(0x02, &[0x77])?;
        self.write_cmd(0xE1, &[0x79])?;
        self.write_cmd(0xB3, &[0x10])?;

        // Change to page 6
        self.write_cmd(0xFF, &[0xFF, 0x98, 0x06, 0x04, 0x06])?;
        self.write_cmd(0x00, &[0x20])?;
        self.write_cmd(0x01, &[0x04])?;
        self.write_cmd(0x02, &[0x00])?;
        self.write_cmd(0x03, &[0x00])?;
        self.write_cmd(0x04, &[0x01])?;
        self.write_cmd(0x05, &[0x01])?;
        self.write_cmd(0x06, &[0x88])?;
        self.write_cmd(0x07, &[0x04])?;
        self.write_cmd(0x08, &[0x01])?;
        self.write_cmd(0x09, &[0x90])?;
        self.write_cmd(0x0A, &[0x03])?;
        self.write_cmd(0x0B, &[0x01])?;
        self.write_cmd(0x0C, &[0x01])?;
        self.write_cmd(0x0D, &[0x01])?;
        self.write_cmd(0x0E, &[0x00])?;
        self.write_cmd(0x0F, &[0x00])?;
        self.write_cmd(0x10, &[0x55])?;
        self.write_cmd(0x11, &[0x53])?;
        self.write_cmd(0x12, &[0x01])?;
        self.write_cmd(0x13, &[0x0D])?;
        self.write_cmd(0x14, &[0x0D])?;
        self.write_cmd(0x15, &[0x43])?;
        self.write_cmd(0x16, &[0x0B])?;
        self.write_cmd(0x17, &[0x00])?;
        self.write_cmd(0x18, &[0x00])?;
        self.write_cmd(0x19, &[0x00])?;
        self.write_cmd(0x1A, &[0x00])?;
        self.write_cmd(0x1B, &[0x00])?;
        self.write_cmd(0x1C, &[0x00])?;
        self.write_cmd(0x1D, &[0x00])?;
        self.write_cmd(0x20, &[0x01])?;
        self.write_cmd(0x21, &[0x23])?;
        self.write_cmd(0x22, &[0x45])?;
        self.write_cmd(0x23, &[0x67])?;
        self.write_cmd(0x24, &[0x01])?;
        self.write_cmd(0x25, &[0x23])?;
        self.write_cmd(0x26, &[0x45])?;
        self.write_cmd(0x27, &[0x67])?;
        self.write_cmd(0x30, &[0x02])?;
        self.write_cmd(0x31, &[0x22])?;
        self.write_cmd(0x32, &[0x11])?;
        self.write_cmd(0x33, &[0xAA])?;
        self.write_cmd(0x34, &[0xBB])?;
        self.write_cmd(0x35, &[0x66])?;
        self.write_cmd(0x36, &[0x00])?;
        self.write_cmd(0x37, &[0x22])?;
        self.write_cmd(0x38, &[0x22])?;
        self.write_cmd(0x39, &[0x22])?;
        self.write_cmd(0x3A, &[0x22])?;
        self.write_cmd(0x3B, &[0x22])?;
        self.write_cmd(0x3C, &[0x22])?;
        self.write_cmd(0x3D, &[0x22])?;
        self.write_cmd(0x3E, &[0x22])?;
        self.write_cmd(0x3F, &[0x22])?;
        self.write_cmd(0x40, &[0x22])?;
        self.write_cmd(0x52, &[0x10])?;
        self.write_cmd(0x53, &[0x12])?;
        self.write_cmd(0x54, &[0x13])?;

        // Change back to page 0 for normal commands
        self.write_cmd(0xFF, &[0xFF, 0x98, 0x06, 0x04, 0x00])?;
        // Display access control: BGR=0 GS=1
        self.write_cmd(0x36, &[0x01])?;
        // Interface pixel format: 24-bit
        self.write_cmd(0x3A, &[0x70])?;

        Ok(())
    }

    pub fn enter_sleep(&mut self) -> Result<(), Error> {
        let wait_time = SLEEP_CHANGE_DELAY.saturating_sub(self.last_sleep_change.elapsed());
        std::thread::sleep(wait_time);

        // Display off
        self.write_cmd(0x28, &[])?;

        // Sleep in
        self.write_cmd(0x10, &[])?;
        self.last_sleep_change = Instant::now();

        Ok(())
    }

    pub fn exit_sleep(&mut self) -> Result<(), Error> {
        let wait_time = SLEEP_CHANGE_DELAY.saturating_sub(self.last_sleep_change.elapsed());
        std::thread::sleep(wait_time);

        // Sleep out
        self.write_cmd(0x11, &[])?;
        self.last_sleep_change = Instant::now();

        // Must wait 5ms before sending commands after sleep out.
        std::thread::sleep(Duration::from_millis(5));

        // Display on
        self.write_cmd(0x29, &[])?;

        Ok(())
    }

    pub fn write_cmd(&mut self, cmd: u8, params: &[u8]) -> Result<(), Error> {
        // 9-bit spi: D/C bit followed by 8 bits MSB-first
        // Pack everything into bytes.
        let mut data = [0u8; 8];
        if 9 + (9 * params.len()) > data.len() * 8 {
            return Err(Error::SpiError);
        }
        data[0] = cmd >> 1;
        data[1] = (cmd & 1) << 7;
        let mut index: usize = 9;

        let mut push = |bit: u8| {
            data[index / 8] |= (bit & 1) << (7 - (index % 8));
            index += 1;
        };

        for &p in params {
            push(1);
            for i in 0..8 {
                push((p >> (7 - i)) & 1);
            }
        }

        let buf = &data[0..(index + 7) / 8];
        self.spi.write(buf).map_err(|_| Error::SpiError)
    }

    /// Set the LCD to be controlled by the FPGA.
    pub fn enable_fpga_control(&mut self) -> Result<(), Error> {
        // No-op
        Ok(())
    }

    /// Select a command page, per the `0xFF,[0xFF,0x98,0x06,0x04,page]`
    /// pattern used throughout `init()`.
    fn select_page(&mut self, page: u8) -> Result<(), Error> {
        self.write_cmd(0xFF, &[0xFF, 0x98, 0x06, 0x04, page])
    }

    /// Write a "Digital Gamma Control" table (macro: page 2, registers
    /// 0x00-0x3F) as a linear ramp: register `reg` gets an adjustment
    /// magnitude scaled by `reg / last_reg`, so point 0 is unadjusted and
    /// `last_reg` gets the full `r_mag`/`b_mag`. Magnitudes are 0..=7.
    ///
    /// Two facts about the nibble, measured on a rev4 panel, drive this
    /// shape:
    ///
    /// 1. It is **sign-magnitude**: bit 3 selects the direction and bits
    ///    2:0 the magnitude, so 0x0 and 0x8 both mean "no adjustment",
    ///    0x9..=0xF cut the channel and 0x7..=0x1 boost it. A nibble of 0
    ///    is therefore *not* a neutral value to ramp up from -- ramping
    ///    0->15 sweeps from max boost through neutral to max cut. This
    ///    writes `0x8 | magnitude` throughout.
    /// 2. It is a constant *code offset* of roughly 2 codes per magnitude
    ///    step, not a gain. A uniform table therefore hits a channel hard
    ///    in the shadows and barely at all at white. Holding the output
    ///    *ratio* constant -- which is what keeps the ramp neutral -- needs
    ///    an offset proportional to the code, and since register index is
    ///    proportional to code, that is a linear ramp of the magnitude.
    ///
    /// Per the datasheet's write-order flowchart, writing all registers
    /// 0x00..=last_reg in order -- ending on last_reg -- is what latches the
    /// table.
    fn write_gamma_ramp(
        &mut self,
        page: u8,
        last_reg: u8,
        r_mag: u8,
        b_mag: u8,
    ) -> Result<(), Error> {
        self.select_page(page)?;
        let scale = |mag: u8, reg: u8| {
            // Round to nearest rather than truncating.
            let n = ((mag & 0x7) as u16 * reg as u16 + last_reg as u16 / 2) / last_reg as u16;
            // Bit 3 = "adjust downward"; with magnitude 0 this is still neutral.
            0x8 | (n as u8 & 0x7)
        };
        for reg in 0..=last_reg {
            let value = (scale(r_mag, reg) << 4) | scale(b_mag, reg);
            self.write_cmd(reg, &[value])?;
        }
        Ok(())
    }

    /// Apply a color-temperature preset via the panel's "Digital 3 Gamma"
    /// feature (`En_3G`): independently adjusts the Red and Blue gamma
    /// curves relative to the shared base curve (which also drives Green,
    /// written once in `init()` via the Positive/Negative Gamma Control
    /// commands, 0xA0-0xAF/0xC0-0xCF).
    ///
    /// `preset`: 0 = Normal (feature disabled, matches default shipped
    /// behavior), 1 = Warm, 2 = Cool. Anything else falls back to Normal.
    ///
    /// Only the macro table (page 2, 64 points, coarse) is written -- the
    /// micro tables (pages 3/4, 256 points, meant for fine trims layered on
    /// top of macro's shape) are intentionally left untouched.
    ///
    /// Values are from spectrophotometer measurements of a rev4 panel.
    /// Each preset ramps one channel to the field's maximum magnitude and
    /// leaves the other neutral:
    ///
    /// - Warm cuts blue only. Red is deliberately left neutral: boosting it
    ///   compressed red near white (the panel runs out of range there)
    ///   while contributing nothing to the white point, since the
    ///   adjustment can't be realized at full drive.
    /// - Cool is the mirror image by symmetry, and is **not yet verified**
    ///   against measurements.
    ///
    /// Ramped to magnitude 7, this is a ~12% cut held constant across the
    /// whole ramp -- all the 3G feature can do without disturbing ramp
    /// neutrality. It moves white from ~16750K to ~13500K.
    pub fn set_color_temperature(&mut self, preset: i32) -> Result<(), Error> {
        struct Gamma3Preset {
            enable: bool,
            r: u8,
            b: u8,
        }
        let preset = match preset {
            1 => Gamma3Preset {
                enable: true,
                r: 0,
                b: 7,
            },
            2 => Gamma3Preset {
                enable: true,
                r: 7,
                b: 0,
            },
            _ => Gamma3Preset {
                enable: false,
                r: 0,
                b: 0,
            },
        };

        // Macro adjustment (page 2, 64 points, registers 0x00-0x3F).
        self.write_gamma_ramp(0x02, 0x3F, preset.r, preset.b)?;
        // Digital 3 Gamma Enable (D3GE), register 0x40, still page 2.
        self.write_cmd(0x40, &[preset.enable as u8])?;

        // Back to page 0 for normal commands, matching init()'s convention.
        self.select_page(0x00)?;

        Ok(())
    }

    /// Write the same byte (`r` nibble high, `b` nibble low) to every
    /// register 0x00..=`last_reg` of a gamma table page, in order, so the
    /// write ends on `last_reg` and latches (see `write_gamma_ramp`).
    fn write_gamma_uniform(&mut self, page: u8, last_reg: u8, r: u8, b: u8) -> Result<(), Error> {
        self.select_page(page)?;
        let value = ((r & 0xF) << 4) | (b & 0xF);
        for reg in 0..=last_reg {
            self.write_cmd(reg, &[value])?;
        }
        Ok(())
    }

    /// Measurement test only (Settings > Gamma Table Test): program the
    /// Digital 3 Gamma tables directly.
    ///
    /// `macro_mode`: 0 = 3G disabled, 1 = enabled with a neutral macro table
    /// (0x88 everywhere), 2 = enabled with the Warm macro ramp. `micro_r` and
    /// `micro_b` are raw nibbles (0x0..=0xF) written to all 256 micro points
    /// (pages 3 and 4). They are raw rather than signed because the micro
    /// encoding hasn't been measured -- the macro table's sign-magnitude
    /// layout is only an assumption for it.
    ///
    /// Undo with `gamma_test_restore`.
    pub fn gamma_test_apply(&mut self, macro_mode: i32, micro_r: u8, micro_b: u8) -> Result<(), Error> {
        let (enable, b_mag) = match macro_mode {
            1 => (true, 0),
            2 => (true, 7),
            _ => (false, 0),
        };
        self.write_gamma_ramp(0x02, 0x3F, 0, b_mag)?;
        self.write_gamma_uniform(0x03, 0x7F, micro_r, micro_b)?;
        self.write_gamma_uniform(0x04, 0x7F, micro_r, micro_b)?;
        self.select_page(0x02)?;
        self.write_cmd(0x40, &[enable as u8])?;
        self.select_page(0x00)?;
        Ok(())
    }

    /// Undo `gamma_test_apply`: micro tables back to 0x00 (assumed to be the
    /// reset value -- nothing else writes them), then re-apply the
    /// color-temperature `preset`, which rewrites the macro table and the
    /// 3G enable.
    pub fn gamma_test_restore(&mut self, preset: i32) -> Result<(), Error> {
        self.write_gamma_uniform(0x03, 0x7F, 0, 0)?;
        self.write_gamma_uniform(0x04, 0x7F, 0, 0)?;
        self.set_color_temperature(preset)
    }
}
