package com.opendroidmic

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class RtpOpusTransport(
    host: String,
    private val port: Int,
    private val packetizer: RtpOpusPacketizer = RtpOpusPacketizer()
) : AudioTransport {
    private val address = InetAddress.getByName(host)
    private val socket = DatagramSocket()

    override fun start() {
        // RTP/UDP is connectionless and has no OpenDroidMic handshake.
    }

    override fun sendOpusFrame(frame: ByteArray) {
        val data = packetizer.packetize(frame)
        socket.send(DatagramPacket(data, data.size, address, port))
    }

    override fun close() {
        socket.close()
    }
}
