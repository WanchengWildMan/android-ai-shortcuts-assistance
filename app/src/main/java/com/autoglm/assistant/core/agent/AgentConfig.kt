package com.autoglm.assistant.core.agent

import com.autoglm.assistant.ai.MessageBuilder

data class AgentConfig(
    val maxSteps: Int = 100,
    val language: String = "cn",
    val systemPrompt: String? = null,
    val verbose: Boolean = true
) {
    fun getEffectiveSystemPrompt(): String {
        return systemPrompt ?: when (language) {
            "en" -> MessageBuilder.DEFAULT_SYSTEM_PROMPT_EN
            else -> MessageBuilder.DEFAULT_SYSTEM_PROMPT_CN
        }
    }
}

data class StepResult(
    val success: Boolean,
    val finished: Boolean,
    val action: String?,
    val thinking: String,
    val message: String?,
    val needsHumanIntervention: Boolean = false
)
