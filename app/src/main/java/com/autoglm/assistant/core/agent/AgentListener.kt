package com.autoglm.assistant.core.agent

import com.autoglm.assistant.ai.IntentResult
import com.autoglm.assistant.core.planner.CoordinatorDecision
import com.autoglm.assistant.core.planner.PlannedSubTask
import com.autoglm.assistant.core.planner.SupervisionResult
import com.autoglm.assistant.core.planner.TaskPlan

/**
 * Agent 事件监听接口
 *
 * 业务目的：统一 PhoneAgent 与外层（WakeWordService/UI）的通信契约，
 * 替代原先分散的 34 个独立 lambda 回调属性。
 * 所有方法提供默认空实现，调用方仅需覆写关注的事件。
 *
 * 实现者：AgentCallbackBinder
 */
interface AgentListener {

    // ===== 1. 任务执行基础回调 =====

    /** Agent 开始执行第 [step] 步 */
    fun onStepStart(step: Int) {}

    /** AI 思考内容（含流式 token 汇总后的完整文本） */
    fun onThinking(thought: String) {}

    /** AI 生成的操作指令（XML 格式） */
    fun onAction(action: String) {}

    /** 单步执行完成，result 包含成功/失败/消息 */
    fun onStepComplete(result: StepResult) {}

    /** 任务执行完成，message 为完成总结或默认消息 */
    fun onTaskComplete(message: String) {}

    /** 任务执行出错，error 为错误描述 */
    fun onError(error: String) {}

    // ===== 2. 用户交互回调 =====

    /** Agent 需要人工干预，reason 为干预原因 */
    fun onHumanInterventionNeeded(reason: String) {}

    /** Agent 中途提问，挂起等待用户回答。返回用户输入的回答文本 */
    suspend fun onUserQuestionAsked(question: String): String = ""

    /** 用户干预指令已被处理 */
    fun onInterventionProcessed(input: String) {}

    // ===== 3. 截图/操作生命周期回调（悬浮窗控制） =====

    /** 截图前：隐藏悬浮窗避免被截入 */
    fun onBeforeScreenshot() {}

    /** 截图后：恢复悬浮窗 */
    fun onAfterScreenshot() {}

    /** 操作（点击/滑动）前：隐藏悬浮窗避免遮挡目标 */
    fun onBeforeAction() {}

    /** 操作后：恢复悬浮窗 */
    fun onAfterAction() {}

    // ===== 4. 流式输出回调 =====

    /** 收到单个流式 token */
    fun onStreamToken(token: String) {}

    /** 流式输出开始 */
    fun onStreamStart() {}

    /** 流式输出结束 */
    fun onStreamEnd() {}

    // ===== 5. 智能协调器回调 =====

    /** 协调器开始决策 */
    fun onDecisionStart() {}

    /** 协调器决策完成 */
    fun onDecisionComplete(decision: CoordinatorDecision) {}

    /** 协调器思考过程文本 */
    fun onCoordinatorThinking(thinking: String) {}

    /** 协调器当前步数/最大步数 */
    fun onCoordinatorStep(currentStep: Int, maxSteps: Int) {}

    /** 开始规划任务 */
    fun onPlanningStart() {}

    /** 任务规划完成，plan 为 null 表示规划失败 */
    fun onPlanningComplete(plan: TaskPlan?) {}

    /** 生成子任务 */
    fun onSubTaskGenerated(task: PlannedSubTask) {}

    /** 开始执行子任务 */
    fun onSubTaskStart(task: PlannedSubTask) {}

    /** 子任务监督评估结果 */
    fun onSupervisionResult(result: SupervisionResult) {}

    // ===== 6. Prompt 优化器回调 =====

    /** 正在优化 Prompt */
    fun onPromptOptimizing() {}

    /** Prompt 优化完成 */
    fun onPromptOptimized(optimizedPrompt: String) {}

    /** 正在生成任务总结 */
    fun onTaskSummarizing() {}

    /** 任务总结完成 */
    fun onTaskSummary(summary: String) {}

    // ===== 7. 意图识别器回调 =====

    /** 正在识别意图 */
    fun onIntentRecognizing() {}

    /** 意图识别完成 */
    fun onIntentRecognized(result: IntentResult) {}

    // ===== 8. 其他 =====

    /** ADB Keyboard 未安装，需显示安装提示 */
    fun onAdbKeyboardNotInstalled() {}
}
