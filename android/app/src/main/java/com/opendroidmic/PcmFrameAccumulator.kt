package com.opendroidmic

/** Collects partial AudioRecord reads into exact Opus frames. */
class PcmFrameAccumulator(private val frameSize: Int) {
    private var frame = ShortArray(frameSize)
    private var filled = 0

    init {
        require(frameSize > 0) { "Frame size must be positive" }
    }

    fun append(samples: ShortArray, sampleCount: Int, onFrame: (ShortArray) -> Unit) {
        require(sampleCount in 0..samples.size) { "Invalid sample count: $sampleCount" }

        var sourceOffset = 0
        while (sourceOffset < sampleCount) {
            val copyCount = minOf(frameSize - filled, sampleCount - sourceOffset)
            samples.copyInto(frame, destinationOffset = filled, startIndex = sourceOffset, endIndex = sourceOffset + copyCount)
            sourceOffset += copyCount
            filled += copyCount

            if (filled == frameSize) {
                val completedFrame = frame
                frame = ShortArray(frameSize)
                filled = 0
                onFrame(completedFrame)
            }
        }
    }

    fun reset() {
        frame = ShortArray(frameSize)
        filled = 0
    }
}
