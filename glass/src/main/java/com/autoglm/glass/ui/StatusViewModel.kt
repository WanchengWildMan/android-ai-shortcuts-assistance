package com.autoglm.glass.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import com.autoglm.glass.bridge.GlassBridge
import com.autoglm.glass.bridge.GlassProtocol
import com.autoglm.glass.bridge.decodePhoneStatus
import com.autoglm.glass.bridge.encodeInboundCaps
import com.autoglm.glass.receiver.KeyType
import com.rokid.cxr.CXRServiceBridge
import com.rokid.cxr.Caps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Agent 状态（手机推送，眼镜渲染）。
 */
data class StatusState(
    val isRunning: Boolean = false,
    val currentStep: Int = 0,
    val thinkingLine: String = "",
    val coordinatorEnabled: Boolean = false,
    val coordinatorTaskLine: String = "",
    val plannerStatusLine: String = "",
    val taskSummary: String = "",
    val logText: String = "",
    val lastCmd: String = "",
    /** 滚动位置（0=最新，正向增长表示往上翻历史） */
    val scrollIndex: Int = 0
)

/**
 * 眼镜端核心 ViewModel。
 * 业务目的：
 *   1) subscribe 手机下发的 Agent 状态 → 更新 StateFlow 驱动 Compose UI
 *   2) 维护对话消息列表（user 说的、agent 做的、system 摘要），支持眼镜下滑动浏览
 *   3) 把眼镜按键/系统路由意图文本 sendMessage 回传手机
 */
class StatusViewModel : ViewModel() {

    private val bridge = GlassBridge()

    private val _state = MutableStateFlow(StatusState())
    val state: StateFlow<StatusState> = _state.asStateFlow()

    /** 对话消息历史，按时间顺序追加；列表最后一条是最新的 */
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    /** 眼镜端是否已就绪（系统桥接已连接） */
    private val _bridgeConnected = MutableStateFlow(false)
    val bridgeConnected: StateFlow<Boolean> = _bridgeConnected.asStateFlow()

    private val msgCallback = object : CXRServiceBridge.MsgCallback {
        override fun onReceive(name: String?, args: Caps?, bytes: ByteArray?) {
            if (name != GlassProtocol.KEY_PHONE_TO_GLASS || args == null) {
                // 调试用：打印非预期通道的指令，用于真机验证眼镜系统 agent 路由意图文本的传递路径
                Log.i(LogTAG, "onReceive name=$name args=$args bytesLen=${bytes?.size ?: 0}")
                return
            }
            val status = decodePhoneStatus(args)
            if (status == null) {
                Log.w(LogTAG, "decodePhoneStatus failed, size=${args.size()}")
                return
            }
            Log.i(LogTAG, "收到手机状态 cmd=${status.cmd} running=${status.isRunning} step=${status.currentStep}")

            // CMD_CHAT：追加到消息历史，并把滚动重置到最新
            if (status.cmd == GlassProtocol.CMD_CHAT && status.chatText.isNotBlank()) {
                appendMessage(ChatMessage(status.chatRole, status.chatText))
                _state.value = _state.value.copy(lastCmd = status.cmd, scrollIndex = 0)
                return
            }

            // 其他指令（status/task_done/log）照旧更新状态
            _state.value = _state.value.copy(
                isRunning = status.isRunning,
                currentStep = status.currentStep,
                thinkingLine = status.thinkingLine,
                coordinatorEnabled = status.coordinatorEnabled,
                coordinatorTaskLine = status.coordinatorTaskLine,
                plannerStatusLine = status.plannerStatusLine,
                taskSummary = status.taskSummary,
                logText = if (status.cmd == GlassProtocol.CMD_LOG) status.thinkingLine else _state.value.logText,
                lastCmd = status.cmd,
                scrollIndex = 0
            )
        }
    }

    init {
        // 监听系统桥接连接状态
        bridge.setStatusListener(object : CXRServiceBridge.StatusListener {
            override fun onConnected(p0: String?, p1: String?, p2: Int) {
                Log.i(LogTAG, "onConnected $p0 $p1 $p2")
                _bridgeConnected.value = true
            }
            override fun onDisconnected() {
                Log.i(LogTAG, "onDisconnected")
                _bridgeConnected.value = false
            }
            override fun onConnecting(p0: String?, p1: String?, p2: Int) {}
            override fun onARTCStatus(p0: Float, p1: Boolean) {}
            override fun onRokidAccountChanged(p0: String?) {}
            override fun onAudioNoise(p0: Float) {}
        })
        // 订阅手机→眼镜状态通道
        val ret = bridge.subscribe(GlassProtocol.KEY_PHONE_TO_GLASS, msgCallback)
        Log.i(LogTAG, "subscribe ${GlassProtocol.KEY_PHONE_TO_GLASS} ret=$ret")
    }

    // ---- 对话历史管理 ----

    /** 追加一条消息到历史末尾，保持列表长度上限避免内存膨胀 */
    private fun appendMessage(msg: ChatMessage) {
        val current = _messages.value
        val updated = (current + msg).takeLast(MAX_MESSAGES)
        _messages.value = updated
    }

    /** 滚动：delta>0 往上翻历史，delta<0 往最新方向翻 */
    fun scroll(delta: Int) {
        val size = _messages.value.size
        if (size == 0) return
        val next = (_state.value.scrollIndex + delta).coerceIn(0, size - 1)
        _state.value = _state.value.copy(scrollIndex = next)
    }

    /** 用户侧文字（眼镜按键触发"新建会话"或被识别为本地指令）也加入历史 */
    fun appendLocalMessage(role: String, text: String) {
        appendMessage(ChatMessage(role, text))
        _state.value = _state.value.copy(scrollIndex = 0)
    }

    // ---- 眼镜→手机 回传 ----

    /** 回传意图文本（眼镜 STT/系统路由结果），同时本地记为一条 user 消息 */
    fun sendIntent(text: String) {
        appendLocalMessage("user", text)
        send(GlassProtocol.CMD_INTENT, text)
    }

    /** 回传停止；本地记录一条 system 消息便于用户在历史里看到 */
    fun sendStop() {
        appendLocalMessage("system", "已停止当前任务")
        send(GlassProtocol.CMD_STOP, "")
    }

    /** 回传介入指令 */
    fun sendIntervene(text: String) {
        appendLocalMessage("system", if (text.isBlank()) "请求介入" else "介入: $text")
        send(GlassProtocol.CMD_INTERVENE, text)
    }

    /** 回传按键事件 */
    fun sendKey(keyName: String) {
        send(GlassProtocol.CMD_KEY_EVENT, keyName)
    }

    fun sendKey(keyType: KeyType) {
        sendKey(keyType.name)
    }

    private fun send(cmd: String, text: String) {
        val ret = bridge.sendMessage(
            GlassProtocol.KEY_GLASS_TO_PHONE,
            encodeInboundCaps(cmd, text)
        )
        Log.i(LogTAG, "sendMessage cmd=$cmd text=${text.take(30)} ret=$ret")
    }

    companion object {
        private const val LogTAG = "AutoGLM"
        // 消息历史最大保留条数，超出自动丢弃最早的，避免眼镜端内存膨胀
        private const val MAX_MESSAGES = 100
    }
}
