package lib.audio

import chisel3._
import lib.util.EphemeralSimulator._
import org.scalatest.funsuite.AnyFunSuite

class FirstOrderLowpassSpec extends AnyFunSuite {
  private val dataBits = 19
  private val sampleRate = 48000.0
  private val cutoffs = Seq(5000.0, 8000.0)

  /** Runs `input` through the filter, one sample per 4 cycles; returns the outputs. */
  private def run(input: Seq[Int], select: Int = 1): Seq[Int] = {
    var out = Seq.empty[Int]
    simulate(new FirstOrderLowpass(dataBits, cutoffs, sampleRate)) { dut =>
      dut.io.select.poke(select)
      dut.io.tick.poke(false)
      for (x <- input) {
        dut.io.in.poke(x)
        dut.io.tick.poke(true)
        dut.clock.step()
        dut.io.tick.poke(false)
        dut.clock.step(3)
        out :+= dut.io.out.peek().litValue.toInt
      }
    }
    out
  }

  /** Magnitude response of the ideal (unquantized) filter at `freq`. */
  private def expectedGain(freq: Double, cutoff: Double): Double = {
    val b0 = FirstOrderLowpass.coefficient(cutoff, sampleRate)
    val w = 2 * math.Pi * freq / sampleRate
    // H(z) = b0 (1 + z^-1) / (1 - (1 - 2 b0) z^-1)
    val num = b0 * math.hypot(1 + math.cos(w), math.sin(w))
    val den = math.hypot(1 - (1 - 2 * b0) * math.cos(w), (1 - 2 * b0) * math.sin(w))
    num / den
  }

  test("DC passes through at unity gain") {
    val out = run(Seq.fill(100)(30000))
    assert(math.abs(out.last - 30000) <= 1)
  }

  test("-3 dB at the cutoff") {
    for (cutoff <- cutoffs) {
      assert(math.abs(expectedGain(cutoff, cutoff) - 1 / math.sqrt(2)) < 1e-9)
    }
  }

  test("full-scale step does not overshoot") {
    val max = (1 << (dataBits - 1)) - 1
    val out = run(Seq.fill(20)(-max - 1) ++ Seq.fill(40)(max))
    assert(out.forall(y => y <= max && y >= -max - 1))
    assert(math.abs(out.last - max) <= 1)
  }

  for ((cutoff, select) <- cutoffs.zipWithIndex; freq <- Seq(1000.0, 5000.0, 8000.0, 16000.0)) {
    test(f"${cutoff / 1000}%.0f kHz filter: sine at ${freq / 1000}%.0f kHz matches the ideal response") {
      val amplitude = 100000.0
      val n = 960
      val input = (0 until n).map(i => math.round(amplitude * math.sin(2 * math.Pi * freq * i / sampleRate)).toInt)
      val out = run(input, select).drop(n / 2) // skip the transient
      val in = input.drop(n / 2)
      def rms(xs: Seq[Int]) = math.sqrt(xs.map(x => x.toDouble * x).sum / xs.length)
      val gain = rms(out) / rms(in)
      val expected = expectedGain(freq, cutoff)
      assert(math.abs(gain - expected) < 0.002, f"gain $gain%.4f, expected $expected%.4f")
    }
  }
}
