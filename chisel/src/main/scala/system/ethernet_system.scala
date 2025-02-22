// SPDX-License-Identifier: BSL-1.0
// Copyright Kenta Ida 2021.
// Distributed under the Boost Software License, Version 1.0.
//    (See accompanying file LICENSE_1_0.txt or copy at
//          https://www.boost.org/LICENSE_1_0.txt)

package system

import chisel3._
import chisel3.util._
import chisel3.experimental.chiselName
import chisel3.stage.ChiselStage
import ethernet._
import display._
import _root_.util._

@chiselName
class EthernetSystem() extends RawModule {
  val clock = IO(Input(Clock()))
  val aresetn = IO(Input(Bool()))
  
  val in_tdata = IO(Input(UInt(8.W)))
  val in_tvalid = IO(Input(Bool()))
  val in_tready = IO(Output(Bool()))
  val in_tlast = IO(Input(Bool()))

  val out_tdata = IO(Output(UInt(8.W)))
  val out_tvalid = IO(Output(Bool()))
  val out_tready = IO(Input(Bool()))
  val out_tlast = IO(Output(Bool()))

  val gpio_in = IO(Input(UInt(8.W)))
  val gpio_out = IO(Output(UInt(72.W)))

  val hub75io = IO(HUB75IO(2))

  withClockAndReset(clock, !aresetn) {
    val service = Module(new EthernetService)
    
    val rxQueue = Module(new Queue(Flushable(UInt(8.W)), 2048))
    rxQueue.io.deq.valid <> service.io.in.valid
    rxQueue.io.deq.ready <> service.io.in.ready
    rxQueue.io.deq.bits.data <> service.io.in.bits.data
    rxQueue.io.deq.bits.last <> service.io.in.bits.last
    service.io.in.bits.keep := 1.U

    val txPacketQueue = Module(new PacketQueue(Flushable(UInt(8.W)), 2048))
    txPacketQueue.io.write.valid <> service.io.out.valid
    txPacketQueue.io.write.ready <> service.io.out.ready
    txPacketQueue.io.write.bits.data <> service.io.out.bits.data
    txPacketQueue.io.write.bits.last <> service.io.out.bits.last

    rxQueue.io.enq.valid <> in_tvalid
    rxQueue.io.enq.ready <> in_tready
    rxQueue.io.enq.bits.data <> in_tdata
    rxQueue.io.enq.bits.last <> in_tlast

    txPacketQueue.io.read.valid <> out_tvalid
    txPacketQueue.io.read.ready <> out_tready
    txPacketQueue.io.read.bits.data <> out_tdata
    txPacketQueue.io.read.bits.last <> out_tlast

    val serviceMux = Module(new UdpServiceMux(1, Seq(
      (context => context.destinationPort === 10000.U),
      (context => context.destinationPort === 10001.U),
      (context => context.destinationPort === 10002.U),
    )))
    service.io.port <> serviceMux.io.in

    val udpLoopback = Module(new UdpLoopback)
    serviceMux.io.servicePorts(0) <> udpLoopback.io.port

    val udpGpio = Module(new UdpGpio(numOutputBits = 72))
    serviceMux.io.servicePorts(1) <> udpGpio.io.port
    gpio_out := udpGpio.io.gpioOut
    udpGpio.io.gpioIn := gpio_in

    val hub75 = Module(new HUB75Controller(2))
    val hub75PixelsUpper = Mem(64*16, UInt(3.W))
    val hub75PixelsLower = Mem(64*16, UInt(3.W))
    hub75.io.panelPixels(0).pixel := hub75PixelsUpper.read(hub75.io.panelPixels(0).address)
    hub75.io.panelPixels(1).pixel := hub75PixelsLower.read(hub75.io.panelPixels(1).address)
    val udpWriter = Module(new UdpMemoryWriter(numMemoryBytes = 64*32))
    serviceMux.io.servicePorts(2) <> udpWriter.io.port
    when( udpWriter.io.writeEnable ) {
      when(udpWriter.io.address < (64*16).U) {
        hub75PixelsUpper.write(udpWriter.io.address, udpWriter.io.data)
      } .elsewhen(udpWriter.io.address < (64*32).U) {
        hub75PixelsLower.write(udpWriter.io.address - (64*16).U, udpWriter.io.data)
      }
    }
    hub75io <> hub75.io.hub75
  }
}

object ElaborateEthernetSystem extends App {
  (new ChiselStage).emitVerilog(new EthernetSystem, Array(
    "-o", "ethernet_system.v",
    "--target-dir", "rtl/chisel/ethernet_system",
  ))
}
