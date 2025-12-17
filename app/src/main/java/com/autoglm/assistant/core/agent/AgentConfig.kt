package com.autoglm.assistant.core.agent

import com.autoglm.assistant.ai.MessageBuilder
import com.autoglm.assistant.ai.ModelConfig
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
    val plannerConfig: TaskPlannerConfig? = null,
    /**
     * Prompt优化器配置
     * 如果启用，用户的简短指令会先被扩展为更详细的任务描述
     */
    val optimizerConfig: PromptOptimizerConfig? = null
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

/**
 * Prompt优化器配置
 */
data class PromptOptimizerConfig(
    /**
     * 是否启用Prompt优化器
     */
    val enabled: Boolean = false,
    /**
     * 优化器使用的模型配置
     */
    val modelConfig: ModelConfig? = null
)
