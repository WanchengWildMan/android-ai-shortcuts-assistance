package com.autoglm.assistant.ui.chat

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    var title: String = DEFAULT_CONVERSATION_TITLE,
    var timestamp: Long = System.currentTimeMillis(),
    val messages: MutableList<ChatMessage> = mutableListOf()
) {
    companion object {
        const val DEFAULT_CONVERSATION_TITLE = "新对话"
        /** 截取用户消息前N字符作为对话标题 */
        private const val TITLE_TRUNCATE_LENGTH = 30

        /** 根据用户首条消息自动生成标题 */
        fun generateTitle(userMessage: String): String {
            return userMessage.take(TITLE_TRUNCATE_LENGTH) +
                if (userMessage.length > TITLE_TRUNCATE_LENGTH) "..." else ""
        }
    }
}

class MessageManager(private val context: Context) {
    private val gson = Gson()
    private val conversationsDir = File(context.filesDir, "conversations").also { it.mkdirs() }
    private val oldFile = File(context.filesDir, "chat_history.json")

    suspend fun loadConversations(): List<Conversation> {
        return withContext(Dispatchers.IO) {
            // Migrate old data
            if (oldFile.exists()) {
                try {
                    val type = object : TypeToken<List<ChatMessage>>() {}.type
                    val oldMessages: List<ChatMessage> = gson.fromJson(oldFile.readText(), type)
                    if (oldMessages.isNotEmpty()) {
                        val newConv = Conversation(
                            title = "History Chat",
                            messages = oldMessages.toMutableList(),
                            timestamp = oldMessages.lastOrNull()?.timestamp ?: System.currentTimeMillis()
                        )
                        saveConversation(newConv)
                    }
                    oldFile.delete()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            val list = mutableListOf<Conversation>()
            conversationsDir.listFiles()?.forEach { file ->
                if (file.extension == "json") {
                    try {
                        val conv = gson.fromJson(file.readText(), Conversation::class.java)
                        list.add(conv)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            list.sortedByDescending { it.timestamp }
        }
    }
    
    suspend fun saveConversation(conversation: Conversation) {
        withContext(Dispatchers.IO) {
            try {
                val file = File(conversationsDir, "${conversation.id}.json")
                val json = gson.toJson(conversation)
                file.writeText(json)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    suspend fun deleteConversation(id: String) {
        withContext(Dispatchers.IO) {
            File(conversationsDir, "$id.json").delete()
        }
    }

    // Legacy methods for backward compatibility
    suspend fun loadMessages(): List<ChatMessage> {
        val conversations = loadConversations()
        return conversations.firstOrNull()?.messages ?: emptyList()
    }

    suspend fun saveMessages(messages: List<ChatMessage>) {}
    fun clearMessages() {}
}
