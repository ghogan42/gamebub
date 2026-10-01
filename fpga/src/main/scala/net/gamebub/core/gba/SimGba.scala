package net.gamebub.core.gba

import chisel3._
import _root_.circt.stage.ChiselStage
import chisel3.util.{Cat, SRAM}
import gba._
import gba.apu.ApuOutput
import gba.cart.emu.{EmulatedCartridge, VerificationEmulatedCartridge}
import gba.ppu.PpuOutput
import lib.log.{Log, Logger}
import lib.mem.{MemoryInterface, PipelineMemoryInterface}

object SimGba extends App {
  Log.setDefaultLevel(Log.Level.Warning)
  sys.env.get("LOG_LEVELS") match {
    case Some(value) => Log.setLevelsFromString(value)
    case None =>
  }

  // SIM_PHYSICAL_CART=1: serve ROM like a physical cartridge (see SimGba.physicalCart).
  val physicalCart = sys.env.get("SIM_PHYSICAL_CART").contains("1")
  // SIM_SLOW_CART=1 (with SIM_PHYSICAL_CART): model a slower cartridge chip/bus turnaround.
  val slowCart = sys.env.get("SIM_SLOW_CART").contains("1")
  // SIM_PHYSICAL_FLASH=1 (with SIM_PHYSICAL_CART): cart Flash save is a physical MX29L010 model.
  val physicalFlash = sys.env.get("SIM_PHYSICAL_FLASH").contains("1")

  ChiselStage.emitSystemVerilogFile(
    new SimGba(physicalCart, slowCart, physicalFlash),
    args,
    firtoolOpts = Array(
      "--preserve-aggregate=1d-vec",
      "-enable-layers=Verification",
      "-enable-layers=Verification.Assert",
      "-enable-layers=Verification.Assume",
      "-enable-layers=Verification.Cover",
    )
  )
}

