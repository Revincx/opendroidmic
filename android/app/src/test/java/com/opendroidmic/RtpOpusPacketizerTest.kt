package com.opendroidmic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RtpOpusPacketizerTest {
    @Test
    fun `writes RFC 3550 header in network byte order`() {
        val packetizer = RtpOpusPacketizer(
            payloadType = 127,
            initialSequence = 0x1234,
            initialTimestamp = 0x10203040,
            ssrc = 0x50607080
        )
        val payload = byteArrayOf(0x11, 0x22, 0x33)

        val packet = packetizer.packetize(payload)
        val header = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)

        assertEquals(0x80, header.get().toInt() and 0xff)
        assertEquals(127, header.get().toInt() and 0x7f)
        assertEquals(0x1234, header.short.toInt() and 0xffff)
        assertEquals(0x10203040, header.int)
        assertEquals(0x50607080, header.int)
        assertArrayEquals(payload, packet.copyOfRange(RtpOpusPacketizer.HEADER_SIZE, packet.size))
    }

    @Test
    fun `increments and wraps sequence and timestamp`() {
        val packetizer = RtpOpusPacketizer(
            initialSequence = 0xffff,
            initialTimestamp = 0xffff_ff00,
            ssrc = 1
        )

        packetizer.packetize(byteArrayOf(1))
        val second = ByteBuffer.wrap(packetizer.packetize(byteArrayOf(2))).order(ByteOrder.BIG_ENDIAN)
        second.position(2)

        assertEquals(0, second.short.toInt() and 0xffff)
        assertEquals(0x0000_02c0, second.int)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects static payload type`() {
        RtpOpusPacketizer(payloadType = 95)
    }
}
