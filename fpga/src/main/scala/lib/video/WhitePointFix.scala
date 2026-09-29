package lib.video

import chisel3._
import chisel3.util._

/**
 * Final per-channel white-point correction, applied to the composited
 * video signal (game pixels *and* the UI overlay) immediately before it is
 * narrowed to the panel's bit depth.
 *
 * The rev4 panel's native white point is around 16750K -- far bluer than
 * D65 -- and its primaries are close enough to sRGB that the gamut is not
 * the problem. A per-channel gain is therefore enough to move white a long
 * way toward a standard illuminant. Red is the limiting channel, so it is
 * left at unity and green and blue are brought down to meet it.
 *
 * This is deliberately *not* the in-core `ColorCorrection` stage: that one
 * emulates a source console's color reproduction, only exists in the GB and
 * GBA cores, and never sees the UI overlay. This stage is part of the
 * framework, runs for every core, and is the last thing to touch a pixel.
 *
 * **These gains act on gamma-encoded pixel values, not linear light.** The
 * pixel arriving here is still in the panel's ~2.26-gamma code space, so
 * scaling a channel by `g` here reduces its *light output* by `g^2.26`.
 * Because every channel's transfer is the same power law, that is exactly
 * equivalent to a linear-light gain of `g^2.26` for every color, not just
 * grays -- so a per-channel scale in this domain is still colorimetrically
 * correct, provided the constants are converted. Measured linear-light
 * gains must therefore be raised to the power `1/2.26` before being used
 * here. Getting this wrong once put the measured
 * white point at 4140K instead of 7500K.
 *
 * The exponent that matters is the *local* one over the span each channel
 * is actually pulled down across -- 255 down to ~160-195 -- not the
 * panel's global gamma of 2.26, and it shifts as the numerator moves. Two
 * attempts to predict it from a single operating point both missed: 2.26
 * left both targets ~350K too cool, and a one-point local estimate then
 * overshot ~150K warm. **Do not re-derive these from a gamma.** The
 * reliable method is a secant through two hardware captures that bracket
 * the target, which needs no exponent at all -- just the measured slope of
 * achieved linear gain per numerator step (~0.0085).
 *
 * Gains are n/256, which is where quantization error drops below the
 * run-to-run spread of the measurements themselves:
 *
 *   mode 0  None  R 256/256  G 256/256  B 256/256   (exactly transparent)
 *   mode 1  D75   R 256/256  G 196/256  B 180/256   (measured 7878K)
 *   mode 2  D65   R 256/256  G 189/256  B 164/256   (measured 6844K)
 *
 * **These are deliberately not the most accurate values available.** The
 * secant solution (D75 G 194/B 177, D65 G 187/B 160) measured closer to
 * target, but the only GBA bitstream built with it failed to load ROMs
 * from SD while physical cartridges still worked. The values here are from
 * a build known to work on hardware, even though they read ~350K cool.
 * Restore the secant values only together with a fix for -- or an
 * explanation of -- that failure.
 *
 * The values describe the rev4 ILI9806E panel specifically, so this module
 * is only instantiated for that revision (`Revision.whitePointFix`).
 */
class WhitePointFix extends Module {
  val io = IO(new Bundle {
    /** 0 = None, 1 = D75, 2 = D65. Unknown values behave as None. */
    val mode = Input(UInt(2.W))
    val in = Input(ColorRGB(8))
    val out = Output(ColorRGB(8))
  })

  /**
   * (red, green, blue) gain numerators over 256, indexed by mode.
   *
   * Calibrated empirically against two hardware captures rather than
   * derived from the panel's global gamma -- see the note above.
   */
  private val gains = Seq(
    /* 0: None */ (256, 256, 256),
    /* 1: D75  */ (256, 196, 180),
    /* 2: D65  */ (256, 189, 164),
  )

  /** `v * num`, built from shifts and adds -- no multiplier. */
  private def times(v: UInt, num: Int): UInt = num match {
    case 256 => v << 8
    case 196 => (v << 7) +& (v << 6) +& (v << 2)
    case 189 => (v << 7) +& (v << 6) -& (v << 1) -& v
    case 180 => (v << 7) +& (v << 5) +& (v << 4) +& (v << 2)
    case 164 => (v << 7) +& (v << 5) +& (v << 2)
  }

  /**
   * Scale an 8-bit channel by `num`/256, rounded to nearest.
   *
   * Rounding rather than truncating matters because the correction already
   * costs output range, and truncating on top of that would bias every
   * channel low. `num` is at most 256, so `v * num + 128` is at most 65408
   * and the result still fits in 8 bits -- and at num = 256 this is exactly
   * `v`, so mode 0 is a true bypass.
   */
  private def scale(v: UInt, num: Int): UInt = (times(v, num) +& 128.U)(15, 8)

  /** Select between the per-mode gains; anything unmapped falls back to None. */
  private def channel(v: UInt, nums: Seq[Int]): UInt =
    MuxLookup(io.mode, scale(v, nums.head))(
      nums.zipWithIndex.tail.map { case (num, mode) => mode.U -> scale(v, num) }
    )

  io.out.r := channel(io.in.r, gains.map(_._1))
  io.out.g := channel(io.in.g, gains.map(_._2))
  io.out.b := channel(io.in.b, gains.map(_._3))
}
