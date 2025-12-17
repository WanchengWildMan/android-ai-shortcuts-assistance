package com.autoglm.assistant.core.agent

import com.autoglm.assistant.ai.MessageBuilder
import com.autoglm.assistant.core.planner.TaskPlannerConfig

data class AgentConfig(
    val maxSteps: Int = 100,
    val language: String = "cn",
    val systemPrompt: String? = null,
    val verbose: Boolean = true,
    /**
     * 任务规划器配置
     * 如果启用，用户指令会先由更强的模型分解为多个子任务
     */
    val plannerConfig: TaskPlannerConfig? = null
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
