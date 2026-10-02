package com.opendroidmic

interface AudioTransport : AutoCloseable {
    /** Starts the transport. Implementations may block while performing a handshake. */
    fun start()

    fun sendOpusFrame(frame: ByteArray)

    /** Performs keepalive/receive work. Returns false when the remote asks to stop. */
    fun maintain(): Boolean = true
}
