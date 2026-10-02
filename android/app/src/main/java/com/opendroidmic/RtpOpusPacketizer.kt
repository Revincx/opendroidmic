package com.opendroidmic

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

class RtpOpusPacketizer(
    val payloadType: Int = DEFAULT_PAYLOAD_TYPE,
    initialSequence: Int = random.nextInt(1 shl 16),
    initialTimestamp: Long = random.nextLong().toUInt().toLong(),
    val ssrc: Long = random.nextLong().toUInt().toLong()
) {
    companion object {
        const val HEADER_SIZE = 12
        const val DEFAULT_PAYLOAD_TYPE = 127
        const val TIMESTAMP_STEP = StreamConfig.FRAME_SIZE
        private const val UINT32_MASK = 0xffff_ffffL
        private val random = SecureRandom()
    }

    private var sequence = initialSequence and 0xffff
    private var timestamp = initialTimestamp and UINT32_MASK

    init {
        require(payloadType in 96..127) { "RTP payload type must be dynamic (96-127)" }
    }

    fun packetize(opusFrame: ByteArray): ByteArray {
        require(opusFrame.isNotEmpty()) { "Opus frame must not be empty" }

        val packet = ByteBuffer.allocate(HEADER_SIZE + opusFrame.size).order(ByteOrder.BIG_ENDIAN)
        packet.put(0x80.toByte()) // RTP v2, no padding/extension/CSRC.
        packet.put(payloadType.toByte()) // Marker is zero.
        packet.putShort(sequence.toShort())
        packet.putInt(timestamp.toInt())
        packet.putInt(ssrc.toInt())
        packet.put(opusFrame)

        sequence = (sequence + 1) and 0xffff
        timestamp = (timestamp + TIMESTAMP_STEP) and UINT32_MASK
        return packet.array()
    }
}
