package gba.cart

import chisel3._
import chisel3.util._
import gba.cart.CartridgeController.State
import gba.{MmioMap, MmioTarget}
import gba.mem.TargetInterface
import lib.log.Logger

object CartridgeController {
  object State extends ChiselEnum {
    val Idle = Value
    /// nCS goes low
    ///   Non-Seq: Can be extended by wait state
    val RomStage0 = Value
    /// nCS still low, nRD/nWR goes low (WS
    ///   Non-Seq: Can be extended by wait state
    ///       Seq: Can be extended by wait state
    val RomStage1 = Value
    /// nRD/nWR goes high
    ///  * if there's a request:
    //       start it (nextState: Rom1), keep nCS low.
    ///  * if no request, nCS goes high too
    val RomStage2 = Value

    /// nCS2 goes low
    /// ADDR put on bus
    /// For write, DATA put on bus
    /// On the *falling edge*: nRD/nRW goes low
    val RamStage0 = Value
    /// nCS2, nRD/nWR still low
    /// Can be extended by wait state
    val RamStage1 = Value
    /// nRD/nRW goes high
    ///  * if there's a request:
    ///      start it (next state: Rom0), keep nCS2 low
    ///  * if no request: on the *falling edge* nCS2 goes high.
    val RamStage2 = Value
  }
}

class WaitstateControl extends Bundle {
  // Cartridge type (read-only): false for GBA, true for CGB
  val isCgbCart = Bool()
  // Enable prefetch buffer
  // TODO: implement
  val prefetch = Bool()
  val _unused = UInt(1.W)
  // Phi clock output (Disable, 4 MHz, 8 MHz, 16 MHz)
  val phi = UInt(2.W)
  // ROM 2 wait states (sequential): 8, 1
  val ws2Next = UInt(1.W)
  // ROM 2 wait states (non-sequential): 4, 3, 2, 8
  val ws2First = UInt(2.W)
  // ROM 1 wait states (sequential): 4, 1
  val ws1Next = UInt(1.W)
  // ROM 1 wait states (non-sequential): 4, 3, 2, 8
  val ws1First = UInt(2.W)
  // ROM 0 wait states (sequential): 2, 1
  val ws0Next = UInt(1.W)
  // ROM 0 wait states (non-sequential): 4, 3, 2, 8
  val ws0First = UInt(2.W)
  // SRAM wait states: 4, 3, 2, 8
  val sram = UInt(2.W)
}

/*
 * Module that interacts with the physical cartridge bus.
 *
 * Handles control signals, wait states, bursts, etc.
 */
