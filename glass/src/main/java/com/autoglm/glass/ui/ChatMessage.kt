package com.autoglm.glass.ui

/**
 * 单条对话消息（眼镜端展示用，由手机推送或本地按键触发）。
 *
 * 业务目的：维护眼镜端可滑动的对话历史列表，每条消息有角色和内容，
 * 用户可通过镜腿键/触控板上下滑动浏览完整对话历史。
 */
data class ChatMessage(
    val role: String,       // "user"=用户说的；"agent"=Agent动作/思考；"system"=状态摘要
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)
