package com.autoglm.assistant.core.planner

import com.autoglm.assistant.ai.ModelConfig

/**
 * 任务协调器配置
 * 用于配置一个更强大的模型来协调任务执行
 */
data class TaskPlannerConfig(
    /**
     * 是否默认启用协调器
     * 控制新建快捷指令和手动输入任务时的默认协调器开关状态
     */
    val enabled: Boolean = false,

    /**
     * 协调器模型配置
     * 建议使用更强大的模型，如 GPT-4, Claude Sonnet, 或 GLM-4 Plus
     */
    val plannerModelConfig: ModelConfig? = null,

    /**
     * 决策超时时间（毫秒）
     */
    val planningTimeout: Long = 30000L,

    // 以下字段已废弃，保留仅为向后兼容
    @Deprecated("No longer used in new coordinator flow")
    val enableSupervision: Boolean = true,

    @Deprecated("No longer used in new coordinator flow")
    val supervisorModelConfig: ModelConfig? = null,

    @Deprecated("No longer used in new coordinator flow")
    val maxCorrections: Int = 2,

    @Deprecated("No longer used in new coordinator flow")
    val maxSubTasks: Int = 10,

    /**
     * 协调器每次任务的最大决策轮次
     * 
     * 每轮决策包括：
     * 1. 协调器分析当前截图和执行历史
     * 2. 决定下一步操作（继续执行/任务完成/任务失败）
     * 3. Agent执行协调器给出的具体指令
     * 
     * 循环条件：while (coordinatorSteps < maxCoordinatorSteps)
     */
    val maxCoordinatorSteps: Int = 20,

    /**
     * 协调器单轮内，PhoneAgent 执行单条 nextInstruction 的最大连续决策轮次
     */
    val maxAgentStepsPerCoordinatorStep: Int = 10,

    /**
     * 自定义系统提示词（空字符串表示使用内置默认提示词）
     */
    val customSystemPrompt: String = "",

    /**
     * 协调器模型是否支持 Vision（图片输入）
     *
     * 如果启用，协调器在决策时会接收当前截图，可以更准确地判断任务状态
     * 如果禁用，协调器只根据执行历史文本做决策
     *
     * 支持 Vision 的模型：
     * - GLM-4V, GLM-4V-Plus (智谱)
     * - GPT-4V, GPT-4-Turbo (OpenAI)
     * - Claude 3 系列 (Anthropic)
     *
     * 不支持 Vision 的模型：
     * - DeepSeek-Chat, DeepSeek-Reasoner
     * - GLM-4, GLM-4-Plus (非 V 版本)
     * - 大部分纯文本模型
     *
     * 默认：false（兼容大多数模型）
     */
    val enableVision: Boolean = false
)

/**
 * 单个子任务的详细描述
 * @deprecated 新的协调器流程不再使用预先规划的子任务，改为逐步决策
 */
@Deprecated("Use CoordinatorDecision instead", ReplaceWith("CoordinatorDecision"))
data class PlannedSubTask(
    /**
     * 子任务序号（从1开始）
     */
    val index: Int,

    /**
     * 子任务目标
     * 清晰描述这个子任务要达成什么
     */
    val goal: String,

    /**
     * 当前状态描述
     * 描述执行此任务前的预期状态
     */
    val currentState: String,

    /**
     * 具体操作指导
     * 告诉UI Agent应该执行什么操作
     */
    val actions: String,

    /**
     * 上下文信息
     * 其他有助于执行的信息，如注意事项、特殊条件等
     */
    val context: String,

    /**
     * 依赖的前置任务索引
     * 如果为空，表示可以立即执行
     */
    val dependencies: List<Int> = emptyList()
)

/**
 * 完整的任务规划
 * @deprecated 新的协调器流程不再使用预先规划，改为逐步决策
 */
@Deprecated("Use CoordinatorDecision instead", ReplaceWith("CoordinatorDecision"))
data class TaskPlan(
    /**
     * 原始用户指令
     */
    val originalTask: String,

    /**
     * 任务分析
     * 规划模型对整个任务的理解和分析
     */
    val analysis: String,

    /**
     * 拆解后的子任务列表
     */
    val subTasks: List<PlannedSubTask>,

    /**
     * 预期总耗时（秒）
     * 规划模型估算的总耗时
     */
    val estimatedDuration: Int? = null,

    /**
     * 规划时间戳
     */
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * 将任务计划格式化为可读的文本
     */
    fun toReadableText(): String {
        val sb = StringBuilder()
        sb.appendLine("📋 **任务规划**")
        sb.appendLine()

        // 如果只有一个子任务且goal就是originalTask，说明是自然语言模式
        val isNaturalLanguageMode = subTasks.size == 1 && subTasks[0].goal == originalTask

        if (!isNaturalLanguageMode) {
            // JSON模式：显示任务分解
            sb.appendLine("**原始任务：**$originalTask")
            sb.appendLine()
            sb.appendLine("📊 **任务理解：**")
            sb.appendLine(analysis)
            sb.appendLine()

            if (estimatedDuration != null) {
                val minutes = estimatedDuration / 60
                val seconds = estimatedDuration % 60
                sb.appendLine("⏱ **预计耗时：**${minutes}分${seconds}秒")
                sb.appendLine()
            }

            sb.appendLine("📝 **执行步骤（共${subTasks.size}步）：**")
            sb.appendLine()

            subTasks.forEachIndexed { index, task ->
                sb.appendLine("**${index + 1}. ${task.goal}**")
                // 显示具体操作
                if (task.actions.isNotBlank() && task.actions != task.goal) {
                    sb.appendLine("   📌 操作：${task.actions}")
                }
                if (task.context.isNotBlank() && task.context != "协调器提供的任务指导") {
                    sb.appendLine("   💡 ${task.context}")
                }
                if (task.dependencies.isNotEmpty()) {
                    sb.appendLine("   ⚠️ 依赖：步骤 ${task.dependencies.joinToString(", ")}")
                }
                sb.appendLine()
            }
        } else {
            // 自然语言模式：直接显示指导
            sb.appendLine("**任务：**$originalTask")
            sb.appendLine()
            sb.appendLine("📌 **执行指导：**")
            sb.appendLine(analysis)
        }

        return sb.toString().trim()
    }
}