class CartridgeController extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())

    /// Cartridge interface
    val cartridge = new CartridgeInterface

    // MMIO interface for WAITCNT (cartridge waitstate control and prefetch buffer)
    val mmio = new MmioTarget()
    val prefetchEnabled = Output(Bool())
    val abortRequest = Input(Bool())

    // Memory bus target interfaces
    // The ROM interface is from the prefetch controller, and is halfword addressed.
    val busTargetRom = new TargetInterface(16.W)
    val busTargetRomRegion = Input(Vec(3, Bool()))
    val busTargetRam = new TargetInterface(8.W)
  })
  val logger = Logger("cart", enable = io.enable)

  val state = RegInit(State.Idle)
  val regReadData = Reg(UInt(16.W))
  /// Write data of the current cartridge RAM access (held on the bus after nWR rises).
  val regRamWriteData = Reg(UInt(8.W))
  val isRequestDone = WireDefault(false.B)
  val regWaitControl = RegInit(0.U.asTypeOf(new WaitstateControl))
  val waitRomFirst = Seq(regWaitControl.ws0First, regWaitControl.ws1First, regWaitControl.ws2First)
  val waitRomNext = Seq(regWaitControl.ws0Next, regWaitControl.ws1Next, regWaitControl.ws2Next)

  io.mmio <> MmioMap(
    // WAITCNT
    // TODO make sure Cartridge type always reads as 0
    0x204 -> MmioMap.Entry.rw(regWaitControl),
  )
  io.prefetchEnabled := regWaitControl.prefetch

  // Default target bus state
  io.busTargetRom.done := isRequestDone
  io.busTargetRom.dataRead := regReadData
  io.busTargetRam.done := false.B
  io.busTargetRam.dataRead := regReadData

  // Control signals come directly from registers to avoid glitches.
  // TODO: see if ADLoOut / AHiOut can also come from registers
  val reg_nCS = RegInit(1.U(1.W))
  val reg_nRD = RegInit(1.U(1.W))
  val reg_nWR = RegInit(1.U(1.W))
  val reg_nCS2 = RegInit(1.U(1.W))

  // Cartridge port
  io.cartridge.phi := 0.U  // TODO
  io.cartridge.nWR := reg_nWR
  io.cartridge.nRD := reg_nRD
  io.cartridge.nCS := reg_nCS
  io.cartridge.ADLoOut := DontCare
  io.cartridge.ADLoDir := 0.U
  io.cartridge.AHiOut := DontCare
  io.cartridge.AHiDir := 0.U
  io.cartridge.nCS2 := reg_nCS2
  io.cartridge.reqStart := false.B
  io.cartridge.reqRom := DontCare
  io.cartridge.reqAddress := DontCare
  io.cartridge.reqWrite := DontCare
  io.cartridge.reqEnd := false.B

  // TODO: romRequestAddress shouldn't combinatorially depend on `io.enable`, makes it hard to avoid cycles with stall

  // ROM targets
  val romRequests = io.busTargetRomRegion
  val hasRomRequest = io.busTargetRom.request
  val romRequestAddress = io.busTargetRom.address
  val romRequestWrite = io.busTargetRom.write
  val romRequestDataWrite = io.busTargetRom.dataWrite
  val romRequestSequential = io.busTargetRom.sequential
  val romRequestNextSequential = io.busTargetRom.nextSeq
  val ramTarget = io.busTargetRam
  val currentRequestPort = Reg(UInt(3.W))
  val currentAddress = Reg(UInt(24.W))
  val currentIsWrite = Reg(Bool())
  val waitCounter = Reg(UInt(3.W))
  val romAddressAtPageEnd = currentAddress(15, 0).andR  // All 1s, highest address at page end
  val doLatchReadData = Reg(Bool())


  // New requests (not burst continuations) can be accepted when idle or at the end of a burst.
  // We can do back-to-back ROM requests (and RAM requests), as long as nCS has been raised high.
  val endRomBurst = WireDefault(false.B)
  val endRamBurst = WireDefault(false.B)
  // A non-sequential ROM request normally needs nCS to have gone high first (a physical cart latches
  // the address on its falling edge), costing a cycle when it ends a burst that kept nCS low (e.g. a
  // prefetch burst). An emulated cartridge takes the address from reqStart instead, so it can start
  // immediately, like on hardware. Except for addresses that may hit an emulated peripheral (EEPROM
  // in region 2, GPIO in the first 256 bytes) the cartridge has, which do rely on the nCS edge.
  val romPeripheralPossible = (romRequests(2) && io.cartridge.emuHasEeprom) ||
    (romRequestAddress(23, 7) === 0.U && io.cartridge.emuHasGpio)
  // Except when the aborted access (e.g. a prefetch fetch) is in its last wait cycle, i.e. would complete
  // next cycle: then the new access starts a cycle later, as on hardware (mGBA suite timing tests; cf.
  // NanoBoyAdvance's prefetch model).
  val abortingLastCycle = state === State.RomStage1 && waitCounter === 0.U
  val immediateRomStart = hasRomRequest && reg_nCS === 0.U && (endRomBurst || (io.abortRequest && !abortingLastCycle)) &&
    io.cartridge.isEmulated && !romPeripheralPossible
  val initialRomWait = Mux1H(romRequests, waitRomFirst)
  when (state === State.Idle || endRomBurst || endRamBurst || immediateRomStart) {
    when (hasRomRequest && (reg_nCS === 1.U || immediateRomStart)) {
      logger.debug(cf"Start Rom(${romRequests.asUInt}%b) request addr=${romRequestAddress << 1}%x wr=$romRequestWrite")
      io.cartridge.ADLoOut := romRequestAddress(15, 0)
      io.cartridge.ADLoDir := true.B
      io.cartridge.AHiOut := romRequestAddress(23, 16)
      io.cartridge.AHiDir := true.B

      io.cartridge.reqStart := true.B
      io.cartridge.reqRom := true.B
      io.cartridge.reqAddress := romRequestAddress
      io.cartridge.reqWrite := romRequestWrite

      when (io.enable) {
        state := State.RomStage0
        reg_nCS := 0.U
        currentAddress := romRequestAddress
        currentIsWrite := romRequestWrite
        currentRequestPort := romRequests.asUInt

        // Initial burst wait: 0 [extra] cycles if total waits is 2,
        // otherwise 1. Physical cartridges: reads always a single address cycle, writes always an
        // address cycle + a data cycle (see RomStage0).
        val physicalCart = !io.cartridge.isEmulated
        waitCounter := Mux(physicalCart, romRequestWrite.asUInt, Mux(initialRomWait === 2.U, 0.U, 1.U))
      }
    } .elsewhen (ramTarget.request && reg_nCS2 === 1.U) {
      logger.debug(cf"Start Ram request addr=${ramTarget.address(15, 0)}%x wr=${ramTarget.write}")

      // Put the address on the bus now, while nCS2 is still high: a physical Flash chip latches the
      // address when nCS2/nWR fall at the start of RamStage0, so it must already be stable then.
      // Note: we don't signal emuCart write until we have the write data, in RamStage0
      when (!io.cartridge.isEmulated) {
        io.cartridge.ADLoOut := ramTarget.address(15, 0)
        io.cartridge.ADLoDir := true.B
      }

      when (io.enable) {
        state := State.RamStage0
        reg_nCS2 := 0.U
        // XXX: nRD/nWR are supposed to go low on the *falling* edge of the next cycle
        // Physical cartridges: nWR only goes low after RamStage0 (see there).
        when (ramTarget.write) {
          reg_nWR := !io.cartridge.isEmulated
        } .otherwise {
          reg_nRD := 0.U
        }
        currentAddress := ramTarget.address
        currentIsWrite := ramTarget.write
      }
    } .otherwise {
      when (io.enable) {
        state := State.Idle
      }
    }
  }

  switch (state) {
    is (State.RomStage0) {
      io.cartridge.ADLoOut := currentAddress(15, 0)
      io.cartridge.ADLoDir := true.B
      io.cartridge.AHiOut := currentAddress(23, 16)
      io.cartridge.AHiDir := true.B

      val wait = Mux1H(currentRequestPort, waitRomFirst)
      val physicalWrite = currentIsWrite && !io.cartridge.isEmulated
      when (currentIsWrite && waitCounter === 0.U && (wait =/= 2.U || physicalWrite)) {
        // If this is the second cycle of a two cycle initial wait, set WDATA output.
        io.cartridge.ADLoDir := true.B
        io.cartridge.ADLoOut := romRequestDataWrite
      }

      when (io.enable) {
        waitCounter := waitCounter - 1.U
        when (waitCounter === 0.U) {
          state := State.RomStage1
          // Physical cartridge reads: nRD goes low right after the single address cycle (the cart has
          // latched A0-15 on nCS falling) and stays low for the rest of the access, like on a GBA.
          // Otherwise nRD would only be low for the last 1-2 cycles (1 cycle at the common 3-wait
          // setting), too short for slower cartridge ROMs (e.g. bootleg Flash ROMs) incl. the AD bus
          // turnaround. Total access time is unchanged.
          // Physical cartridge writes: an address cycle, a data cycle (nWR high; e.g. EZ-Flash Omega
          // needs the data set up before nWR falls), then nWR low for at least 2 cycles (a 1 cycle
          // pulse is too short for some carts, e.g. a bootleg Emerald's ROM-area writes). This adds a
          // cycle to physical ROM writes at 3 or 2 first-access waits.
          val longReadStrobe = !io.cartridge.isEmulated && !currentIsWrite
          waitCounter := Mux(longReadStrobe, VecInit(2.U, 1.U, 0.U, 6.U)(wait),
            Mux(physicalWrite, VecInit(1.U, 1.U, 1.U, 5.U)(wait), VecInit(1.U, 0.U, 0.U, 5.U)(wait)))
          when (currentIsWrite) {
            reg_nWR := 0.U
          } .otherwise {
            reg_nRD := 0.U
          }
        }
      }
    }
    is (State.RomStage1) {
      when (currentIsWrite) {
        io.cartridge.ADLoDir := true.B
        io.cartridge.ADLoOut := romRequestDataWrite
      } .otherwise {
        io.cartridge.ADLoDir := false.B
      }
      io.cartridge.AHiOut := currentAddress(23, 16)
      io.cartridge.AHiDir := true.B

      when (io.enable) {
        waitCounter := waitCounter - 1.U
        when (waitCounter === 0.U) {
          state := State.RomStage2
          regReadData := io.cartridge.ADLoIn
          reg_nRD := 1.U
          reg_nWR := 1.U
          doLatchReadData := true.B

          when (!romRequestNextSequential || romAddressAtPageEnd) {
            // The request is definitely not being continued with a burst, put nCS back high
            reg_nCS := 1.U
          }
        }
      }
    }
    is (State.RomStage2) {
      // There's a write data hold time requirement:
      // we need to keep the write data on the bus after nWR goes high
      // (the GBA keeps it on the bus for ~1/2 cycle, and then switches to the
      //  next request's address)
      // The proper way to do this would probably be to use an actual DDR output,
      // but it's difficult (or impossible?) to time, so we'll have to hope this
      // works well enough.
      when (!io.cartridge.isEmulated && clock.asBool) {
        when (currentIsWrite) {
          io.cartridge.ADLoDir := true.B
          io.cartridge.ADLoOut := romRequestDataWrite
        } .otherwise {
          io.cartridge.ADLoDir := false.B
        }
        io.cartridge.AHiOut := currentAddress(23, 16)
        io.cartridge.AHiDir := true.B
      }


      when (io.cartridge.isEmulated && doLatchReadData) {
        io.busTargetRom.dataRead := io.cartridge.ADLoIn
        io.cartridge.reqEnd := true.B

        when (io.enable) {
          regReadData := io.cartridge.ADLoIn
          doLatchReadData := false.B
        }
      }
      isRequestDone := true.B

      when (reg_nCS === 1.U) {
        // Bus doesn't have a sequential request (for any target). End burst.
        endRomBurst := true.B
      } .elsewhen (hasRomRequest && romRequestSequential) {
        // Starting a new, sequential request, nCS was kept low.
        io.cartridge.reqStart := true.B
        io.cartridge.reqRom := true.B
        io.cartridge.reqAddress := romRequestAddress
        io.cartridge.reqWrite := romRequestWrite

        when (io.enable) {
          state := State.RomStage1
          when (romRequestWrite) {
            reg_nWR := 0.U
          } .otherwise {
            reg_nRD := 0.U
          }
          currentAddress := romRequestAddress
          currentIsWrite := romRequestWrite

          val wait = Mux1H(currentRequestPort, waitRomNext)
          when (wait === 1.U) {
            waitCounter := 0.U  // 1 wait state
          } .otherwise {
            waitCounter := Mux1H(currentRequestPort, Seq(1.U, 3.U, 7.U))
          }
        }
      } .elsewhen (!romRequestNextSequential || (hasRomRequest && !romRequestSequential)) {
        // Time to end the request
        endRomBurst := true.B
        when (io.enable) {
          reg_nCS := 1.U
        }
      } .otherwise {
        // Still in a burst (keep nCS high), but haven't yet received a request.
      }
    }
    is (State.RamStage0) {
      // Now that we have write data, signal the access.
      // TODO: Note that reqStart will be asserted for multiple cycles if io.enable is false
      io.cartridge.reqStart := true.B
      io.cartridge.reqRom := false.B
      io.cartridge.reqAddress := currentAddress(15, 0)
      io.cartridge.reqWrite := currentIsWrite

      io.cartridge.ADLoOut := currentAddress(15, 0)
      io.cartridge.ADLoDir := true.B
      when (currentIsWrite) {
        io.cartridge.AHiDir := true.B
        io.cartridge.AHiOut := ramTarget.dataWrite
      }

      when (io.enable) {
        state := State.RamStage1
        waitCounter := VecInit(2.U, 1.U, 0.U, 6.U)(regWaitControl.sram)
        // Physical cartridges: a Flash chip latches the address when /WE falls, so drive A0-15 and the
        // data for this whole cycle first (the AD bus may have been turning around from a ROM read),
        // then pull nWR low.
        when (currentIsWrite && !io.cartridge.isEmulated) {
          reg_nWR := 0.U
        }
      }
    }
    is (State.RamStage1) {
      io.cartridge.ADLoOut := currentAddress(15, 0)
      io.cartridge.ADLoDir := true.B
      when (currentIsWrite) {
        io.cartridge.AHiDir := true.B
        io.cartridge.AHiOut := ramTarget.dataWrite
      }

      io.cartridge.reqEnd := waitCounter === 0.U
      regRamWriteData := ramTarget.dataWrite
      when (io.enable) {
        waitCounter := waitCounter - 1.U
        // Physical cartridges: end the write pulse one cycle early, so the address and data stay
        // driven for a full cycle after /WE rises (Flash latches data on the rising edge).
        when (currentIsWrite && !io.cartridge.isEmulated && waitCounter === 1.U) {
          reg_nWR := 1.U
        }
        when (waitCounter === 0.U) {
          state := State.RamStage2
          regReadData := io.cartridge.AHiIn
          reg_nWR := 1.U
          reg_nRD := 1.U
          // XXX: nCS2 should go back high on the *falling* edge of the next cycle
          // TODO: use requestSplitForceNextSequential to determine if nCS2 goes back high
          reg_nCS2 := 1.U
        }
      }
    }
    is (State.RamStage2) {
      // Hold time: nWR/nRD (and nCS2) just went high, and a physical Flash chip latches write data on
      // that rising edge. Keep the address and write data on the bus for ~1/2 cycle (as for ROM
      // writes in RomStage2); a following request (if any) takes over the bus in the second half.
      when (!io.cartridge.isEmulated && clock.asBool) {
        io.cartridge.ADLoOut := currentAddress(15, 0)
        io.cartridge.ADLoDir := true.B
        when (currentIsWrite) {
          io.cartridge.AHiOut := regRamWriteData
          io.cartridge.AHiDir := true.B
        }
      }
      ramTarget.done := true.B
      // nRD/NRW went back high
      when (ramTarget.request && ramTarget.sequential) {
        logger.debug(cf"Continue ram request")
        // Starting a new "sequential" request -- nCS2 stays low
        // XXX: in a burst, the next ADDR is put on the bus in the *falling* edge of this cycle
        // ... but we don't do that here. It's probably fine to just put it on the next rising
        // edge, because that's the timing of the first request in a burst anyway.

        when (io.enable) {
          state := State.RamStage0
          when (ramTarget.write) {
            reg_nWR := !io.cartridge.isEmulated
          } .otherwise {
            reg_nRD := 0.U
          }
          currentAddress := ramTarget.address
          currentIsWrite := ramTarget.write
        }
      } .otherwise {
        // Not getting another request this cycle, end burst.

        logger.debug(cf"End ram request")
        endRamBurst := true.B
      }
    }
  }

  // When a request is aborted, immediately move to idle state (and adjust signals).
  // It is assumed that the requester will ignore any data returned.
  when (io.enable && io.abortRequest) {
    state := State.Idle
    reg_nCS := 1.U
    reg_nCS2 := 1.U
    reg_nRD := 1.U
    reg_nWR := 1.U
  }
  when (immediateRomStart) {
    // Same as starting a request from idle (above), overriding the ended/aborted burst's updates.
    io.cartridge.reqStart := true.B
    io.cartridge.reqRom := true.B
    io.cartridge.reqAddress := romRequestAddress
    io.cartridge.reqWrite := romRequestWrite
    when (io.enable) {
      state := State.RomStage0
      reg_nCS := 0.U
      currentAddress := romRequestAddress
      currentIsWrite := romRequestWrite
      currentRequestPort := romRequests.asUInt
      waitCounter := Mux(initialRomWait === 2.U, 0.U, 1.U)
    }
  }
}
