package com.autoglm.assistant.core.agent

import com.autoglm.assistant.ai.MessageBuilder
import com.autoglm.assistant.ai.ModelConfig
import com.autoglm.assistant.core.planner.TaskPlannerConfig

/**
 * 智能体配置信息
 */
data class AgentConfig(
    val maxSteps: Int = 100,
    val language: String = "cn",
    val systemPrompt: String? = null,
    val verbose: Boolean = true,
    /**
     * 任务规划器配置
     * 只要配置了协调器API信息，就会初始化SmartCoordinator
     * enabled字段控制"默认启用规划"，不影响协调器的初始化
     * 具体任务是否使用规划由任务级别的enablePlanning参数决定
     */
    val plannerConfig: TaskPlannerConfig? = null,
    /**
     * Prompt优化器配置
     * 如果启用，用户的简短指令会先被扩展为更详细的任务描述
     */
    val optimizerConfig: PromptOptimizerConfig? = null
) {
    /**
     * 获取生效的系统提示词
     */
    fun getEffectiveSystemPrompt(): String {
        return systemPrompt ?: when (language) {
            "en" -> MessageBuilder.DEFAULT_SYSTEM_PROMPT_EN
            else -> MessageBuilder.DEFAULT_SYSTEM_PROMPT_CN
        }
    }
}

/**
 * 单步执行结果
 */
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
    val modelConfig: ModelConfig? = null,
    /**
     * 是否启用任务完成后的自动总结
     */
    val enableTaskSummary: Boolean = false
)
