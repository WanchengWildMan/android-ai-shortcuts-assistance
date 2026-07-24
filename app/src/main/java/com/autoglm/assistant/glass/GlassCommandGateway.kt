package com.autoglm.assistant.glass

import com.autoglm.assistant.service.WakeWordService
import com.autoglm.assistant.util.Logger
import com.rokid.cxr.link.callbacks.ICustomCmdCbk

/**
 * 眼镜端指令回传网关。
 *
 * 业务目的：接收眼镜端经 sendMessage("rk_custom_key", Caps) 回传的指令，
 * 解码后路由到 WakeWordService 的任务入口（executeTask/stopCurrentTask/介入）。
 *
 * 注册时机：会话构建完成后由 WakeWordService 调用 link.setCXRCustomCmdCbk(this)。
 *
 * 路由表：
 *   intent   → executeTask(text)        眼镜 STT/系统路由出的意图文本
 *   stop     → stopCurrentTask()        停止当前任务
 *   intervene→ requestInterventionFromGlass(text)  介入指令
 *   key      → 按键事件（LONG_PRESS→停止，DOUBLE_CLICK→介入空提示，其余仅记录）
 */
class GlassCommandGateway(private val service: WakeWordService) : ICustomCmdCbk {

    override fun onCustomCmdResult(key: String?, payload: ByteArray?) {
        if (key != GlassProtocol.KEY_GLASS_TO_PHONE || payload == null) return
        val inbound = decodeInbound(payload) ?: run {
            Logger.w(Logger.GLASS, "解码眼镜指令失败")
            return
        }
        Logger.i(Logger.GLASS, "收到眼镜指令 cmd=${inbound.cmd} text=${inbound.text.take(40)}")
        when (inbound.cmd) {
            GlassProtocol.CMD_INTENT -> {
                val text = inbound.text.trim()
                if (text.isNotEmpty()) {
                    service.executeTask(text)
                } else {
                    Logger.w(Logger.GLASS, "intent 文本为空，忽略")
                }
            }
            GlassProtocol.CMD_STOP -> {
                service.stopCurrentTask()
            }
            GlassProtocol.CMD_INTERVENE -> {
                service.requestInterventionFromGlass(inbound.text)
            }
            GlassProtocol.CMD_KEY_EVENT -> {
                handleKeyEvent(inbound.text)
            }
            else -> Logger.w(Logger.GLASS, "未知眼镜指令 cmd=${inbound.cmd}")
        }
    }

    /** 眼镜按键事件映射（可后续按真机手感调整） */
    private fun handleKeyEvent(keyName: String) {
        Logger.i(Logger.GLASS, "眼镜按键: $keyName")
        when (keyName) {
            "LONG_PRESS", "AI_START" -> service.stopCurrentTask()
            "DOUBLE_CLICK" -> service.requestInterventionFromGlass("")
            else -> { /* CLICK 等暂不映射，避免误触 */ }
        }
    }
}
