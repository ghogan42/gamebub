package gba

import chisel3._
import chisel3.util._
import gba.mem.{BusAccessWidth, BusInterface}
import lib.log.Logger

object DmaAddressControl extends ChiselEnum {
  val increment = Value
  val decrement = Value
  val fixed = Value
  val reload = Value
}

object DmaStartControl extends ChiselEnum {
  val immediate = Value
  val vblank = Value
  val hblank = Value
  val special = Value
}

class DmaControl extends Bundle {
  val enable = Bool()
  val irq = Bool()
  val startControl = DmaStartControl()
  val cartridgeDrq = Bool()
  val sizeWord = Bool()
  val repeat = Bool()
  val sourceControl = DmaAddressControl()
  val destControl = DmaAddressControl()
  val _padding = UInt(5.W)
}

class Dma extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
    val mmio = new MmioTarget()

    val irq = Output(Vec(4, Bool()))

    val triggerVblank = Input(Bool())
    val triggerHblank = Input(Bool())
    val triggerVideo = Input(Bool())
    val triggerFifo = Input(Vec(2, Bool()))
    val stopVideo = Input(Bool())

    val busInitiator = Vec(4, new BusInterface)

    /// Whether the CPU must be kept off the bus (DMA startup cycles).
    val blockCpu = Output(Bool())
  })
  private val logger = Logger("dma", enable = io.enable)

  val regConfigSource = Seq(RegInit(0.U(27.W)), RegInit(0.U(28.W)), RegInit(0.U(28.W)), RegInit(0.U(28.W)))
  val regConfigDest = Seq(RegInit(0.U(27.W)), RegInit(0.U(27.W)), RegInit(0.U(27.W)), RegInit(0.U(28.W)))
  val regConfigCount = Seq(RegInit(0.U(14.W)), RegInit(0.U(14.W)), RegInit(0.U(14.W)), RegInit(0.U(16.W)))
  val regConfigControl = Seq.fill(4)(RegInit(0.U.asTypeOf(new DmaControl)))

  io.mmio <> MmioMap.fromSeq(
    (0 until 4).flatMap(i => Seq(
      (0xB0 + (i * 12)) -> MmioMap.Entry.w(regConfigSource(i)),
      (0xB4 + (i * 12)) -> MmioMap.Entry.w(regConfigDest(i)),
      (0xB8 + (i * 12)) -> MmioMap.Entry(
        // Count is write-only (reads 0); control bits 0-4 are unused and bit 11 (DRQ) only exists on DMA3.
        MmioMap.ReadFn(0.U(8.W), regConfigControl(i).asUInt & (if (i == 3) "hFFE0" else "hF7E0").U),
        MmioMap.WriteFn(regConfigCount(i), regConfigControl(i))
      )
    ))
  )

  // Whether any channel is active and in the "store" phase.
  val isAnyChannelStoring = WireDefault(false.B)

  // DMA data latch, shared by all channels. Loads from below EWRAM (BIOS, unmapped; also
  // DMA0 "ROM" sources, which are masked into this range) don't update it, so the store
  // repeats the last value transferred by any channel.
  val dataLatch = Reg(UInt(32.W))

  io.blockCpu := false.B

  for (i <- 0 until 4) {
    val configSource = regConfigSource(i)
    val configDest = regConfigDest(i)
    val configCount = regConfigCount(i)
    val control = regConfigControl(i)
    val bus = io.busInitiator(i)

    val isAudioFifo = (i == 1 || i == 2).B && control.startControl === DmaStartControl.special
    val isSizeWord = control.sizeWord || isAudioFifo

    val active = RegInit(false.B)
    val regSource = Reg(configSource.cloneType)
    val regDest = Reg(configDest.cloneType)
    val regCount = Reg(configCount.cloneType)
    val regInitial = Reg(Bool())  // Whether this is the initial load-store cycle.
    val regAccessedCart = Reg(Bool()) // Whether the cartridge has already been accessed this cycle
    val regStage = Reg(UInt(1.W))
    val regSourceAddressHalfword = Reg(UInt(1.W))
    val regSourceInvalid = Reg(Bool())
    /// Remaining internal cycles at the end of a transfer (GBATEK: 2I), during which the DMA
    /// still owns the bus.
    val regFinishCycles = RegInit(0.U(2.W))

    val busActive = WireDefault(false.B)
    io.irq(i) := false.B
    bus.WRITE := DontCare
    bus.SIZE := Mux(isSizeWord, BusAccessWidth.Word, BusAccessWidth.Halfword)
    bus.PROT.data := true.B
    bus.PROT.privileged := false.B
    bus.LOCK := false.B
    bus.ADDR := DontCare
    bus.MREQ := busActive
    bus.SEQ := regAccessedCart
    bus.WDATA := dataLatch

    // Latching config
    val prevEnabled = RegEnable(control.enable, io.enable)
    val justEnabled = control.enable && !prevEnabled
    val addressMask = Mux(isSizeWord, "b00".U(2.W), "b10".U(2.W))
    when (io.enable && justEnabled) {
      logger.info(cf"${i}: enabled: ${control}")
      // Mask off lower bits of address depending on size
      regSource := Cat(configSource(configSource.getWidth - 1, 2), configSource(1, 0) & addressMask)
      regDest := Cat(configDest(configDest.getWidth - 1, 2), configDest(1, 0) & addressMask)

      when (isAudioFifo) {
        // Special audio FIFO handling
        // Forces count=4, size=word, destControl=fixed
        regCount := 4.U
      } .otherwise {
        regCount := configCount
      }
    }

    // Channel activation
    val activateImm = (control.startControl === DmaStartControl.immediate) && justEnabled
    val activateHblank = (control.startControl === DmaStartControl.hblank) && io.triggerHblank
    val activateVblank = (control.startControl === DmaStartControl.vblank) && io.triggerVblank
    val activateSpecial = WireDefault(false.B)
    if (i == 1 || i == 2) {
      activateSpecial := control.startControl === DmaStartControl.special && io.triggerFifo(i - 1)
    } else if (i == 3) {
      activateSpecial := control.startControl === DmaStartControl.special && io.triggerVideo
      when (control.startControl === DmaStartControl.special && io.stopVideo) {
        control.enable := false.B
      }
    }
    when (io.enable) {
      when (!active && control.enable && (activateImm || activateHblank || activateVblank || activateSpecial)) {
        logger.info(cf"${i}: activate")
        active := true.B
        regInitial := true.B
        regStage := 0.U
        regAccessedCart := false.B
      }
    }

    // Each DMA channel can only be pre-empted during the "load" phase -- that is, once a DMA channel
    // submits a load to the bus, it won't be pre-empted until after it completes the store.
    //
    // This is done slightly messily here: if any channel is storing, a DMA can't start,
    // unless it's *us* who is storing, or we're past the initial load/store.
    when (active && regStage === 1.U) {
      isAnyChannelStoring := true.B
    }
    val isNotBlocked = !isAnyChannelStoring || regStage === 1.U || !regInitial

    // The cycle the transfer completes, plus regFinishCycles more (2I total).
    val isCompleting = active && regStage === 0.U && regCount === 0.U && !regInitial
    when (isCompleting) {
      io.blockCpu := true.B
    }
    when (regFinishCycles =/= 0.U) {
      io.blockCpu := true.B
      when (io.enable) {
        regFinishCycles := regFinishCycles - 1.U
      }
    }

    // Run DMA
    when (io.enable && active && isNotBlocked) {
      when (regStage === 0.U) {
        val complete = regCount === 0.U && !regInitial
        // Begin Load (if not end)
        busActive := !complete
        bus.ADDR := regSource
        bus.WRITE := false.B
        if (regSource.getWidth >= 28) {
          when (regSource(27)) {
            // TODO: this should probably only be set on bus.CLKEN
            regAccessedCart := true.B
          }
        }
        // Complete Store (if not initial)
        regSourceAddressHalfword := regSource(1)
        regSourceInvalid := regSource(regSource.getWidth - 1, 25) === 0.U

        // Check if the DMA is complete.
        when (bus.CLKEN) {
          when (complete) {
            logger.info(cf"${i}: complete")
            io.irq(i) := control.irq
            active := false.B
            regFinishCycles := 1.U
            when (control.repeat && isAudioFifo) {
              // Audio fifo is forced at 4 words and fixed destination
              regCount := 4.U
            } .elsewhen (control.repeat && control.startControl =/= DmaStartControl.immediate) {
              regCount := configCount
              when (control.destControl === DmaAddressControl.reload) {
                regDest := Cat(configDest(configDest.getWidth - 1, 2), configDest(1, 0) & addressMask)
              }
            } .otherwise {
              control.enable := false.B
            }
          } .otherwise {
            logger.debug(cf"${i}: load @ 0x${regSource}%x")
            regStage := 1.U
            regCount := regCount - 1.U

            // Game Pak ROM sources (0x08000000-0x0DFFFFFF) always increment, regardless of control.
            val sourceInRom = if (regSource.getWidth >= 28) {
              regSource(27) && regSource(26, 25) =/= 3.U
            } else {
              false.B
            }
            when (sourceInRom || control.sourceControl === DmaAddressControl.increment) {
              regSource := regSource + Mux(isSizeWord, 4.U, 2.U)
            } .elsewhen (control.sourceControl === DmaAddressControl.decrement) {
              regSource := regSource - Mux(isSizeWord, 4.U, 2.U)
            }
          }
        }
      } .otherwise {
        // Complete Load
        when (bus.CLKEN && !regSourceInvalid) {
          when (isSizeWord) {
            dataLatch := bus.RDATA
          } .otherwise {
            dataLatch := Fill(2, Mux(regSourceAddressHalfword.asBool, bus.RDATA(31, 16), bus.RDATA(15, 0)))
          }
        }

        // Begin Store
        busActive := true.B
        bus.ADDR := regDest
        bus.WRITE := true.B
        if (regDest.getWidth >= 28) {
          when (regDest(27)) {
            // TODO: this should probably only be set on bus.CLKEN
            regAccessedCart := true.B
          }
        }

        when (bus.CLKEN) {
          logger.debug(cf"${i}: store @ 0x${regDest}%x  (data = 0x${bus.RDATA}%x)")
          regStage := 0.U
          regInitial := false.B

          when (!isAudioFifo) {
            switch(control.destControl) {
              is(DmaAddressControl.increment, DmaAddressControl.reload) {
                regDest := regDest + Mux(isSizeWord, 4.U, 2.U)
              }
              is(DmaAddressControl.decrement) {
                regDest := regDest - Mux(isSizeWord, 4.U, 2.U)
              }
            }
          }
        }
      }
    }
  }
}