package com.opendroidmic

enum class TransportMode(
    val displayName: String,
    val defaultPort: Int
) {
    ODMC("OpenDroidMic", 38471),
    RTP_OPUS("RTP / Opus (PipeWire)", 38472);

    companion object {
        fun fromDisplayName(value: String): TransportMode =
            entries.firstOrNull { it.displayName == value } ?: ODMC
    }
}

enum class OpusBandwidthMode(val displayName: String) {
    AUTO("Auto"),
    WIDEBAND("Wideband (8 kHz)"),
    FULLBAND("Fullband (20 kHz)");

    companion object {
        fun fromDisplayName(value: String): OpusBandwidthMode =
            entries.firstOrNull { it.displayName == value } ?: AUTO
    }
}

enum class PickupMode(val displayName: String) {
    DESKTOP("Desktop"),
    CALL("Call"),
    NATIVE("Native");

    companion object {
        fun fromDisplayName(value: String): PickupMode =
            entries.firstOrNull { it.displayName == value } ?: DESKTOP
    }
}

data class CaptureSettings(
    val mode: PickupMode = PickupMode.DESKTOP,
    val noiseReduction: NoiseReductionMode = NoiseReductionMode.RNNOISE
)

enum class NoiseReductionMode {
    OFF,
    RNNOISE
}

data class OpusSettings(
    val bitrate: Int = DEFAULT_BITRATE,
    val bandwidth: OpusBandwidthMode = OpusBandwidthMode.AUTO
) {
    init {
        require(bitrate in BITRATE_OPTIONS) { "Unsupported Opus bitrate: $bitrate" }
    }

    companion object {
        const val DEFAULT_BITRATE = 32_000
        val BITRATE_OPTIONS = listOf(16_000, 24_000, 32_000, 48_000, 64_000, 96_000, 128_000)
    }
}

data class StreamConfig(
    val host: String,
    val port: Int,
    val transport: TransportMode = TransportMode.ODMC,
    val opus: OpusSettings = OpusSettings(),
    val capture: CaptureSettings = CaptureSettings()
) {
    init {
        require(host.isNotBlank()) { "Host must not be blank" }
        require(port in 1..65535) { "Port must be between 1 and 65535" }
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNEL_COUNT = 1
        const val FRAME_SIZE = 960
        const val FRAME_DURATION_MS = 20
    }
}
