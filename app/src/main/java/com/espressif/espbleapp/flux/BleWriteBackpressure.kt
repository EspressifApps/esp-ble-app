package com.espressif.espbleapp.flux

import kotlinx.coroutines.delay

/** Android exposes congestion signals, not the controller's free-buffer count. */
internal class BleWriteBackpressure(private val clockMs: () -> Long) {
    private var consecutiveCongestion = 0
    private var resumeAtMs = 0L

    @Synchronized
    fun onCongested(): Boolean {
        consecutiveCongestion++
        if (consecutiveCongestion > MAX_CONGESTION_CALLBACKS) return false
        val backoff = minOf(100L, 10L shl (consecutiveCongestion - 1).coerceAtMost(4))
        resumeAtMs = maxOf(resumeAtMs, clockMs() + backoff)
        return true
    }

    @Synchronized
    fun onWriteSucceeded() {
        consecutiveCongestion = 0
        // Older successful writes can finish during a congestion pause. Keep that pause.
    }

    @Synchronized
    fun remainingDelayMs(): Long = (resumeAtMs - clockMs()).coerceAtLeast(0L)

    suspend fun awaitReady() {
        while (true) {
            val remaining = remainingDelayMs()
            if (remaining == 0L) return
            delay(remaining)
        }
    }

    private companion object {
        const val MAX_CONGESTION_CALLBACKS = 20
    }
}
