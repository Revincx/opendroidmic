package com.opendroidmic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class RtpOpusTransportTest {
    @Test
    fun `sends packetizer output as one UDP datagram`() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { receiver ->
            receiver.soTimeout = 2_000
            val packetizer = RtpOpusPacketizer(
                initialSequence = 7,
                initialTimestamp = 960,
                ssrc = 42
            )
            val transport = RtpOpusTransport(
                InetAddress.getLoopbackAddress().hostAddress ?: "127.0.0.1",
                receiver.localPort,
                packetizer
            )
            val opus = byteArrayOf(1, 2, 3, 4)

            transport.use {
                it.start()
                it.sendOpusFrame(opus)
            }

            val buffer = ByteArray(256)
            val received = DatagramPacket(buffer, buffer.size)
            receiver.receive(received)

            assertEquals(RtpOpusPacketizer.HEADER_SIZE + opus.size, received.length)
            assertArrayEquals(
                opus,
                received.data.copyOfRange(RtpOpusPacketizer.HEADER_SIZE, received.length)
            )
        }
    }
}
