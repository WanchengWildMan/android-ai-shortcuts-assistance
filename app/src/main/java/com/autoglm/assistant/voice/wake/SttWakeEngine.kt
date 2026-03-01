package com.autoglm.assistant.voice.wake

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * STT 文本匹配唤醒引擎抽象基类
 *
 * 通过持续语音识别，匹配唤醒词文本来触发唤醒
 * 支持精确匹配和正则表达式匹配
 */
abstract class SttWakeEngine(
    protected val context: Context
) : WakeEngine {

    companion object {
        private const val TAG = "WakeEngine:STT"
    }

    protected val _engineState = MutableStateFlow(WakeEngine.EngineState.UNINITIALIZED)
    override val engineState: StateFlow<WakeEngine.EngineState> = _engineState

    protected val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError

    protected var currentConfig: WakeEngineConfig.SttWakeConfig? = null
    protected var onWakeDetected: ((Float) -> Unit)? = null
    protected var lastWakeTime = 0L

    /**
     * 匹配唤醒词文本
     *
     * @param recognizedText 识别到的文本
     * @return 是否匹配成功
     */
    protected fun matchWakePhrase(recognizedText: String): Boolean {
        val config = currentConfig ?: return false

        // 检查冷却时间
        val now = System.currentTimeMillis()
        if (now - lastWakeTime < config.cooldownMs) {
            Log.d(TAG, "冷却中，忽略: $recognizedText")
            return false
        }

        // 文本归一化
        val normalized = normalizeText(recognizedText)
        val wakePhrase = normalizeText(config.wakePhrase)

        val matched = if (config.regexEnabled) {
            // 正则匹配
            try {
                val regex = Regex(wakePhrase, setOf(RegexOption.IGNORE_CASE))
                regex.containsMatchIn(normalized)
            } catch (e: Exception) {
                Log.w(TAG, "正则表达式错误: ${e.message}")
                false
            }
        } else {
            // 精确匹配（包含即可）
            normalized.contains(wakePhrase, ignoreCase = true)
        }

        if (matched) {
            lastWakeTime = now
            Log.i(TAG, "唤醒词匹配: '$recognizedText' -> '$wakePhrase'")
        }

        return matched
    }

    /**
     * 文本归一化
     * 移除标点符号、多余空格，统一大小写
     */
    private fun normalizeText(text: String): String {
        return text
            .replace(Regex("""[，。！？、；：""''（）《》【】\s]+"""), " ")
            .replace(Regex("""[,\.!?;:"'()[]\s]+"""), " ")
            .trim()
            .lowercase()
    }

    override fun release() {
        _engineState.value = WakeEngine.EngineState.UNINITIALIZED
        currentConfig = null
        onWakeDetected = null
    }
}
