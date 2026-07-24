package com.autoglm.assistant.glass

import com.rokid.cxr.Caps

/**
 * 手机端与眼镜端之间的双向指令协议（眼镜端 :glass 模块有同名镜像 GlassProtocol）。
 *
 * 通道 key 两端必须一致：
 *   - 手机→眼镜：手机 sendCustomCmd(KEY_PHONE_TO_GLASS, Caps) ↔ 眼镜 subscribe(KEY_PHONE_TO_GLASS)
 *   - 眼镜→手机：眼镜 sendMessage(KEY_GLASS_TO_PHONE, Caps) ↔ 手机 onCustomCmdResult(KEY_GLASS_TO_PHONE, bytes)
 *
 * Caps 按 write 顺序 at(idx) 读取，两端严格对齐。
 */
object GlassProtocol {

    // ---- 通道 key（与 Rokid 官方 Sample 约定一致）----
    const val KEY_PHONE_TO_GLASS = "rk_custom_client"
    const val KEY_GLASS_TO_PHONE = "rk_custom_key"

    // ---- 手机→眼镜 推送指令类型（Caps idx0）----
    const val CMD_STATUS = "status"          // Agent 状态快照
    const val CMD_TASK_DONE = "task_done"    // 任务结束
    const val CMD_LOG = "log"                // 纯文本提示
    const val CMD_CHAT = "chat"              // 对话消息推送（role+text），用于眼镜端消息历史展示

    // ---- 眼镜→手机 回传指令类型（Caps idx0）----
    const val CMD_INTENT = "intent"          // 眼镜 STT/系统路由出的意图文本
    const val CMD_STOP = "stop"              // 停止当前任务
    const val CMD_INTERVENE = "intervene"    // 介入指令文本
    const val CMD_KEY_EVENT = "key"          // 眼镜按键事件

    /** 协议版本号，预留于眼镜→手机指令的 extra 字段，便于未来不兼容升级 */
    const val PROTOCOL_VERSION = "v1"
}

/**
 * 手机→眼镜 状态推送的 Caps 编码。
 * 字段顺序（眼镜端按此 at(idx) 读取）：
 *   0 cmd(String)、1 isRunning(uint32)、2 currentStep(uint32)、3 thinkingLine(String)、
 *   4 coordinatorEnabled(uint32)、5 coordinatorTaskLine(String)、6 plannerStatusLine(String)、7 taskSummary(String)
 */
fun encodeStatusCaps(
    isRunning: Boolean,
    currentStep: Int,
    thinkingLine: String,
    coordinatorEnabled: Boolean,
    coordinatorTaskLine: String,
    plannerStatusLine: String,
    taskSummary: String?
): Caps = Caps().apply {
    write(GlassProtocol.CMD_STATUS)
    writeUInt32(if (isRunning) 1 else 0)
    writeUInt32(currentStep)
    write(thinkingLine)
    writeUInt32(if (coordinatorEnabled) 1 else 0)
    write(coordinatorTaskLine)
    write(plannerStatusLine)
    write(taskSummary ?: "")
}

/** 任务结束推送 */
fun encodeTaskDoneCaps(taskSummary: String?): Caps = Caps().apply {
    write(GlassProtocol.CMD_TASK_DONE)
    write(taskSummary ?: "")
}

/** 纯文本提示推送 */
fun encodeLogCaps(text: String): Caps = Caps().apply {
    write(GlassProtocol.CMD_LOG)
    write(text)
}

/** 对话消息推送：role="user"/"agent"/"system"，眼镜端会追加到消息历史列表 */
fun encodeChatCaps(role: String, text: String): Caps = Caps().apply {
    write(GlassProtocol.CMD_CHAT)
    write(role)
    write(text)
}

/** 眼镜→手机 指令解码结果 */
data class GlassInbound(
    val cmd: String,
    val text: String,     // intent/intervene 文本，或 key 事件名（stop 时为空）
    val extra: String     // 预留（协议版本号等）
)

/**
 * 解码眼镜→手机指令。
 * 字段顺序：0 cmd(String)、1 text(String)、2 extra(String，可缺省)
 */
fun decodeInbound(payload: ByteArray): GlassInbound? = runCatching {
    val caps = Caps.fromBytes(payload)
    val size = caps.size()
    if (size < 2) return@runCatching null
    val cmd = caps.at(0).getString()
    val text = caps.at(1).getString()
    val extra = if (size > 2) caps.at(2).getString() else ""
    GlassInbound(cmd, text, extra)
}.getOrNull()
