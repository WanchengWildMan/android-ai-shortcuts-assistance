package com.autoglm.assistant.ui.chat

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autoglm.assistant.App
import com.autoglm.assistant.service.WakeWordService
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val messageManager = MessageManager(application)
    
    val conversations = mutableStateListOf<Conversation>()
    val messages = mutableStateListOf<ChatMessage>()
    
    private var _currentConversation = mutableStateOf<Conversation?>(null)
    val currentConversation get() = _currentConversation.value

    // Indices for streaming updates
    private var optimizerMessageIndex = -1
    private var plannerMessageIndex = -1
    private var summaryMessageIndex = -1
    private var hasShownSubtaskCards = false
    private var lastCoordinatorContent: String? = null
    
    // Thinking message cache
    private var lastThinkingContent: String? = null
    private var lastThinkingMessageIndex: Int? = null

    init {
        loadConversations()
        observeServiceMessages()
    }

    private fun loadConversations() {
        viewModelScope.launch {
            val loaded = messageManager.loadConversations()
            conversations.clear()
            conversations.addAll(loaded)
            if (loaded.isNotEmpty()) {
                selectConversation(loaded.first())
            }
        }
    }

    fun selectConversation(conversation: Conversation) {
        _currentConversation.value = conversation
        messages.clear()
        messages.addAll(conversation.messages)
        
        // Reset streaming indices when switching conversation
        resetStreamingIndices()
    }

    fun createNewConversation(): Conversation {
        val newConv = Conversation()
        conversations.add(0, newConv)
        selectConversation(newConv)
        return newConv
    }
    
    fun deleteConversation(id: String) {
        viewModelScope.launch {
            messageManager.deleteConversation(id)
            conversations.removeAll { it.id == id }
            if (currentConversation?.id == id) {
                val nextConv = conversations.firstOrNull()
                if (nextConv != null) {
                    selectConversation(nextConv)
                } else {
                    _currentConversation.value = null
                    messages.clear()
                }
            }
        }
    }

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        
        val conv = currentConversation ?: createNewConversation()
        // If the message is not already in the conversation (check by reference or ID if needed, but here we just append)
        // Note: messages list is cleared and repopulated from conv.messages, so we need to sync them.
        // Actually, messages IS a snapshot of conv.messages in the current implementation of MainActivity, 
        // but here we are using a separate state list.
        
        // We should add to conv.messages as well
        if (!conv.messages.contains(message)) {
            conv.messages.add(message)
        }
        
        conv.timestamp = System.currentTimeMillis()
        
        // Auto-generate title
        if (conv.title == "New Chat" && message.isUser) {
            conv.title = message.content.take(30) + if (message.content.length > 30) "..." else ""
        }
        
        // Reorder conversations
        conversations.remove(conv)
        conversations.add(0, conv)
        _currentConversation.value = conv
        
        viewModelScope.launch {
            messageManager.saveConversation(conv)
        }
    }

    fun updateMessage(index: Int, message: ChatMessage) {
        if (index >= 0 && index < messages.size) {
            messages[index] = message
            
            val conv = currentConversation
            if (conv != null && index < conv.messages.size) {
                conv.messages[index] = message
                conv.timestamp = System.currentTimeMillis()
                viewModelScope.launch {
                    messageManager.saveConversation(conv)
                }
            }
        }
    }
    
    private fun resetStreamingIndices() {
        optimizerMessageIndex = -1
        plannerMessageIndex = -1
        summaryMessageIndex = -1
        hasShownSubtaskCards = false
        lastCoordinatorContent = null
        lastThinkingContent = null
        lastThinkingMessageIndex = null
    }

    private fun observeServiceMessages() {
        viewModelScope.launch {
            // We need to wait for service to be available or observe it globally
            // Since WakeWordService.instance is available, we can use it.
            // But we should probably poll or retry if it's null, or use a flow from a repository.
            // For now, we assume the service might be bound later. 
            // But WakeWordService.instance is a var.
            
            // A better way is to observe a flow that emits the service instance, but we don't have that.
            // We can launch a loop to check for instance, or just rely on the fact that 
            // when this ViewModel is created (in MainActivity), the service might be binding.
            
            // Actually, MainActivity binds the service.
            // We can expose a function to start observing when service is ready.
        }
    }
    
    // Called from MainActivity when service is ready or state changes
    fun startObservingService(service: WakeWordService) {
        viewModelScope.launch {
            launch {
                service.coordinatorMessage.collect { msg ->
                    if (msg == null) return@collect
                    handleCoordinatorMessage(msg)
                }
            }
            
            launch {
                service.agentMessage.collect { msg ->
                    if (msg == null) return@collect
                    handleAgentMessage(msg)
                }
            }
            
            launch {
                service.lastRecognizedText.collect { text ->
                    if (text.isNotBlank()) {
                        // Only add if it's a new recognized text that we haven't processed?
                        // MainActivity logic was: if (text.isNotBlank() && currentRoute == "chat") addMessage...
                        // We might need to be careful not to duplicate.
                        // For now, let's leave speech recognition handling to MainActivity or handle it here with care.
                        // MainActivity clears it? No.
                        // Let's keep speech handling in MainActivity for now to avoid breaking changes, 
                        // or move it here if we can ensure single processing.
                    }
                }
            }
        }
    }

    private fun handleCoordinatorMessage(msg: WakeWordService.CoordinatorMessage) {
        val displayContent = when (msg.type) {
            WakeWordService.CoordinatorMessageType.OPTIMIZER_STREAMING -> {
                if (msg.content.isBlank()) "✨ 正在优化指令..." 
                else "✨ 正在优化指令...\n\n${msg.content}"
            }
            WakeWordService.CoordinatorMessageType.OPTIMIZER_COMPLETE -> {
                "✨ **指令优化完成**\n\n${msg.content}"
            }
            WakeWordService.CoordinatorMessageType.PLANNING_STREAMING -> {
                if (msg.content.isBlank()) "🤔 正在规划任务..."
                else "🤔 正在规划任务...\n\n${msg.content}"
            }
            WakeWordService.CoordinatorMessageType.PLAN_COMPLETE -> {
                "📋 **任务规划：**\n\n${msg.content}"
            }
            WakeWordService.CoordinatorMessageType.SUBTASK_CARD -> {
                msg.content // JSON content, will be rendered by UI
            }
            WakeWordService.CoordinatorMessageType.SUBTASK_START -> {
                "▶️ 开始执行子任务：${msg.content}"
            }
            WakeWordService.CoordinatorMessageType.SUPERVISION_RESULT -> {
                val parts = msg.content.split("|", limit = 2)
                val status = parts.getOrNull(0) ?: ""
                val content = parts.getOrNull(1) ?: msg.content
                val emoji = when (status) {
                    "SUCCESS" -> "✅"
                    "NEEDS_CORRECTION" -> "⚠️"
                    "FAILED" -> "❌"
                    "UNCERTAIN" -> "❓"
                    else -> "📊"
                }
                "$emoji 监督结果\n\n$content"
            }
            WakeWordService.CoordinatorMessageType.COORDINATOR_THINKING -> {
                "💭 协调器思考：\n${msg.content}"
            }
            WakeWordService.CoordinatorMessageType.SUMMARY_STREAMING -> {
                if (msg.content.isBlank()) "📝 正在生成任务总结..."
                else "📝 正在总结...\n\n${msg.content}"
            }
            WakeWordService.CoordinatorMessageType.SUMMARY_COMPLETE -> {
                "📝 **任务总结：**\n\n${msg.content}"
            }
            WakeWordService.CoordinatorMessageType.CLEAR -> ""
        }

        if (displayContent.isBlank()) return

        when (msg.type) {
            WakeWordService.CoordinatorMessageType.OPTIMIZER_STREAMING,
            WakeWordService.CoordinatorMessageType.OPTIMIZER_COMPLETE -> {
                if (optimizerMessageIndex >= 0 && optimizerMessageIndex < messages.size) {
                    val oldMsg = messages[optimizerMessageIndex]
                    updateMessage(optimizerMessageIndex, ChatMessage(
                        id = oldMsg.id,
                        content = displayContent,
                        isUser = false,
                        timestamp = oldMsg.timestamp
                    ))
                } else {
                    addMessage(ChatMessage(content = displayContent, isUser = false))
                    optimizerMessageIndex = messages.size - 1
                }
            }
            WakeWordService.CoordinatorMessageType.PLANNING_STREAMING,
            WakeWordService.CoordinatorMessageType.PLAN_COMPLETE -> {
                val content = if (msg.type == WakeWordService.CoordinatorMessageType.PLAN_COMPLETE && hasShownSubtaskCards) {
                    "✅ 任务规划完成，开始执行"
                } else {
                    displayContent
                }

                if (plannerMessageIndex >= 0 && plannerMessageIndex < messages.size) {
                    val oldMsg = messages[plannerMessageIndex]
                    updateMessage(plannerMessageIndex, ChatMessage(
                        id = oldMsg.id,
                        content = content,
                        isUser = false,
                        timestamp = oldMsg.timestamp
                    ))
                } else {
                    addMessage(ChatMessage(content = content, isUser = false))
                    plannerMessageIndex = messages.size - 1
                }
            }
            WakeWordService.CoordinatorMessageType.SUBTASK_CARD -> {
                hasShownSubtaskCards = true
                addMessage(ChatMessage(content = displayContent, isUser = false))
            }
            WakeWordService.CoordinatorMessageType.SUMMARY_STREAMING,
            WakeWordService.CoordinatorMessageType.SUMMARY_COMPLETE -> {
                if (summaryMessageIndex >= 0 && summaryMessageIndex < messages.size) {
                    val oldMsg = messages[summaryMessageIndex]
                    updateMessage(summaryMessageIndex, ChatMessage(
                        id = oldMsg.id,
                        content = displayContent,
                        isUser = false,
                        timestamp = oldMsg.timestamp
                    ))
                } else {
                    addMessage(ChatMessage(content = displayContent, isUser = false))
                    summaryMessageIndex = messages.size - 1
                }
            }
            else -> {
                if (displayContent != lastCoordinatorContent) {
                    lastCoordinatorContent = displayContent
                    addMessage(ChatMessage(content = displayContent, isUser = false))
                }
            }
        }
    }

    private fun handleAgentMessage(msg: WakeWordService.AgentMessage) {
        val showProcess = App.instance.preferenceManager.showAgentProcess
        val shouldShow = when (msg.type) {
            WakeWordService.AgentMessageType.RESULT -> true
            WakeWordService.AgentMessageType.THINKING,
            WakeWordService.AgentMessageType.ACTION -> showProcess
        }

        when (msg.type) {
            WakeWordService.AgentMessageType.THINKING -> {
                lastThinkingContent = msg.content
                if (shouldShow && msg.content.isNotBlank()) {
                    addMessage(ChatMessage(content = "**思考：**\n${msg.content}", isUser = false))
                    lastThinkingMessageIndex = messages.size - 1
                } else {
                    lastThinkingMessageIndex = null
                }
            }
            WakeWordService.AgentMessageType.ACTION -> {
                if (shouldShow && msg.content.isNotBlank()) {
                    val content = if (!lastThinkingContent.isNullOrBlank()) {
                        buildString {
                            append("**思考：**\n")
                            append(lastThinkingContent)
                            append("\n\n**操作：**\n")
                            append(msg.content)
                        }
                    } else {
                        "**操作：**\n${msg.content}"
                    }

                    if (lastThinkingMessageIndex != null && lastThinkingMessageIndex!! < messages.size) {
                        val oldMsg = messages[lastThinkingMessageIndex!!]
                        updateMessage(lastThinkingMessageIndex!!, ChatMessage(
                            id = oldMsg.id,
                            content = content,
                            isUser = false,
                            timestamp = oldMsg.timestamp
                        ))
                    } else {
                        addMessage(ChatMessage(content = content, isUser = false))
                    }
                }
                lastThinkingContent = null
                lastThinkingMessageIndex = null
            }
            WakeWordService.AgentMessageType.RESULT -> {
                if (shouldShow || msg.type == WakeWordService.AgentMessageType.RESULT) {
                    addMessage(ChatMessage(content = msg.content, isUser = false))
                }
                lastThinkingContent = null
                lastThinkingMessageIndex = null
            }
        }
    }
}
