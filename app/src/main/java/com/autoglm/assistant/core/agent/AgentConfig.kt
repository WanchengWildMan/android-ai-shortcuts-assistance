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
     * 单步执行超时阈值（毫秒）。
     * 超过该时长的单步视为卡住（模型流式响应死循环或 action 执行卡死），
     * 强制中止当前步并通知协调器，避免单步死循环拖垮整条任务。
     * 默认 20 秒，配置可改。
     */
    val stepTimeoutMs: Long = 20_000L,
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
    val optimizerConfig: PromptOptimizerConfig? = null,
    /**
     * 意图识别器配置
     * 如果启用，用户输入会先尝试匹配快捷指令，匹配成功则使用指令模板执行
     */
    val intentConfig: IntentRecognizerConfig? = null
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
    val needsHumanIntervention: Boolean = false,
    val userQuestion: String? = null,  // Agent中途提问，需要用户回答后继续执行
    /**
     * 该单步是否因超时被强制中止。
     * true 表示 executeStep 整体耗时超过 agentConfig.stepTimeoutMs，被 withTimeoutOrNull 取消；
     * 外层循环看到该标记应立即终止当前协调器步/整个任务，不再继续执行后续 subStep。
     */
    val timeout: Boolean = false
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
    val enableTaskSummary: Boolean = false,
    /**
     * 自定义系统提示词（空字符串表示使用内置默认提示词）
     */
    val customSystemPrompt: String = ""
)

/**
 * 意图识别器配置
 */
data class IntentRecognizerConfig(
    /**
     * 是否启用意图识别
     */
    val enabled: Boolean = false,
    /**
     * 识别器使用的模型配置
     */
    val modelConfig: ModelConfig? = null
)
