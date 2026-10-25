use std::cell::RefCell;
use std::rc::Rc;
use std::time::Duration;

use slint::{ComponentHandle, Timer};

use super::super::slint::{Backend, ScreenId};
use crate::device::Device;

use super::UiState;

/// State for the display measurement test screens (Settings > General >
/// Color Patch / Gamma Table Test). Both show an exact 8-bit patch drawn by
/// the FPGA (`Device::set_test_patch`); the Gamma Table Test also programs
/// the panel's gamma tables, which must be restored on the way out.
#[derive(Default)]
pub(super) struct TestPatchState {
    /// Whether the FPGA test patch is currently enabled.
    patch_active: bool,
    /// Gamma tables last written by the Gamma Table Test (macro mode,
    /// micro R, micro B), or `None` if they are in their normal state.
    gamma_applied: Option<(i32, u8, u8)>,
}

impl UiState {
    pub(super) fn setup_test_patch(&mut self, state: &Rc<RefCell<UiState>>, _device: &mut Device) {
        let root = self.root.unwrap();
        let backend = root.global::<Backend>();

        let state_ = state.clone();
        backend.on_test_patch_changed(move || UiState::schedule_test_patch_update(&state_, ScreenId::ColorPatch));

        let state_ = state.clone();
        backend.on_gamma_test_changed(move || UiState::schedule_test_patch_update(&state_, ScreenId::GammaTest));
    }

    /// Bring the test patch and gamma tables in line with `screen` (the
    /// screen being entered, or the one whose values changed). The device
    /// work runs from a zero-delay timer in the UI loop, not inside the Slint
    /// callback that asked for it, so it never runs nested in Slint event
    /// handling or rendering.
    pub(super) fn schedule_test_patch_update(state: &Rc<RefCell<UiState>>, screen: ScreenId) {
        let state = state.clone();
        Timer::single_shot(Duration::ZERO, move || {
            let mut state = state.borrow_mut();
            match screen {
                ScreenId::ColorPatch => {
                    state.leave_gamma_test();
                    state.apply_color_patch();
                }
                ScreenId::GammaTest => state.apply_gamma_test(),
                _ => state.on_test_patch_leave(),
            }
        });
    }

    /// Turn the test patch off and put the gamma tables back.
    fn on_test_patch_leave(&mut self) {
        self.leave_gamma_test();
        if self.test_patch.patch_active {
            log::info!("Test patch off");
            Device::lock().set_test_patch(None);
            self.test_patch.patch_active = false;
        }
    }

    fn apply_color_patch(&mut self) {
        let root = self.root.unwrap();
        let backend = root.global::<Backend>();
        let channel = |v: i32| v.clamp(0, 255) as u8;
        let color = (
            channel(backend.get_test_patch_r()),
            channel(backend.get_test_patch_g()),
            channel(backend.get_test_patch_b()),
        );
        log::info!("Color patch: {:?}", color);
        Device::lock().set_test_patch(Some(color));
        self.test_patch.patch_active = true;
    }

    fn apply_gamma_test(&mut self) {
        let root = self.root.unwrap();
        let backend = root.global::<Backend>();
        let level = backend.get_gamma_test_level().clamp(0, 255) as u8;
        let tables = (
            backend.get_gamma_test_macro().clamp(0, 2),
            backend.get_gamma_test_micro_r().clamp(0, 15) as u8,
            backend.get_gamma_test_micro_b().clamp(0, 15) as u8,
        );

        log::info!("Gamma test: level {} tables {:?}", level, tables);
        let mut device = Device::lock();
        // Rewriting the tables is ~350 SPI commands, so only do it when they
        // change, not on every gray-level step.
        if self.test_patch.gamma_applied != Some(tables) {
            device.gamma_test_apply(tables.0, tables.1, tables.2);
            self.test_patch.gamma_applied = Some(tables);
        }
        device.set_test_patch(Some((level, level, level)));
        self.test_patch.patch_active = true;
    }

    fn leave_gamma_test(&mut self) {
        if self.test_patch.gamma_applied.take().is_some() {
            log::info!("Gamma test: restoring tables");
            Device::lock().gamma_test_restore();
        }
    }
}
