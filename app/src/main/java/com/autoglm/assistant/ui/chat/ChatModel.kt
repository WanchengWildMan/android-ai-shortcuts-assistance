package com.autoglm.assistant.ui.chat

import java.util.UUID

/**
 * 消息来源枚举 — 用于区分不同模块产生的消息，以便UI渲染不同图标和标注
 */
enum class MessageSource {
    USER,           // 用户消息
    AGENT,          // Agent执行消息（思考+操作）
    COORDINATOR,    // 协调器消息（规划、思考、决策步数等）
    OPTIMIZER,      // 优化器消息
    SUMMARY         // 任务总结
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus = MessageStatus.SENT,
    val senderName: String = if (isUser) "You" else "Assistant",
    val source: MessageSource = if (isUser) MessageSource.USER else MessageSource.AGENT
)

enum class MessageStatus {
    SENDING, SENT, ERROR, PROCESSING
}
