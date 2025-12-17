package com.autoglm.assistant.core.planner

import com.autoglm.assistant.ai.ModelConfig

/**
 * 任务规划器配置
 * 用于配置一个更强大的模型来分解用户任务
 */
data class TaskPlannerConfig(
    /**
     * 是否启用任务规划器
     * 当启用时，用户指令会先由规划模型分解为多个子任务，再交给UI Agent执行
     */
    val enabled: Boolean = false,

    /**
     * 规划模型配置
     * 建议使用更强大的模型，如 GPT-4, Claude Sonnet, 或 GLM-4 Plus
     */
    val plannerModelConfig: ModelConfig? = null,

    /**
     * 是否启用任务监督
     * 当启用时，每个子任务执行后会由监督模型检查结果并提供反馈
     */
    val enableSupervision: Boolean = true,

    /**
     * 监督模型配置
     * 如果为null，则使用plannerModelConfig作为监督模型
     */
    val supervisorModelConfig: ModelConfig? = null,

    /**
     * 最大纠正次数
     * 每个子任务如果执行不正确，最多允许纠正几次
     */
    val maxCorrections: Int = 2,

    /**
     * 最大子任务数量
     * 防止任务分解过于细碎
     */
    val maxSubTasks: Int = 10,

    /**
     * 规划超时时间（毫秒）
     */
    val planningTimeout: Long = 30000L
)

/**
 * 单个子任务的详细描述
 */
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
 */
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
