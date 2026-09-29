use std::cell::RefCell;
use std::rc::Rc;

use slint::ComponentHandle;

use super::super::slint::Backend;
use crate::device::Device;

use super::UiState;

/// Expand a 5-bit level to the 8-bit value that survives the UI overlay's
/// quantization unchanged.
///
/// The boot core renders the menu overlay as ARGB1555 -- 5 bits per channel
/// (`HandheldTop.overlayFullDepth`, set in `HandheldBoot.scala`) -- and
/// expands back to 8 bits by replicating the high bits. An arbitrary 8-bit
/// value therefore does *not* reach the panel intact: 63 lands on either 57
/// or 66 depending on how the write side rounds, which is enough ambiguity
/// to shift a fitted gamma by 0.25. Values produced by this function are
/// fixed points of that round trip under either convention, so what the
/// label says is what the panel gets.
const fn expand5(level: u8) -> u8 {
    (level << 3) | (level >> 2)
}

/// 5-bit drive levels to sweep for each primary channel.
const LEVELS: [u8; 5] = [0, 8, 15, 23, 31];
const NAMES: [&str; 3] = ["RED", "GREEN", "BLUE"];
/// Gray/white gets a finer sweep than the primaries: a 16-step ramp spanning
/// the full 5-bit range, for measuring the display's grayscale transfer
/// curve. 31 doesn't divide evenly by 15, so the steps are 5-bit levels
/// 0, 2, 4 ... 14, 17, 19 ... 31 -- one double-width step in the middle.
const GRAY_STEPS: usize = 16;
const NUM_COLORS: usize = GRAY_STEPS + LEVELS.len() * NAMES.len();

/// Returns (label, r, g, b) for a given index, wrapping around. The gray ramp
/// comes first, then each primary in turn. Labels carry the 8-bit code that
/// actually reaches the panel, which is what the measurements are fitted
/// against.
fn color_test_entry(index: usize) -> (String, u8, u8, u8) {
    let index = index % NUM_COLORS;
    if index < GRAY_STEPS {
        // Round-to-nearest 5-bit level: (i * 31 + 7) / 15.
        let level = expand5(((index * 31 + 7) / (GRAY_STEPS - 1)) as u8);
        return (format!("GRAY {}", level), level, level, level);
    }
    let index = index - GRAY_STEPS;
    let channel = index / LEVELS.len();
    let level = expand5(LEVELS[index % LEVELS.len()]);
    let (r, g, b) = match channel {
        0 => (level, 0, 0),
        1 => (0, level, 0),
        _ => (0, 0, level),
    };
    (format!("{} {}", NAMES[channel], level), r, g, b)
}

impl UiState {
    /// Set up the "Color Test" screen: full-screen solid color patterns for
    /// measuring the display (e.g. with a spectrophotometer) while a color
    /// temperature preset is active. Reachable from Settings > General.
    pub(super) fn setup_color_test(&mut self, state: &Rc<RefCell<UiState>>, _device: &mut Device) {
        let root = self.root.unwrap();
        let backend = root.global::<Backend>();

        let state_ = state.clone();
        backend.on_color_test_advance(move || {
            let mut state = state_.borrow_mut();
            state.color_test_index = state.color_test_index.wrapping_add(1);
            state.apply_color_test_entry();
        });
    }

    pub(super) fn on_color_test_enter(&mut self) {
        self.color_test_index = 0;
        self.apply_color_test_entry();
    }

    fn apply_color_test_entry(&mut self) {
        let (label, r, g, b) = color_test_entry(self.color_test_index);
        let root = self.root.unwrap();
        let backend = root.global::<Backend>();
        backend.set_color_test_label(label.into());
        backend.set_color_test_r(r as i32);
        backend.set_color_test_g(g as i32);
        backend.set_color_test_b(b as i32);
    }
}