/// @param physicalCart Run the cartridge controller in physical-cartridge mode (isEmulated = false)
///   with ROM data served from the physical bus signals (address latched on nCS falling edge,
///   incremented on nRD rising edge), like a real Game Pak. Backup memory and GPIO still come from
///   the emulated cartridge.
///   slowCart: (physical mode) ROM data is only valid once nRD has been low for 2 cycles, or 1 cycle
///   if the data bus was already an input (no bus turnaround) -- garbage otherwise.
///   physicalFlash: (physical mode) the cart's Flash save is modelled from the physical bus signals
///   like a Macronix MX29L010 (command state machine, ID C2/09, empty contents). Conservative timing:
///   the address is latched when /WE (or /CE) falls only if it was already driven and stable in the
///   previous cycle; data is latched from the last cycle /WE is low.
class SimGba(physicalCart: Boolean = false, slowCart: Boolean = false, physicalFlash: Boolean = false) extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
    val ppu = Output(new PpuOutput)
    val keypad = Input(new Keypad.State)
    val apu = Output(new ApuOutput)

    val configGBPlayer = Input(Bool())

    val emuCartConfig = Input(new EmulatedCartridge.Config)
    val emuCartRomSize = Input(UInt(25.W))
    val emuCartBackup = Flipped(new MemoryInterface(addressWidth = 17, dataWidth = 8))
    val emuCartStall = Output(Bool())
  })

  // Cycle markers for trace logs (only emitted when LOG_LEVELS enables sim.cycle:debug).
  val cycleLogger = Logger("sim.cycle", enable = io.enable)
  val regCycle = RegInit(0.U(32.W))
  when (io.enable) {
    regCycle := regCycle + 1.U
  }
  cycleLogger.debug(cf"---- cycle ${regCycle}")

  val gba = Module(new GBA())
  gba.io.enable <> io.enable
  gba.io.configGBPlayer := io.configGBPlayer
  gba.io.ppu <> io.ppu
  gba.io.keypad <> io.keypad
  gba.io.apu <> io.apu

  // BIOS, to be filled in by verilator simulator
  val biosRom = {
    val rom = SyncReadMem(16 * 1024 / 4, UInt(32.W))
    // dontTouch: hack to ensure Chisel doesn't optimize the mem out
    val temp = dontTouch(WireDefault(false.B))
    when (temp) {
      rom.write(0.U, 0.U)
    }
    rom
  }
  gba.io.biosRom.data := biosRom.read(gba.io.biosRom.address, gba.io.biosRom.read)

  // EWRAM
  val ewram = SRAM.masked(128 * 1024, Vec(2, UInt(8.W)), numReadPorts = 0, numWritePorts = 0, numReadwritePorts = 1)
  val ewramPort = ewram.readwritePorts(0)
  ewramPort.address := gba.io.ewram.address
  ewramPort.enable := gba.io.ewram.enable
  ewramPort.isWrite := gba.io.ewram.write
  ewramPort.mask.get := gba.io.ewram.writeStrobe.asBools
  ewramPort.writeData := gba.io.ewram.dataWrite.asTypeOf(ewramPort.writeData)
  gba.io.ewram.dataRead := ewramPort.readData.asUInt
  gba.io.ewram.done := true.B

  // Emulated cartridge
  val emuCart = Module(new EmulatedCartridge)
  emuCart.io.config := io.emuCartConfig
  emuCart.io.romSize := io.emuCartRomSize
  emuCart.io.imuAccelX := 0x3A0.U
  emuCart.io.imuAccelY := 0x3A0.U
  emuCart.io.imuGyroZ := DontCare  // No gyro support in simulator
  emuCart.io.rtcDataWrite := false.B
  emuCart.io.rtcDataIn := DontCare
  emuCart.io.rtcDataSelect := DontCare
  io.emuCartStall := emuCart.io.stall
  gba.io.cartridge <> emuCart.io.interface
  io.emuCartBackup <> emuCart.io.backup
  emuCart.io.interfaceEnable := gba.io.enable

  // Cartridge ROM, to be filled in by verilator simulation
  val cartRom = {
    val rom = SyncReadMem(32 * 1024 * 1024 / 2, UInt(16.W))
    // dontTouch: hack to ensure Chisel doesn't optimize the mem out
    val temp = dontTouch(WireDefault(false.B))
    when (temp) {
      rom.write(0.U, 0.U)
    }
    rom
  }
  // Simulate ROM access
  val romBusy = RegInit(false.B)
  val romBusyAddress = Reg(UInt(24.W))
  val romCounter = Reg(UInt(4.W))
  val romAccessWaitCycles = 1 // minimum 1
  emuCart.io.rom.ready := true.B
  when (romBusy) {
    emuCart.io.rom.ready := false.B
    romCounter := romCounter - 1.U
    when (romCounter === 0.U) {
      romBusy := false.B
      emuCart.io.rom.ready := true.B
    }
  }
  when (emuCart.io.rom.enable && emuCart.io.rom.ready) {
    romCounter := romAccessWaitCycles.U
    romBusy := true.B
    romBusyAddress := emuCart.io.rom.address
  }
  emuCart.io.rom.dataRead := cartRom.read(romBusyAddress, romBusy && romCounter === 1.U)

  if (physicalCart) {
    val cart = gba.io.cartridge
    val prevNCS = RegNext(cart.nCS, true.B)
    val prevNRD = RegNext(cart.nRD, true.B)
    // Like a real Game Pak: A0-15 are latched when nCS falls and auto-increment on each nRD rising
    // edge; A16-23 are live address lines, valid from when nCS falls (constant within a burst, which
    // never crosses a 128 KiB page).
    val regLatchedLow = RegInit(0.U(16.W))
    val nextLatchedLow = WireDefault(regLatchedLow)
    val regHigh = RegInit(0.U(8.W))
    val nextHigh = WireDefault(regHigh)
    when (!cart.nCS && prevNCS) {
      nextLatchedLow := cart.ADLoOut
      nextHigh := cart.AHiOut
    } .elsewhen (!cart.nCS && cart.nRD && !prevNRD) {
      nextLatchedLow := regLatchedLow + 1.U
    }
    regLatchedLow := nextLatchedLow
    regHigh := nextHigh
    val regLatchedAddress = Cat(regHigh, regLatchedLow)
    // Read ahead so the data for the latched address is valid the cycle nRD goes low (async ROM).
    val romData = cartRom.read(Cat(nextHigh, nextLatchedLow), true.B)
    // Per-cycle cartridge bus waveform (only when LOG_LEVELS enables sim.cartbus:debug).
    Logger("sim.cartbus", enable = io.enable).debug(
      cf"CS=${cart.nCS} CS2=${cart.nCS2} RD=${cart.nRD} WR=${cart.nWR} " +
      cf"AD=${Mux(cart.ADLoDir, cart.ADLoOut, cart.ADLoIn)}%x out=${cart.ADLoDir} " +
      cf"AH=${Mux(cart.AHiDir, cart.AHiOut, cart.AHiIn)}%x out=${cart.AHiDir}")
    val gpioAddress = regLatchedAddress >= (0xC2 / 2).U && regLatchedAddress <= (0xC8 / 2).U
    cart.isEmulated := false.B
    // Beyond the end of the ROM, the bus floats to the latched (halfword) address.
    val outOfBounds = Cat(regLatchedAddress, 0.U(1.W)) > io.emuCartRomSize
    val romCartData = Mux(outOfBounds, regLatchedLow, romData)
    val romBusData = WireDefault(romCartData)
    if (slowCart) {
      val regReadLowCycles = RegInit(0.U(4.W))
      regReadLowCycles := Mux(cart.nRD, 0.U, Mux(regReadLowCycles.andR, regReadLowCycles, regReadLowCycles + 1.U))
      val prevDataDirOut = RegNext(cart.ADLoDir, true.B)
      val readLowCycles = regReadLowCycles + 1.U  // including this cycle
      val valid = readLowCycles >= 2.U || !prevDataDirOut
      when (!valid) {
        romBusData := ~romCartData
      }
    }
    when (!cart.nCS && !cart.nRD && !gpioAddress) {
      cart.ADLoIn := romBusData
    }

    if (physicalFlash) {
      val flashLogger = Logger("sim.flash", enable = io.enable)
      val ce = !cart.nCS2
      val we = !cart.nWR
      val oe = !cart.nRD
      val prevActive = RegNext(ce && we, false.B)
      val prevAdDriven = RegNext(cart.ADLoDir, false.B)
      val prevAd = RegNext(Mux(cart.ADLoDir, cart.ADLoOut, cart.ADLoIn))
      val regAddress = Reg(UInt(16.W))
      val regData = Reg(UInt(8.W))
      val regIdMode = RegInit(false.B)
      val regStep = RegInit(0.U(2.W))     // AA / 55 unlock progress
      val regBankNext = RegInit(false.B)  // next write selects the bank
      // Address latched on the later of /CE and /WE falling.
      when (ce && we && !prevActive) {
        val stable = prevAdDriven && cart.ADLoDir && prevAd === cart.ADLoOut
        regAddress := Mux(stable, cart.ADLoOut, prevAd)
        when (!stable) {
          flashLogger.error(cf"FLASH: address not set up before /WE fell (prev=${prevAd}%x driven=${prevAdDriven} now=${cart.ADLoOut}%x)")
        }
      }
      when (ce && we) {
        regData := Mux(cart.AHiDir, cart.AHiOut, 0xFF.U)
      }
      // Command processed when the write ends (earlier of /CE and /WE rising).
      when (prevActive && !(ce && we)) {
        val a = regAddress
        val d = regData
        flashLogger.debug(cf"FLASH write addr=${a}%x data=${d}%x step=${regStep} id=${regIdMode}")
        regStep := 0.U
        when (regBankNext) {
          regBankNext := false.B
        } .elsewhen (d === 0xF0.U) {
          regIdMode := false.B
        } .elsewhen (regStep === 0.U && a === 0x5555.U && d === 0xAA.U) {
          regStep := 1.U
        } .elsewhen (regStep === 1.U && a === 0x2AAA.U && d === 0x55.U) {
          regStep := 2.U
        } .elsewhen (regStep === 2.U && a === 0x5555.U) {
          when (d === 0x90.U) { regIdMode := true.B }
          when (d === 0xB0.U) { regBankNext := true.B }
        }
      }
      when (ce && oe) {
        val a = cart.ADLoOut
        cart.AHiIn := Mux(regIdMode, Mux(a === 0.U, 0xC2.U, Mux(a === 1.U, 0x09.U, 0xFF.U)), 0xFF.U)
      }
    }
  }

  // Sim-only check: ROM data completed by the cartridge controller must match the ROM image
  // (skipping out-of-bounds reads and addresses that may hit emulated peripherals).
  {
    import chisel3.util.experimental.BoringUtils
    val done = BoringUtils.bore(gba.cart.isRequestDone)
    val data = BoringUtils.bore(gba.cart.io.busTargetRom.dataRead)
    val address = BoringUtils.bore(gba.cart.currentAddress)
    val isWrite = BoringUtils.bore(gba.cart.currentIsWrite)
    val port = BoringUtils.bore(gba.cart.currentRequestPort)
    val check = io.enable && done && !isWrite && Cat(address, 0.U(1.W)) <= io.emuCartRomSize &&
      address(23, 7) =/= 0.U && !port(2)
    val expected = cartRom.read(address, check)
    val regCheck = RegNext(check, false.B)
    val regData = RegNext(data)
    val regAddress = RegNext(address)
    when (regCheck && expected =/= regData) {
      printf(cf"ROMCHECK mismatch addr=0x${regAddress << 1}%x got=0x${regData}%x expected=0x${expected}%x\n")
    }
  }

  // Sim-only check at the system bus: ROM data returned to the CPU/DMA must match the ROM image.
  {
    import chisel3.util.experimental.BoringUtils
    val accessDone = BoringUtils.bore(gba.bus.accessDone)
    val busy = BoringUtils.bore(gba.bus.regAccessBusy)
    val address = BoringUtils.bore(gba.bus.regAccessAddress)
    val isWrite = BoringUtils.bore(gba.bus.regAccessWrite)
    val split = BoringUtils.bore(gba.bus.regAccessSplit)
    val phase = BoringUtils.bore(gba.bus.regAccessSplitPhase)
    val wide = BoringUtils.bore(gba.bus.wideDoneNow)
    val wideData = BoringUtils.bore(gba.bus.wideDataRead)
    val data = BoringUtils.bore(gba.bus.requestDataRead)
    val inRom = address(31, 28) === 0.U && address(27, 25) >= 4.U && address(27, 25) <= 5.U
    val halfAddr = Mux(split && phase === 0.U, address(24, 2) ## 0.U(1.W), address(24, 1))
    val check = io.enable && busy && accessDone && !isWrite && inRom &&
      Cat(halfAddr, 0.U(1.W)) <= io.emuCartRomSize && halfAddr(23, 7) =/= 0.U
    val expected = cartRom.read(halfAddr(23, 0), check)
    val expected2 = cartRom.read(halfAddr(23, 0) + 1.U, check)
    val regCheck = RegNext(check, false.B)
    val regWide = RegNext(wide)
    val regData = RegNext(Mux(wide, wideData, data))
    val regAddress = RegNext(halfAddr)
    val mismatch = Mux(regWide, regData =/= Cat(expected2, expected), regData(15, 0) =/= expected)
    when (regCheck && mismatch) {
      printf(cf"BUSCHECK cycle=${regCycle} mismatch addr=0x${regAddress << 1}%x wide=${regWide} got=0x${regData}%x expected=0x${expected2}%x${expected}%x\n")
    }
  }


  // Link
  // No cable: link port inputs are pulled high.
  gba.io.link.in.si := true.B
  gba.io.link.in.so := true.B
  gba.io.link.in.sc := true.B
  gba.io.link.in.sd := true.B
}