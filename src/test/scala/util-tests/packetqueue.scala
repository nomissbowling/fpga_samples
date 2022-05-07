// SPDX-License-Identifier: BSL-1.0
// Copyright Kenta Ida 2021-2022
// Distributed under the Boost Software License, Version 1.0.
//    (See accompanying file LICENSE_1_0.txt or copy at
//          https://www.boost.org/LICENSE_1_0.txt)

package util

import org.scalatest._
import chiseltest._
import chisel3.util._
import chisel3._
import chisel3.experimental.BundleLiterals._
import scala.util.control.Breaks
import scala.util.Random
import chisel3.stage.PrintFullStackTraceAnnotation
import chiseltest.experimental.TestOptionBuilder._

class PacketQueueTester extends FlatSpec with ChiselScalatestTester with Matchers {
    val dutName = "PacketQueue"
    behavior of dutName

    def push8pop8(c: PacketQueue[UInt]) {
        c.io.write.initSource().setSourceClock(c.clock)
        c.io.read.initSink().setSinkClock(c.clock)

        val dataType = Flushable(0.U(8.W))

        // Push 8 entries
        (0 to 7).foreach(i => {
            c.io.write.ready.expect(true.B)
            c.io.read.valid.expect(false.B)
            c.io.write.enqueue(dataType.Lit( _.body -> i.U(8.W), _.last -> (i == 7).B ) )
        })
        c.io.write.ready.expect((c.entries != 8).B) // READY must be asserted if a packet is received.
        c.clock.step(1)
        // Pop 8 entries
        (0 to 7).foreach(i => {
            c.io.read.valid.expect(true.B)
            c.io.read.expectDequeue(dataType.Lit( _.body -> i.U(8.W), _.last -> (i == 7).B ) )
        })
        c.io.read.valid.expect(false.B)
    }

    def pushFull(c: PacketQueue[UInt]) {
        c.io.write.initSource().setSourceClock(c.clock)
        c.io.read.initSink().setSinkClock(c.clock)

        val dataType = Flushable(0.U(8.W))
        val entries = c.entries
        val lastEntry = entries - 1
        // Push to fill the all entries.
        (0 to lastEntry).foreach(i => {
            c.io.write.ready.expect(true.B)
            c.io.read.valid.expect(false.B)
            c.io.write.enqueue(dataType.Lit( _.body -> i.U(8.W), _.last -> false.B ) )
        })
        c.clock.step(1)
        (0 to lastEntry).foreach(i => {
            c.io.read.valid.expect(true.B)
            c.io.read.expectDequeue(dataType.Lit( _.body -> i.U(8.W), _.last -> false.B ) )
        })
        c.io.read.valid.expect(false.B)
    }

    def pushFullTwoPacket(c: PacketQueue[UInt]) {
        c.io.write.initSource().setSourceClock(c.clock)
        c.io.read.initSink().setSinkClock(c.clock)

        val dataType = Flushable(0.U(8.W))
        val entries = c.entries
        val lastEntry = entries - 1
        val midEntry = entries/2 - 1
        // Push to fill the all entries with two packets.
        (0 to lastEntry).foreach(i => {
            c.io.write.ready.expect(true.B)
            c.io.read.valid.expect((i > midEntry).B)
            c.io.write.enqueue(dataType.Lit( _.body -> i.U(8.W), _.last -> (i ==  midEntry).B ) )
        })
        // Pop the all entries in the queue and fill
        (0 to midEntry).foreach(i => {
            c.io.read.expectDequeue(dataType.Lit( _.body -> i.U(8.W), _.last -> (i == midEntry).B ) )
            c.io.write.enqueueNow(dataType.Lit( _.body -> (entries + i).U(8.W), _.last -> false.B ))
        })
        ((midEntry + 1) to (lastEntry + midEntry + 1)).foreach(i => {
            c.io.read.expectDequeue(dataType.Lit( _.body -> i.U(8.W), _.last -> false.B ) )
        })

        c.io.read.valid.expect(false.B)
    }

