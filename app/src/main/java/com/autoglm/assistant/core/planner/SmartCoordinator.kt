package com.autoglm.assistant.core.planner

import com.autoglm.assistant.ai.Message
import com.autoglm.assistant.ai.ModelClient
import com.autoglm.assistant.util.Logger
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
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
    private val gson = Gson()
    private var coordinatorClient: ModelClient? = null
    private val gatheredInfo = mutableListOf<String>()  // 收集的信息
    private var stepCount = 0  // 当前步数

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
            val systemPrompt = if (language == "en") {
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
                        onCoordinatorThinking?.invoke(thinking)
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
            Logger.e(Logger.AGENT, "Decision making failed", e)
            return null
        }
    }

    /**
     * 构建决策提示词
     */
    private fun buildDecisionPrompt(
        originalTask: String,
        executionHistory: String,
        language: String
    ): String {
        return when (language) {
            "en" -> """
Original task: $originalTask

Execution history so far:
$executionHistory

Current screenshot: (attached)

Please analyze the current state and decide the next step.
            """.trimIndent()

            else -> """
原始任务：$originalTask

已执行的步骤：
$executionHistory

当前截图：（已附加）

请分析当前状态并决定下一步操作。
            """.trimIndent()
        }
    }

    /**
     * 解析决策响应
     */
    private fun parseDecisionResponse(responseText: String): CoordinatorDecision? {
        try {
            val jsonText = extractJson(responseText)
            if (jsonText.isBlank()) {
                return null
            }

            val response = gson.fromJson(jsonText, DecisionResponse::class.java)

            return CoordinatorDecision(
                status = when (response.status.lowercase()) {
                    "continue" -> DecisionStatus.CONTINUE
                    "complete" -> DecisionStatus.COMPLETE
                    "failed" -> DecisionStatus.FAILED
                    else -> DecisionStatus.CONTINUE
                },
                nextInstruction = response.nextInstruction,
                assessment = response.assessment,
                gatheredInfo = response.gatheredInfo ?: ""
            )

        } catch (e: JsonSyntaxException) {
            Logger.e(Logger.AGENT, "Failed to parse decision response", e)
            return null
        }
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
     * 从响应中提取JSON
     */
    private fun extractJson(text: String): String {
        val jsonBlockRegex = "```json\\s*([\\s\\S]*?)\\s*```".toRegex()
        val jsonMatch = jsonBlockRegex.find(text)
        if (jsonMatch != null) {
            return jsonMatch.groupValues[1].trim()
        }

        val codeBlockRegex = "```\\s*([\\s\\S]*?)\\s*```".toRegex()
        val codeMatch = codeBlockRegex.find(text)
        if (codeMatch != null) {
            return codeMatch.groupValues[1].trim()
        }

        val jsonObjectRegex = "\\{[\\s\\S]*\\}".toRegex()
        val objectMatch = jsonObjectRegex.find(text)
        if (objectMatch != null) {
            return objectMatch.value.trim()
        }

        return text.trim()
    }

    /**
     * 释放资源
     */
    fun release() {
        coordinatorClient = null
        gatheredInfo.clear()
        stepCount = 0
    }

    // ==================== 内部数据类 ====================

    private data class DecisionResponse(
        @SerializedName("status")
        val status: String,

        @SerializedName("assessment")
        val assessment: String,

        @SerializedName("next_instruction")
        val nextInstruction: String?,

        @SerializedName("gathered_info")
        val gatheredInfo: String?
    )

    companion object {
        /**
         * 决策系统提示词（中文）
         */
        val DECISION_SYSTEM_PROMPT_CN = """你是一个任务协调专家，负责指导UI Agent完成用户任务。

你的职责：
1. 查看当前截图和已执行的步骤
2. 判断任务是否已完成，或决定下一步应该做什么
3. 给出简洁、明确的下一步指令（描述目标，而非具体操作）
4. 提取和总结获取到的有用信息

**关键原则：**

1. **描述目标，不描述路径**：
   - ✅ 好的指令："搜索星巴克门店"
   - ❌ 差的指令："点击搜索框，输入'星巴克'，点击搜索按钮"
   - 原因：你不知道具体的界面布局，让UI Agent自己决定如何操作

2. **基于实际状态决策**：
   - 看截图判断当前在哪个界面
   - 根据已执行的步骤判断进度
   - 不要猜测界面，不要假设功能存在

3. **判断任务完成的标准**：
   - 用户的目标已达成（如"搜索到了咖啡店列表"）
   - 或者到达需要用户介入的环节（如支付）

4. **信息收集**：
   - 如果是查询类任务，提取截图中的关键信息
   - 如果是操作类任务，记录完成的关键步骤

请严格按照以下JSON格式输出决策结果：

```json
{
  "status": "continue | complete | failed",
  "assessment": "对当前状态的评估（必须明确说明看到了什么）",
  "next_instruction": "下一步指令（如果status是continue）",
  "gathered_info": "从当前截图或执行过程中获取到的有用信息"
}```

**状态说明：**
- **continue**: 任务未完成，需要继续执行。必须提供next_instruction
- **complete**: 任务已完成或到达需要用户介入的环节
- **failed**: 任务失败，无法继续

**指令示例：**
- "打开美团外卖"
- "搜索星巴克"
- "在店铺内找到拿铁并加入购物车"
- "确认订单信息"

注意：指令应该是一句话，描述要达成的目标，而不是详细的操作步骤。
"""

        /**
         * 决策系统提示词（英文）
         */
        val DECISION_SYSTEM_PROMPT_EN = """You are a task coordination expert responsible for guiding the UI Agent to complete user tasks.

Your responsibilities:
1. View the current screenshot and executed steps
2. Determine if the task is complete, or decide what to do next
3. Provide concise, clear next instruction (describe the goal, not specific operations)
4. Extract and summarize useful information obtained

**Key Principles:**

1. **Describe goals, not paths**:
   - ✅ Good instruction: "Search for Starbucks stores"
   - ❌ Bad instruction: "Click search box, enter 'Starbucks', click search button"
   - Reason: You don't know the specific UI layout, let the UI Agent decide how to operate

2. **Make decisions based on actual state**:
   - Look at screenshot to determine current screen
   - Judge progress based on executed steps
   - Don't guess UI, don't assume features exist

3. **Criteria for task completion**:
   - User's goal has been achieved (e.g., "found coffee shop list")
   - Or reached a point requiring user intervention (e.g., payment)

4. **Information gathering**:
   - For query tasks, extract key information from screenshot
   - For operation tasks, record completed key steps

Please output decision results strictly in the following JSON format:

```json
{
  "status": "continue | complete | failed",
  "assessment": "Assessment of current state (must clearly state what you see)",
  "next_instruction": "Next instruction (if status is continue)",
  "gathered_info": "Useful information obtained from current screenshot or execution process"
}```

**Status explanation:**
- **continue**: Task not complete, need to continue. Must provide next_instruction
- **complete**: Task completed or reached point requiring user intervention
- **failed**: Task failed, cannot continue

**Instruction examples:**
- "Open Meituan Delivery"
- "Search for Starbucks"
- "Find latte in store and add to cart"
- "Confirm order information"

Note: Instructions should be one sentence describing the goal to achieve, not detailed operation steps.
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
