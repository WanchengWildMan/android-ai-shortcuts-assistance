package com.autoglm.assistant.ui.chat

import java.util.UUID

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus = MessageStatus.SENT,
    val senderName: String = if (isUser) "You" else "Assistant"
)

enum class MessageStatus {
    SENDING, SENT, ERROR, PROCESSING
}