    def pushTwoPackets(c: PacketQueue[UInt]) {
        c.io.write.initSource().setSourceClock(c.clock)
        c.io.read.initSink().setSinkClock(c.clock)

        val dataType = Flushable(0.U(8.W))
        val entries = c.entries
        val lastEntry = entries - 1
        val midEntry = entries/2 - 1
        // Push to fill the first packet.
        (0 to midEntry).foreach(i => {
            c.io.write.ready.expect(true.B)
            c.io.read.valid.expect(false.B)
            c.io.write.enqueue(dataType.Lit( _.body -> i.U(8.W), _.last -> (i ==  midEntry).B ) )
        })
        // Push to fill the last packet.
        (midEntry + 1 to lastEntry - 1).foreach(i => {
            c.io.write.ready.expect(true.B)
            c.io.read.valid.expect(true.B)
            c.io.write.enqueue(dataType.Lit( _.body -> i.U(8.W), _.last -> false.B ) )
        })
        // Now, the queue cannot receive the entry if it is the last entry of the packet.
        // c.io.write.ready.expect(true.B)
        // c.io.write.bits.poke(dataType.Lit( _.body -> 0.U(8.W), _.last -> true.B))
        // c.clock.step(1)
        // c.io.write.ready.expect(false.B)

        // Pop the first packet entries in the queue and fill
        (0 to midEntry).foreach(i => {
            c.io.read.expectDequeue(dataType.Lit( _.body -> i.U(8.W), _.last -> (i == midEntry).B ) )
        })
        // Now, the last packet can be pushed.
        c.io.write.enqueue(dataType.Lit(_.body -> lastEntry.U(8.W), _.last -> true.B))
        (midEntry + 1 to lastEntry).foreach(i => {
            c.io.write.ready.expect(true.B)
            c.io.read.expectDequeue(dataType.Lit( _.body -> i.U(8.W), _.last -> (i == lastEntry).B ) )
        })

        c.io.read.valid.expect(false.B)
    }

    it should "push8pop8 depth 8" in {
        test(new PacketQueue(Flushable(0.U(8.W)), 8)).withAnnotations(Seq(PrintFullStackTraceAnnotation))  { c => push8pop8(c) }
    }
    it should "push8pop8 depth 9" in {
        test(new PacketQueue(Flushable(0.U(8.W)), 9)).withAnnotations(Seq(PrintFullStackTraceAnnotation))  { c => push8pop8(c) }
    }
    it should "push8pop8 depth 16" in {
        test(new PacketQueue(Flushable(0.U(8.W)), 16)).withAnnotations(Seq(PrintFullStackTraceAnnotation))  { c => push8pop8(c) }
    }
    it should "pushFull depth 8" in {
        test(new PacketQueue(Flushable(0.U(8.W)), 8)).withAnnotations(Seq(PrintFullStackTraceAnnotation))  { c => pushFull(c) }
    }
    it should "pushFull depth 9" in {
        test(new PacketQueue(Flushable(0.U(8.W)), 9)).withAnnotations(Seq(PrintFullStackTraceAnnotation))  { c => pushFull(c) }
    }
    it should "pushFullTwoPacket depth 8" in {
        test(new PacketQueue(Flushable(0.U(8.W)), 8)).withAnnotations(Seq(PrintFullStackTraceAnnotation))  { c => pushFullTwoPacket(c) }
    }
    it should "pushFullTwoPacket depth 9" in {
        test(new PacketQueue(Flushable(0.U(8.W)), 9)).withAnnotations(Seq(PrintFullStackTraceAnnotation))  { c => pushFullTwoPacket(c) }
    }
    it should "pushTwoPackets depth 8" in {
        test(new PacketQueue(Flushable(0.U(8.W)), 8)).withAnnotations(Seq(PrintFullStackTraceAnnotation))  { c => pushTwoPackets(c) }
    }
    it should "pushTwoPackets depth 9" in {
        test(new PacketQueue(Flushable(0.U(8.W)), 9)).withAnnotations(Seq(PrintFullStackTraceAnnotation))  { c => pushTwoPackets(c) }
    }
}
