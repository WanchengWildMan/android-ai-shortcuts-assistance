package com.autoglm.assistant.ai

import com.google.gson.Gson

object MessageBuilder {

    private val gson = Gson()

    fun createSystemMessage(content: String): Message.System {
        return Message.System(content)
    }

    fun createUserMessage(text: String, imageBase64: String? = null): Message.User {
        return Message.User(text, imageBase64)
    }

    fun createAssistantMessage(content: String): Message.Assistant {
        return Message.Assistant(content)
    }

    fun buildScreenInfo(
        currentApp: String,
        screenWidth: Int? = null,
        screenHeight: Int? = null,
        extraInfo: Map<String, Any>? = null
    ): String {
        val info = mutableMapOf<String, Any>(
            "current_app" to currentApp
        )

        screenWidth?.let { info["screen_width"] = it }
        screenHeight?.let { info["screen_height"] = it }
        extraInfo?.let { info.putAll(it) }

        // 检测是否在 AutoGLM 应用中，如果是，添加警告
        if (currentApp.contains("AutoGLM", ignoreCase = true) ||
            currentApp.contains("Assistant", ignoreCase = true)) {
            info["warning"] = "你当前在 AutoGLM 助手应用中。请勿点击快捷指令卡片！请直接启动目标应用。"
        }

        return gson.toJson(info)
    }

    fun buildTaskPrompt(task: String, screenInfo: String): String {
        return """$task

** 屏幕信息 **
$screenInfo"""
    }

    fun buildContinuePrompt(screenInfo: String): String {
        return """** 屏幕信息 **
$screenInfo"""
    }

    fun removeImagesFromMessages(messages: List<Message>): List<Message> {
        return messages.map { message ->
            when (message) {
                is Message.User -> {
                    if (message.imageBase64 != null) {
                        Message.User(message.text, null)
                    } else {
                        message
                    }
                }
                else -> message
            }
        }
    }

    // 系统提示词统一通过 PromptRegistry 获取
    val DEFAULT_SYSTEM_PROMPT_CN: String get() = PromptRegistry.get(PromptKey.AGENT_SYSTEM_CN)
    val DEFAULT_SYSTEM_PROMPT_EN: String get() = PromptRegistry.get(PromptKey.AGENT_SYSTEM_EN)
}
