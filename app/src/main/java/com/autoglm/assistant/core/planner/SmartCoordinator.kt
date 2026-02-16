package com.autoglm.assistant.core.planner

import com.autoglm.assistant.ai.Message
import com.autoglm.assistant.ai.ModelClient
import com.autoglm.assistant.util.Logger
import kotlinx.coroutines.withTimeout

/**
 * 智能协调器
 * 使用更强大的模型负责：
 * 1. 根据当前执行状态决定下一步指令
 * 2. 判断任务是否完成
 * 3. 收集和总结获取到的信息
 */
class SmartCoordinator(
    private val config: TaskPlannerConfig
) {
    private var coordinatorClient: ModelClient? = null
    private val gatheredInfo = mutableListOf<String>()  // 收集的信息
    private var stepCount = 0  // 当前步数

    /** 获取当前协调器步数 */
    fun getStepCount(): Int = stepCount

    /** 获取协调器最大步数配置 */
    fun getMaxSteps(): Int = config.maxCoordinatorSteps

    // 回调接口 - 用于UI显示
    var onDecisionStart: (() -> Unit)? = null           // 开始决策
    var onDecisionComplete: ((CoordinatorDecision) -> Unit)? = null  // 决策完成

    // 流式输出回调 - 用于打字机效果
    var onStreamToken: ((String) -> Unit)? = null       // 流式token回调
    var onStreamStart: (() -> Unit)? = null             // 流式输出开始
    var onStreamEnd: (() -> Unit)? = null               // 流式输出结束
    var onCoordinatorThinking: ((String) -> Unit)? = null  // 协调者思考过程

    init {
        config.plannerModelConfig?.let {
            coordinatorClient = ModelClient(it)
        }
    }

    /**
     * 决定下一步操作
     * 根据原始任务、执行历史和当前截图，决定下一步应该做什么
     */
    suspend fun decideNextStep(
        originalTask: String,
        executionHistory: String,
        screenshotBase64: String?,
        language: String = "cn"
    ): CoordinatorDecision? {
        if (coordinatorClient == null) {
            Logger.w(Logger.AGENT, "SmartCoordinator model not configured")
            return null
        }

        stepCount++
        Logger.i(Logger.AGENT, "========== SMART COORDINATOR: DECIDING STEP $stepCount ==========")
        Logger.i(Logger.AGENT, "Original task: $originalTask")
        Logger.startTimer("coordinator_decision")

        try {
            val systemPrompt = if (config.customSystemPrompt.isNotBlank()) {
                config.customSystemPrompt
            } else if (language == "en") {
                DECISION_SYSTEM_PROMPT_EN
            } else {
                DECISION_SYSTEM_PROMPT_CN
            }

            val userPrompt = buildDecisionPrompt(originalTask, executionHistory, language)

            val messages = listOf(
                Message.System(systemPrompt),
                Message.User(userPrompt, screenshotBase64)
            )

            Logger.i(Logger.AGENT, "Calling coordinator model: ${config.plannerModelConfig?.modelName}")
            Logger.i(Logger.AGENT, "[DEBUG] API URL: ${config.plannerModelConfig?.baseUrl}")
            Logger.i(Logger.AGENT, "[DEBUG] System prompt length: ${systemPrompt.length} chars")
            Logger.i(Logger.AGENT, "[DEBUG] User prompt length: ${userPrompt.length} chars")
            Logger.i(Logger.AGENT, "[DEBUG] Screenshot attached: ${screenshotBase64 != null}")
            Logger.i(Logger.AGENT, "[DEBUG] Timeout: ${config.planningTimeout}ms")
            Logger.startTimer("coordinator_decision_request")

            onDecisionStart?.invoke()
            onStreamStart?.invoke()

            val response = withTimeout(config.planningTimeout) {
                coordinatorClient!!.chat(messages, object : ModelClient.StreamCallback {
                    override fun onToken(token: String) {
                        onStreamToken?.invoke(token)
                        Logger.d(Logger.AGENT, "Coordinator decision token: $token")
                    }

                    override fun onThinkingComplete(thinking: String) {
                        Logger.i(Logger.AGENT, "[COORDINATOR] Decision thinking (${thinking.length} chars): ${thinking.take(200)}...")
                        // 后处理：将 JSON 格式的思考内容转为可读文本
                        val formatted = formatThinkingOutput(thinking)
                        onCoordinatorThinking?.invoke(formatted)
                    }

                    override fun onComplete(response: com.autoglm.assistant.ai.ModelResponse) {
                        Logger.i(Logger.AGENT, "[COORDINATOR] Decision TTFT: ${response.timeToFirstToken}ms, Total: ${response.totalTime}ms")
                        onStreamEnd?.invoke()
                    }

                    override fun onError(error: String) {
                        Logger.e(Logger.AGENT, "[COORDINATOR] Decision error: $error")
                        onStreamEnd?.invoke()
                    }
                })
            }

            val decisionTime = Logger.endTimer("coordinator_decision_request", Logger.AGENT)
            Logger.i(Logger.AGENT, "Decision completed in ${decisionTime}ms")
            Logger.i(Logger.AGENT, "[DEBUG] Raw response.action: ${response.action.take(200)}...")
            Logger.i(Logger.AGENT, "[DEBUG] Response.action length: ${response.action.length} chars")
            Logger.i(Logger.AGENT, "[DEBUG] Response.thinking length: ${response.thinking.length} chars")

            // 业务目的：协调器只产出"下一步目标指令"给 PhoneAgent，
            // 不再强依赖 JSON 协议，减少格式波动导致的执行中断。
            val decision = parseDecisionResponse(response.action)

            if (decision != null) {
                Logger.i(Logger.AGENT, "Decision status: ${decision.status}")
                Logger.i(Logger.AGENT, "Assessment: ${decision.assessment}")
                if (decision.nextInstruction != null) {
                    Logger.i(Logger.AGENT, "Next instruction: ${decision.nextInstruction}")
                }
                if (decision.gatheredInfo.isNotBlank()) {
                    gatheredInfo.add("[步骤$stepCount] ${decision.gatheredInfo}")
                    Logger.i(Logger.AGENT, "Gathered info: ${decision.gatheredInfo}")
                }

                onDecisionComplete?.invoke(decision)
            }

            val totalTime = Logger.endTimer("coordinator_decision", Logger.AGENT)
            Logger.i(Logger.AGENT, "========== DECISION COMPLETE (${totalTime}ms) ==========")

            return decision

        } catch (e: Exception) {
            Logger.e(Logger.AGENT, "Decision making failed: ${e.javaClass.simpleName}", e)
            Logger.e(Logger.AGENT, "[DEBUG] Exception message: ${e.message}")
            Logger.e(Logger.AGENT, "[DEBUG] Exception cause: ${e.cause?.message}")
            when (e) {
                is kotlinx.coroutines.TimeoutCancellationException -> {
                    Logger.e(Logger.AGENT, "[DEBUG] Timeout after ${config.planningTimeout}ms")
                }
                is java.net.UnknownHostException -> {
                    Logger.e(Logger.AGENT, "[DEBUG] Cannot resolve host: ${e.message}")
                }
                is java.net.SocketTimeoutException -> {
                    Logger.e(Logger.AGENT, "[DEBUG] Socket timeout")
                }
                is javax.net.ssl.SSLException -> {
                    Logger.e(Logger.AGENT, "[DEBUG] SSL error: ${e.message}")
                }
            }
            return null
        }
    }

    /**
     * 构建决策提示词
     * 第一次协调时告知当前在 AutoGLM 页面（已知界面，无需截图）
     */
    private fun buildDecisionPrompt(
        originalTask: String,
        executionHistory: String,
        language: String
    ): String {
        val isFirstStep = stepCount <= 1
        val historySection = executionHistory.ifBlank {
            if (language == "en") "(none yet)" else "（暂无）"
        }

        return when (language) {
            "en" -> buildString {
                appendLine("Original task: $originalTask")
                appendLine()
                appendLine("Execution history so far:")
                appendLine(historySection)
                if (isFirstStep) {
                    appendLine()
                    appendLine("Current state: User is on the AutoGLM assistant main page. No screenshot needed for this known interface.")
                }
                appendLine()
                appendLine("Please analyze and decide the next step.")
            }.trimEnd()

            else -> buildString {
                appendLine("原始任务：$originalTask")
                appendLine()
                appendLine("已执行的步骤：")
                appendLine(historySection)
                if (isFirstStep) {
                    appendLine()
                    appendLine("当前状态：用户处于 AutoGLM 助手主页面，这是已知界面，无需截图。")
                }
                appendLine()
                appendLine("请分析当前状态并决定下一步操作。")
            }.trimEnd()
        }
    }

    /**
     * 解析决策响应
     * 设计原则：模型输出什么就直接当指令用，协调器只是"说话的"不是"执行的"。
     * 只判断 [COMPLETE] / [FAILED] 前缀，其余全部作为 nextInstruction 交给 PhoneAgent。
     * 如果模型不听话输出了 JSON，兜底提取 instruction 字段。
     */
    private fun parseDecisionResponse(responseText: String): CoordinatorDecision? {
        val raw = responseText.trim()
        Logger.i(Logger.AGENT, "[DEBUG] parseDecisionResponse input (${raw.length} chars): ${raw.take(200)}")
        if (raw.isBlank()) {
            Logger.w(Logger.AGENT, "[DEBUG] Response is blank, returning null")
            return null
        }

        // 步骤1：如果模型输出了 JSON，提取其中的 instruction 文本
        val text = extractTextFromJsonIfNeeded(raw)
        val upper = text.uppercase().trimStart()
        Logger.i(Logger.AGENT, "[DEBUG] Effective text: ${text.take(100)}")

        // 步骤2：判断完成/失败前缀
        return when {
            upper.startsWith("[COMPLETE]") || upper.startsWith("COMPLETE:") -> {
                val reason = text
                    .replace(Regex("^\\[?COMPLETE\\]?:?\\s*", RegexOption.IGNORE_CASE), "")
                    .trim().ifBlank { "任务已完成" }
                CoordinatorDecision(
                    status = DecisionStatus.COMPLETE,
                    assessment = reason,
                    nextInstruction = null,
                    gatheredInfo = ""
                )
            }
            upper.startsWith("[FAILED]") || upper.startsWith("FAILED:") -> {
                val reason = text
                    .replace(Regex("^\\[?FAILED\\]?:?\\s*", RegexOption.IGNORE_CASE), "")
                    .trim().ifBlank { "任务失败" }
                CoordinatorDecision(
                    status = DecisionStatus.FAILED,
                    assessment = reason,
                    nextInstruction = null,
                    gatheredInfo = ""
                )
            }
            else -> {
                // 步骤3：其余全部作为 PhoneAgent 的下一步目标指令
                val instruction = text
                    .replace(Regex("^\\[?CONTINUE\\]?:?\\s*", RegexOption.IGNORE_CASE), "")
                    .trim()
                // 最后一道防线：再次过滤操作指令
                val finalInstruction = stripActionCommands(instruction.ifBlank { text })
                CoordinatorDecision(
                    status = DecisionStatus.CONTINUE,
                    assessment = "继续执行",
                    nextInstruction = finalInstruction,  // 绝不返回空指令（stripActionCommands 内部有兜底）
                    gatheredInfo = ""
                )
            }
        }
    }

    /**
     * 从模型输出中提取有效文本指令
     * 如果模型不听话输出了 JSON，提取其中的 instruction 字段；
     * 如果是纯文本，原样返回。
     * 最后过滤掉 do(action=...) 等具体操作指令。
     * 核心原则：绝不返回空字符串，总有东西交给 PhoneAgent。
     */
    private fun extractTextFromJsonIfNeeded(raw: String): String {
        val cleaned = stripMarkdownCodeBlock(raw)
        // 非 JSON 格式 → 过滤操作指令后返回
        if (!(cleaned.startsWith("{") && cleaned.endsWith("}"))) {
            return stripActionCommands(raw)
        }

        return try {
            val json = org.json.JSONObject(cleaned)
            // 按优先级依次尝试常见的 instruction 字段名
            val instruction = json.optString("next_instruction", "")
                .ifBlank { json.optString("instruction", "") }
                .ifBlank { json.optString("next_step", "") }
                .ifBlank { json.optString("goal", "") }
                .ifBlank { json.optString("assessment", "") }
            // 提取到了有效字段 → 过滤操作指令后返回
            if (instruction.isNotBlank()) {
                val filtered = stripActionCommands(instruction)
                Logger.i(Logger.AGENT, "[DEBUG] Extracted from JSON: ${filtered.take(80)}")
                return filtered.ifBlank { raw }  // 如果过滤后为空，兜底返回原文
            } else {
                // JSON 中没有可识别的字段，把所有 value 拼起来当指令
                val values = json.keys().asSequence().map { json.optString(it, "") }.filter { it.isNotBlank() }
                val joined = values.joinToString("；")
                Logger.w(Logger.AGENT, "[DEBUG] No known JSON field, joining all values: ${joined.take(80)}")
                stripActionCommands(joined.ifBlank { raw })
            }
        } catch (_: Exception) {
            stripActionCommands(raw)  // JSON 解析失败 → 过滤后当纯文本用
        }
    }

    /**
     * 过滤协调器输出中混入的 PhoneAgent 操作指令
     * 协调器应该只输出目标描述（"打开微信"），不应输出具体操作（"do(action='Tap')"）
     * 
     * 过滤模式：
     * - do(action="...", ...) → 删除
     * - finish(message="...") → 删除
     * - 点击坐标(x,y) → 删除
     * - 点击"按钮名" → 点击"按钮名"（保留，这是目标描述）
     */
    private fun stripActionCommands(text: String): String {
        var result = text
            // 过滤 do(action=...) 格式
            .replace(Regex("""do\s*\(\s*action\s*=\s*["'][^"']*["'][^)]*\)""", RegexOption.IGNORE_CASE), "")
            // 过滤 finish(message=...) 格式
            .replace(Regex("""finish\s*\(\s*message\s*=\s*["'][^"']*["']\s*\)""", RegexOption.IGNORE_CASE), "")
            // 过滤坐标格式：(123, 456) 或 坐标(123,456)
            .replace(Regex("""[坐标coordinates]*\s*\(\s*\d+\s*,\s*\d+\s*\)""", RegexOption.IGNORE_CASE), "")
            .trim()
        
        // 清理多余空白
        result = result.replace(Regex("""\s{2,}"""), " ").trim()
        
        if (result.isBlank() && text.isNotBlank()) {
            Logger.w(Logger.AGENT, "[COORDINATOR] Output was entirely action commands, stripped to blank. Returning original: ${text.take(100)}")
            // 过滤后变空了，返回原文让 PhoneAgent 自己处理
            // 总比返回一个无意义的兜底文本好（如"继续执行上一步目标"对第一次协调无意义）
            return text
        }
        return result
    }

    /**
     * 获取所有收集到的信息
     */
    fun getAllGatheredInfo(): List<String> = gatheredInfo.toList()

    /**
     * 获取信息总结
     */
    fun getInfoSummary(): String {
        return if (gatheredInfo.isEmpty()) {
            "无收集到的信息"
        } else {
            gatheredInfo.joinToString("\n")
        }
    }

    /**
     * 清空收集的信息
     */
    fun clearGatheredInfo() {
        gatheredInfo.clear()
        stepCount = 0
    }

    /**
     * 释放资源
     */
    fun release() {
        coordinatorClient = null
        gatheredInfo.clear()
        stepCount = 0
    }

    /**
     * 后处理协调器思考输出
     * 检测 JSON 格式（含 markdown 代码块包裹）并转为可读的自然语言文本
     */
    private fun formatThinkingOutput(thinking: String): String {
        // 先剥离 markdown 代码块标记（```json ... ```）
        val cleaned = stripMarkdownCodeBlock(thinking.trim())
        // 检测是否为 JSON 对象或数组
        if (!((cleaned.startsWith("{") && cleaned.endsWith("}")) ||
              (cleaned.startsWith("[") && cleaned.endsWith("]")))) {
            return thinking
        }
        return try {
            val json = org.json.JSONObject(cleaned)
            buildString {
                for (key in json.keys()) {
                    val label = THINKING_KEY_LABELS[key] ?: key
                    appendLine("$label：${json.get(key)}")
                }
            }.trimEnd()
        } catch (_: Exception) {
            try {
                val arr = org.json.JSONArray(cleaned)
                buildString {
                    for (i in 0 until arr.length()) {
                        appendLine("- ${arr.get(i)}")
                    }
                }.trimEnd()
            } catch (_: Exception) {
                thinking
            }
        }
    }

    /**
     * 剥离 markdown 代码块标记
     * 处理 ```json\n{...}\n``` 或 ```\n{...}\n``` 格式
     */
    private fun stripMarkdownCodeBlock(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```")) return trimmed
        // 去掉首行 ```json 或 ``` 和末尾 ```
        val lines = trimmed.lines()
        val start = if (lines.first().startsWith("```")) 1 else 0
        val end = if (lines.last().trim() == "```") lines.size - 1 else lines.size
        return lines.subList(start, end).joinToString("\n").trim()
    }

    companion object {
        /** 思考 JSON key → 中文标签映射 */
        private val THINKING_KEY_LABELS = mapOf(
            "assessment" to "当前评估",
            "analysis" to "分析",
            "current_state" to "当前状态",
            "next_step" to "下一步",
            "next_action" to "下一步操作",
            "instruction" to "指令",
            "reason" to "原因",
            "reasoning" to "推理",
            "status" to "状态",
            "thought" to "思考",
            "observation" to "观察",
            "plan" to "计划",
            "goal" to "目标",
            "progress" to "进度",
            "gathered_info" to "收集信息"
        )

        /**
         * 决策系统提示词（中文）
         */
        val DECISION_SYSTEM_PROMPT_CN = """你是任务协调器。你的职责是为 PhoneAgent 规划下一步目标。

## 输出格式（严格遵守）

只输出一句简短的自然语言目标描述，例如：

打开地图应用并搜索星巴克

如果任务完成：
[COMPLETE] 已成功找到目标信息

如果任务失败：
[FAILED] 页面无法继续操作

## 禁止输出的格式（绝对不要输出）

❌ do(action="Tap", x=100, y=200)
❌ finish(message="完成")
❌ {"status": "continue", "instruction": "..."}
❌ 点击坐标(500,300)
❌ 向下滑动屏幕
❌ 输入文字"测试"

## 角色边界

你是"规划者"，不是"执行者"：
- ✅ 你说：打开微信并找到张三的聊天
- ❌ 你不说：点击微信图标，然后点击搜索框，输入"张三"

你只负责描述目标，PhoneAgent 会自己决定如何操作屏幕。
"""

        /**
         * 决策系统提示词（英文）
         */
        val DECISION_SYSTEM_PROMPT_EN = """You are a task coordinator. Your job is to plan the next goal for PhoneAgent.

## Output format (strictly follow)

Write exactly one line of natural language goal description, example:

Open map app and search for Starbucks

If task is complete:
[COMPLETE] Successfully found the target information

If task failed:
[FAILED] Cannot proceed from current page

## Forbidden output formats (NEVER output these)

❌ do(action="Tap", x=100, y=200)
❌ finish(message="Done")
❌ {"status": "continue", "instruction": "..."}
❌ tap coordinates(500,300)
❌ swipe down the screen
❌ type text "test"

## Role boundary

You are a "planner", not an "executor":
- ✅ You say: Open WeChat and find the chat with John
- ❌ You don't say: Tap WeChat icon, then tap search box, type "John"

You only describe the goal; PhoneAgent will figure out how to operate the screen.
"""
    }
}

/**
 * 决策状态
 */
enum class DecisionStatus {
    CONTINUE,       // 继续执行
    COMPLETE,       // 任务完成
    FAILED          // 任务失败
}

/**
 * 协调器决策结果
 */
data class CoordinatorDecision(
    val status: DecisionStatus,
    val assessment: String,
    val nextInstruction: String?,
    val gatheredInfo: String
)

// 保留旧的枚举和数据类以保持兼容性，但标记为废弃
@Deprecated("Use CoordinatorDecision instead")
enum class SupervisionStatus {
    SUCCESS,
    NEEDS_CORRECTION,
    FAILED,
    UNCERTAIN
}

@Deprecated("Use CoordinatorDecision instead")
data class SupervisionResult(
    val status: SupervisionStatus,
    val assessment: String,
    val correctionInstruction: String?,
    val gatheredInfo: String
)
