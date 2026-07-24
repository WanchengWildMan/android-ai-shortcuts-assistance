package com.autoglm.assistant.glass

import com.autoglm.assistant.util.Logger
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.IAudioStreamCbk

class GlassAudioController(
    private val linkProvider: () -> CXRLink?,
    private val silenceThreshold: Int,
    private val silenceDurationMs: Long,
    private val maxDurationMs: Long,
    private val onPcmReady: (ByteArray) -> Unit,
    private val onError: (String) -> Unit
) {
    private var session: GlassPcmSession? = null

    private val callback = object : IAudioStreamCbk {
        override fun onAudioReceived(data: ByteArray?, offset: Int, length: Int) {
            if (data == null) return
            val pcm = session?.append(data, offset, length) ?: return
            finishStream(pcm)
        }

        override fun onAudioError(errorCode: Int, errorInfo: String?) {
            session = null
            onError("眼镜音频流错误 code=$errorCode info=${errorInfo.orEmpty()}")
        }

        override fun onAudioStreamStateChanged(started: Boolean) {
            Logger.i(Logger.GLASS, "眼镜音频流 started=$started")
        }
    }

    fun onAiInterrupt() {
        if (session != null) return
        val link = linkProvider() ?: run {
            onError("眼镜连接未就绪")
            return
        }
        session = GlassPcmSession(silenceThreshold, silenceDurationMs, maxDurationMs)
        link.setCXRAudioCbk(callback)
        if (!link.startAudioStream(1)) {
            session = null
            onError("启动眼镜音频流失败")
        }
    }

    fun stop() {
        val pcm = session?.finish()
        session = null
        runCatching { linkProvider()?.stopAudioStream() }
        if (pcm != null && pcm.isNotEmpty()) onPcmReady(pcm)
    }

    private fun finishStream(pcm: ByteArray) {
        session = null
        runCatching { linkProvider()?.stopAudioStream() }
        onPcmReady(pcm)
    }
}
