package com.autoglm.glass.bridge

import com.rokid.cxr.Caps

/**
 * 眼镜端协议镜像（与手机端 com.autoglm.assistant.glass.GlassProtocol 同源）。
 * 通道 key 与字段顺序两端必须严格一致。
 */
object GlassProtocol {
    const val KEY_PHONE_TO_GLASS = "rk_custom_client"
    const val KEY_GLASS_TO_PHONE = "rk_custom_key"

    const val CMD_STATUS = "status"
    const val CMD_TASK_DONE = "task_done"
    const val CMD_LOG = "log"
    const val CMD_CHAT = "chat"           // 手机→眼镜 对话消息推送，用于眼镜端消息历史列表展示

    const val CMD_INTENT = "intent"
    const val CMD_STOP = "stop"
    const val CMD_INTERVENE = "intervene"
    const val CMD_KEY_EVENT = "key"
}

/** 眼镜→手机 指令编码：idx0 cmd、idx1 text、idx2 extra(预留) */
fun encodeInboundCaps(cmd: String, text: String, extra: String = ""): Caps = Caps().apply {
    write(cmd)
    write(text)
    write(extra)
}

/** 手机→眼镜 对话消息推送：idx0 cmd="chat"、idx1 role、idx2 text */
fun encodeChatCaps(role: String, text: String): Caps = Caps().apply {
    write(GlassProtocol.CMD_CHAT)
    write(role)
    write(text)
}

/** 手机→眼镜 状态快照解码结果 */
data class PhoneStatus(
    val cmd: String,
    val isRunning: Boolean,
    val currentStep: Int,
    val thinkingLine: String,
    val coordinatorEnabled: Boolean,
    val coordinatorTaskLine: String,
    val plannerStatusLine: String,
    val taskSummary: String,
    /** CMD_CHAT 专用：消息角色（user/agent/system） */
    val chatRole: String = "",
    /** CMD_CHAT 专用：消息内容 */
    val chatText: String = ""
)

/**
 * 解码手机→眼镜状态推送。
 * 字段顺序：0 cmd、1 isRunning、2 step、3 thinking、4 coordEnabled、5 coordTask、6 planner、7 summary
 */
fun decodePhoneStatus(args: Caps): PhoneStatus? = runCatching {
    if (args.size() < 2) return@runCatching null
    val cmd = args.at(0).getString()
    when (cmd) {
        GlassProtocol.CMD_STATUS -> {
            PhoneStatus(
                cmd = cmd,
                isRunning = args.at(1).getInt() == 1,
                currentStep = if (args.size() > 2) args.at(2).getInt() else 0,
                thinkingLine = if (args.size() > 3) args.at(3).getString() else "",
                coordinatorEnabled = if (args.size() > 4) args.at(4).getInt() == 1 else false,
                coordinatorTaskLine = if (args.size() > 5) args.at(5).getString() else "",
                plannerStatusLine = if (args.size() > 6) args.at(6).getString() else "",
                taskSummary = if (args.size() > 7) args.at(7).getString() else ""
            )
        }
        GlassProtocol.CMD_TASK_DONE -> {
            val summary = if (args.size() > 1) args.at(1).getString() else ""
            PhoneStatus(cmd, false, 0, "", false, "", "", summary)
        }
        GlassProtocol.CMD_LOG -> {
            val text = if (args.size() > 1) args.at(1).getString() else ""
            PhoneStatus(cmd, false, 0, text, false, "", "", "")
        }
        GlassProtocol.CMD_CHAT -> {
            // idx1 role、idx2 text
            val role = if (args.size() > 1) args.at(1).getString() else "system"
            val text = if (args.size() > 2) args.at(2).getString() else ""
            PhoneStatus(cmd, false, 0, "", false, "", "", "", chatRole = role, chatText = text)
        }
        else -> null
    }
}.getOrNull()
