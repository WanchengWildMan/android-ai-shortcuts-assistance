package com.autoglm.assistant.glass

import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

class GlassPcmSession(
    private val silenceThreshold: Int,
    private val silenceDurationMs: Long,
    private val maxDurationMs: Long
) {
    private val output = ByteArrayOutputStream()
    private var startedAtMs: Long? = null
    private var silenceStartedAtMs: Long? = null

    var hasSpeech: Boolean = false
        private set

    fun append(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size,
        nowMs: Long = System.currentTimeMillis()
    ): ByteArray? {
        if (offset !in data.indices || length <= 0 || offset + length > data.size) return null
        if (startedAtMs == null) startedAtMs = nowMs
        output.write(data, offset, length)

        val rms = calculateRms(data, offset, length)
        if (rms > silenceThreshold) {
            hasSpeech = true
            silenceStartedAtMs = null
        } else if (hasSpeech) {
            val silenceStart = silenceStartedAtMs
            if (silenceStart == null) {
                silenceStartedAtMs = nowMs
            } else if (nowMs - silenceStart >= silenceDurationMs) {
                return finish()
            }
        }

        if (nowMs - (startedAtMs ?: nowMs) >= maxDurationMs) return finish()
        return null
    }

    fun finish(): ByteArray = output.toByteArray()

    private fun calculateRms(data: ByteArray, offset: Int, length: Int): Double {
        val sampleCount = length / 2
        if (sampleCount == 0) return 0.0
        var sum = 0.0
        var index = offset
        repeat(sampleCount) {
            val sample = ((data[index + 1].toInt() shl 8) or (data[index].toInt() and 0xff)).toShort()
            sum += sample.toDouble() * sample.toDouble()
            index += 2
        }
        return sqrt(sum / sampleCount)
    }
}
