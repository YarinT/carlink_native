package com.carlink.audio

/**
 * Lock-free ring buffer for audio jitter compensation.
 *
 * PURPOSE:
 * Absorbs irregular packet arrival from USB (gaps of 500-1200ms observed)
 * by maintaining a buffer reserve. When packets arrive in bursts, they fill
 * the buffer; when gaps occur, playback continues from the buffer.
 *
 * THREAD SAFETY:
 * Designed for single-writer (USB thread), single-reader (audio playback thread).
 * Uses volatile variables for lock-free synchronization.
 *
 * BUFFER SIZING:
 * - Media stream: 200-300ms recommended (absorbs adapter gaps)
 * - Navigation stream: 100-150ms recommended (lower latency for prompts)
 *
 * @param capacityMs Buffer capacity in milliseconds
 * @param sampleRate Audio sample rate (e.g., 44100, 48000)
 * @param channels Number of audio channels (1=mono, 2=stereo)
 */
class AudioRingBuffer(
    private val capacityMs: Int,
    private val sampleRate: Int,
    private val channels: Int,
) {
    // Bytes per millisecond: sampleRate * channels * 2 (16-bit) / 1000
    private val bytesPerMs = (sampleRate * channels * 2) / 1000

    // Total buffer capacity in bytes
    private val capacity = capacityMs * bytesPerMs

    // The ring buffer storage
    private val buffer = ByteArray(capacity)

    // Write position (only modified by writer thread)
    @Volatile
    private var writePos = 0

    // Read position (only modified by reader thread)
    @Volatile
    private var readPos = 0

    // Statistics
    @Volatile
    var totalBytesWritten: Long = 0
        private set

    @Volatile
    var totalBytesRead: Long = 0
        private set

    @Volatile
    var overflowCount: Int = 0
        private set

    @Volatile
    var underflowCount: Int = 0
        private set

    @Volatile
    var discardedBytes: Long = 0
        private set

    /**
     * Write data to the ring buffer (non-blocking).
     *
     * Called from USB thread. Never blocks.
     *
     * For real-time audio, uses overwrite-oldest pattern: when buffer is full,
     * discards oldest data to make room for new data. This prevents data loss
     * during playback pauses while maintaining real-time behavior.
     *
     * @param data Audio data to write
     * @param offset Start offset in data array
     * @param length Number of bytes to write
     * @return Number of bytes actually written (always equals length for real-time audio)
     */
    fun write(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size - offset,
    ): Int {
        var available = availableForWrite()

        // Overwrite-oldest pattern for real-time audio:
        // If buffer is full/nearly full, discard oldest data to make room
        // This fixes the issue where buffer fills during track pause (Session 4 overflow=568)
        if (available < length) {
            val toDiscard = length - available
            // Advance read position to discard oldest data
            readPos = (readPos + toDiscard) % capacity
            discardedBytes += toDiscard
            overflowCount++
            available = length // Now we have room
        }

        val toWrite = minOf(length, available)
        val localWritePos = writePos

        // Calculate how much we can write before wrapping
        val firstChunk = minOf(toWrite, capacity - localWritePos)
        System.arraycopy(data, offset, buffer, localWritePos, firstChunk)

        // If we need to wrap around
        if (toWrite > firstChunk) {
            val secondChunk = toWrite - firstChunk
            System.arraycopy(data, offset + firstChunk, buffer, 0, secondChunk)
        }

        // Update write position (atomic for reader visibility)
        writePos = (localWritePos + toWrite) % capacity
        totalBytesWritten += toWrite

        return toWrite
    }

    /**
     * Read data from the ring buffer (non-blocking).
     *
     * Called from audio playback thread. Never blocks.
     *
     * @param out Output buffer to read into
     * @param offset Start offset in output buffer
     * @param length Maximum number of bytes to read
     * @return Number of bytes actually read (may be less than length if buffer empty)
     */
    fun read(
        out: ByteArray,
        offset: Int = 0,
        length: Int = out.size - offset,
    ): Int {
        val available = availableForRead()

        if (available == 0) {
            underflowCount++
            return 0
        }

        val toRead = minOf(length, available)
        val localReadPos = readPos

        // Calculate how much we can read before wrapping
        val firstChunk = minOf(toRead, capacity - localReadPos)
        System.arraycopy(buffer, localReadPos, out, offset, firstChunk)

        // If we need to wrap around
        if (toRead > firstChunk) {
            val secondChunk = toRead - firstChunk
            System.arraycopy(buffer, 0, out, offset + firstChunk, secondChunk)
        }

        // Update read position (atomic for writer visibility)
        readPos = (localReadPos + toRead) % capacity
        totalBytesRead += toRead

        return toRead
    }

    /**
     * Get the number of bytes available for reading.
     */
    fun availableForRead(): Int {
        val w = writePos
        val r = readPos
        return if (w >= r) w - r else capacity - r + w
    }

    /**
     * Get the number of bytes available for writing.
     */
    fun availableForWrite(): Int {
        // Leave one byte to distinguish full from empty
        return capacity - availableForRead() - 1
    }

    /**
     * Get buffer fill level as percentage (0.0 to 1.0).
     */
    fun fillLevel(): Float = availableForRead().toFloat() / capacity

    /**
     * Get buffer fill level in milliseconds.
     */
    fun fillLevelMs(): Int = if (bytesPerMs > 0) availableForRead() / bytesPerMs else 0

    /**
     * Check if buffer has enough data for stable playback.
     *
     * @param thresholdMs Minimum buffered audio in milliseconds
     */
    fun hasEnoughData(thresholdMs: Int = 50): Boolean = fillLevelMs() >= thresholdMs

    /**
     * Clear the buffer (reset to empty state).
     *
     * Should only be called when both threads are stopped.
     */
    fun clear() {
        writePos = 0
        readPos = 0
    }

    /**
     * Get statistics about the buffer.
     */
    fun getStats(): Map<String, Any> =
        mapOf(
            "capacityMs" to capacityMs,
            "capacityBytes" to capacity,
            "fillLevelMs" to fillLevelMs(),
            "fillPercent" to (fillLevel() * 100).toInt(),
            "totalBytesWritten" to totalBytesWritten,
            "totalBytesRead" to totalBytesRead,
            "overflowCount" to overflowCount,
            "underflowCount" to underflowCount,
            "discardedBytes" to discardedBytes,
            "sampleRate" to sampleRate,
            "channels" to channels,
        )

    companion object {
        /**
         * Create a ring buffer for media audio (larger buffer for jitter absorption).
         */
        fun forMedia(
            sampleRate: Int = 44100,
            channels: Int = 2,
        ): AudioRingBuffer {
            // 250ms buffer for media - larger for jitter absorption
            return AudioRingBuffer(
                capacityMs = 250,
                sampleRate = sampleRate,
                channels = channels,
            )
        }

        /**
         * Create a ring buffer for navigation audio (smaller buffer for lower latency).
         */
        fun forNavigation(
            sampleRate: Int = 44100,
            channels: Int = 2,
        ): AudioRingBuffer {
            // 120ms buffer for navigation - smaller for lower latency
            return AudioRingBuffer(
                capacityMs = 120,
                sampleRate = sampleRate,
                channels = channels,
            )
        }
    }
}
