package com.autoglm.assistant.glass

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassPcmSessionTest {
    @Test
    fun `检测到语音后的持续静音会完成会话`() {
        val session = GlassPcmSession(
            silenceThreshold = 800,
            silenceDurationMs = 1000,
            maxDurationMs = 15000
        )

        assertNull(session.append(pcm(amplitude = 2000), nowMs = 0))
        assertNull(session.append(pcm(amplitude = 0), nowMs = 100))
        val result = session.append(pcm(amplitude = 0), nowMs = 1200)

        assertTrue(result != null)
        assertTrue(result!!.isNotEmpty())
    }

    @Test
    fun `没有检测到语音时静音不会提前完成`() {
        val session = GlassPcmSession(800, 1000, 15000)

        assertNull(session.append(pcm(0), nowMs = 0))
        assertNull(session.append(pcm(0), nowMs = 3000))
        assertFalse(session.hasSpeech)
    }

    @Test
    fun `无效区间不会写入音频`() {
        val session = GlassPcmSession(800, 1000, 15000)
        val source = pcm(2000)

        session.append(source, offset = source.size + 1, length = source.size, nowMs = 0)

        assertArrayEquals(byteArrayOf(), session.finish())
    }

    private fun pcm(amplitude: Short): ByteArray = ByteArray(320).also { bytes ->
        var index = 0
        while (index < bytes.size) {
            bytes[index] = (amplitude.toInt() and 0xff).toByte()
            bytes[index + 1] = (amplitude.toInt() shr 8 and 0xff).toByte()
            index += 2
        }
    }
}
