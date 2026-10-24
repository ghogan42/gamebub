package lib.audio

import chisel3._

object FirstOrderLowpass {
  /**
   * Coefficient b0 of the bilinear-transform first-order low-pass
   *   y[n] = b0 (x[n] + x[n-1]) + (1 - 2 b0) y[n-1]
   * which is -3 dB exactly at the cutoff and rolls off at 6 dB/octave, with a
   * zero at Nyquist.
   */
  def coefficient(cutoffHz: Double, sampleRateHz: Double): Double = {
    val k = math.tan(math.Pi * cutoffHz / sampleRateHz)
    k / (1 + k)
  }
}

/**
 * First-order IIR low-pass filter, evaluated in the equivalent form
 *   y[n] = y[n-1] + b0 (x[n] + x[n-1] - 2 y[n-1])
 * so it needs a single multiplier. Updates one cycle after each `tick`; the
 * output is held until the next one, and `outValid` pulses when it updates.
 *
 * The impulse response is all positive with unity DC gain, so the output
 * never exceeds the input range (no overshoot).
 */
class FirstOrderLowpass(
  dataBits: Int,
  cutoffHz: Double,
  sampleRateHz: Double,
  coeffFracBits: Int = 17,
  stateFracBits: Int = 4,
) extends Module {
  val io = IO(new Bundle {
    /** Advances the filter by one sample period. */
    val tick = Input(Bool())
    val in = Input(SInt(dataBits.W))
    val out = Output(SInt(dataBits.W))
    val outValid = Output(Bool())
  })

  // b0 in unsigned Q0.(coeffFracBits), as a non-negative SInt.
  private val b0 = math.round(FirstOrderLowpass.coefficient(cutoffHz, sampleRateHz) * (1L << coeffFracBits))
  require(b0 > 0 && b0 < (1L << coeffFracBits), "cutoff must be below Nyquist")
  private val coeff = b0.S((coeffFracBits + 1).W)

  // State in Q(dataBits).(stateFracBits): the fraction bits keep the rounding
  // error fed back through y well below the output LSB.
  private val stateBits = dataBits + stateFracBits
  private val stateMax = (BigInt(1) << (stateBits - 1)) - 1
  val xPrev = RegInit(0.S(dataBits.W))
  val y = RegInit(0.S(stateBits.W))

  val outReg = RegInit(0.S(dataBits.W))
  io.out := outReg
  io.outValid := RegNext(io.tick, false.B)

  when (io.tick) {
    xPrev := io.in
    val diff = ((io.in +& xPrev) << stateFracBits).asSInt - (y << 1).asSInt
    val step = (diff * coeff + (1L << (coeffFracBits - 1)).S) >> coeffFracBits
    val sum = y +& step
    // Can't exceed the input range in theory; clamp so rounding can't wrap.
    val yNext = Mux(sum > stateMax.S, stateMax.S,
      Mux(sum < (-stateMax - 1).S, (-stateMax - 1).S, sum))(stateBits - 1, 0).asSInt
    y := yNext
    outReg := Saturate((yNext + (1 << (stateFracBits - 1)).S) >> stateFracBits, dataBits)
  }
}
