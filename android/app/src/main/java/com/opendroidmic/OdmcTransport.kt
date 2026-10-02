package com.opendroidmic

import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom

class OdmcTransport(
    host: String,
    private val port: Int,
    private val sessionToken: Long = random.nextLong()
) : AudioTransport {
    companion object {
        private const val TAG = "OdmcTransport"
        private const val HELLO_ACK_TIMEOUT_MS = 3_000L
        private const val PING_INTERVAL_MS = 5_000L
        private const val PONG_TIMEOUT_MS = 5_000L
        private val random = SecureRandom()
    }

    private val address = InetAddress.getByName(host)
    private val socket = DatagramSocket()
    private val receiveBuffer = ByteArray(Protocol.MAX_PACKET_SIZE)
    private var sequence = 0
    private var lastPingTime = 0L
    private var pingSentAt = 0L
    private var waitingForPong = false

    override fun start() {
        send(Protocol.createHello(sessionToken))
        Log.d(TAG, "Sent HELLO (token=${sessionToken.toString(16)})")

        val deadline = System.currentTimeMillis() + HELLO_ACK_TIMEOUT_MS
        socket.soTimeout = 500
        while (System.currentTimeMillis() < deadline) {
            try {
                val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)
                socket.receive(packet)
                val parsed = Protocol.parse(receiveBuffer, packet.length)
                if (parsed?.type == Protocol.TYPE_HELLO_ACK) {
                    socket.soTimeout = 1
                    lastPingTime = System.currentTimeMillis()
                    return
                }
            } catch (_: SocketTimeoutException) {
                // Continue until the handshake deadline.
            }
        }
        throw IOException("HELLO_ACK timeout")
    }

    override fun sendOpusFrame(frame: ByteArray) {
        val timestampMs = sequence * StreamConfig.FRAME_DURATION_MS
        send(Protocol.createAudio(sequence, timestampMs, frame))
        sequence++
    }

    override fun maintain(): Boolean {
        val now = System.currentTimeMillis()
        if (!waitingForPong && now - lastPingTime >= PING_INTERVAL_MS) {
            send(Protocol.createPing(sequence, sequence * StreamConfig.FRAME_DURATION_MS))
            lastPingTime = now
            pingSentAt = now
            waitingForPong = true
        }

        if (waitingForPong && now - pingSentAt >= PONG_TIMEOUT_MS) {
            throw IOException("PONG timeout")
        }

        try {
            val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)
            socket.receive(packet)
            val parsed = Protocol.parse(receiveBuffer, packet.length)
            when (parsed?.type) {
                Protocol.TYPE_PONG -> waitingForPong = false
                Protocol.TYPE_STOP -> return false
            }
        } catch (_: SocketTimeoutException) {
            // No control packet is waiting.
        }
        return true
    }

    private fun send(data: ByteArray) {
        socket.send(DatagramPacket(data, data.size, address, port))
    }

    override fun close() {
        if (!socket.isClosed) {
            try {
                send(Protocol.createStop(sessionToken))
            } catch (_: Exception) {
                // Best-effort shutdown packet.
            }
            socket.close()
        }
    }
}
