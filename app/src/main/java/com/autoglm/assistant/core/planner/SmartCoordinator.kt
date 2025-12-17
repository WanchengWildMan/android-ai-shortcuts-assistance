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
 * 使用更强大的模型统一负责：
 * 1. 将用户指令分解为明确的子任务
 * 2. 监督UI Agent的每步执行（检查thinking + action + screenshot）
 * 3. 判断是否达成目标，必要时提供纠正指令
 * 4. 收集和总结获取到的信息
 */
class SmartCoordinator(
    private val config: TaskPlannerConfig
) {
    private val gson = Gson()
    private var coordinatorClient: ModelClient? = null
    private val gatheredInfo = mutableListOf<String>()  // 收集的信息

    init {
        config.plannerModelConfig?.let {
            coordinatorClient = ModelClient(it)
        }
    }

    /**
     * 规划任务 - 将用户指令分解为子任务
     */
    suspend fun planTask(userTask: String, language: String = "cn"): TaskPlan? {
        if (!config.enabled || coordinatorClient == null) {
            Logger.w(Logger.AGENT, "SmartCoordinator not enabled or model not configured")
            return null
        }

        Logger.i(Logger.AGENT, "========== SMART COORDINATOR: PLANNING ==========")
        Logger.i(Logger.AGENT, "Original task: $userTask")
        Logger.startTimer("smart_planning")

        try {
            val systemPrompt = if (language == "en") {
                PlannerPrompts.SYSTEM_PROMPT_EN
            } else {
                PlannerPrompts.SYSTEM_PROMPT_CN
            }

            val userPrompt = PlannerPrompts.buildPlanningPrompt(userTask, language)

            val messages = listOf(
                Message.System(systemPrompt),
                Message.User(userPrompt)
            )

            Logger.i(Logger.AGENT, "Calling coordinator model: ${config.plannerModelConfig?.modelName}")
            Logger.startTimer("coordinator_planning_request")

            val streamContent = StringBuilder()
            val response = withTimeout(config.planningTimeout) {
                coordinatorClient!!.chat(messages, object : ModelClient.StreamCallback {
                    override fun onToken(token: String) {
                        streamContent.append(token)
                        // 每收到token就打印，方便调试
                        Logger.d(Logger.AGENT, "Coordinator token: $token")
                    }

                    override fun onThinkingComplete(thinking: String) {
                        Logger.i(Logger.AGENT, "Coordinator thinking: ${thinking.take(500)}...")
                    }

                    override fun onComplete(response: com.autoglm.assistant.ai.ModelResponse) {
                        Logger.i(Logger.AGENT, "Coordinator TTFT: ${response.timeToFirstToken}ms, Total: ${response.totalTime}ms")
                        Logger.i(Logger.AGENT, "Coordinator full response: ${streamContent.toString().take(1000)}")
                    }

                    override fun onError(error: String) {
                        Logger.e(Logger.AGENT, "Coordinator error: $error")
                    }
                })
            }

            val planningTime = Logger.endTimer("coordinator_planning_request", Logger.AGENT)
            Logger.i(Logger.AGENT, "Planning completed in ${planningTime}ms")

            val taskPlan = parsePlanningResponse(response.action, userTask)

            if (taskPlan != null) {
                Logger.i(Logger.AGENT, "Task plan created: ${taskPlan.subTasks.size} sub-tasks")
                Logger.i(Logger.AGENT, "Analysis: ${taskPlan.analysis}")
                taskPlan.subTasks.forEachIndexed { index, task ->
                    Logger.i(Logger.AGENT, "  Sub-task ${index + 1}: ${task.goal}")
                }
            } else {
                Logger.e(Logger.AGENT, "Failed to parse task plan")
            }

            val totalTime = Logger.endTimer("smart_planning", Logger.AGENT)
            Logger.i(Logger.AGENT, "========== PLANNING COMPLETE (${totalTime}ms) ==========")

            return taskPlan

        } catch (e: Exception) {
            Logger.e(Logger.AGENT, "Task planning failed", e)
            return null
        }
    }

    /**
     * 监督子任务执行 - 检查UI Agent的执行结果并提供反馈
     */
    suspend fun superviseExecution(
        subTask: PlannedSubTask,
        agentThinking: String,
        agentAction: String,
        screenshotBase64: String?,
        language: String = "cn"
    ): SupervisionResult {
        if (!config.enableSupervision || coordinatorClient == null) {
            // 如果未启用监督，默认认为成功
            return SupervisionResult(
                status = SupervisionStatus.SUCCESS,
                assessment = "Supervision disabled",
                correctionInstruction = null,
                gatheredInfo = ""
            )
        }

        Logger.i(Logger.AGENT, "========== SMART COORDINATOR: SUPERVISING SUB-TASK ${subTask.index} ==========")
        Logger.startTimer("smart_supervision")

        try {
            val systemPrompt = buildSupervisionSystemPrompt(language)
            val userPrompt = buildSupervisionPrompt(subTask, agentThinking, agentAction, language)

            val messages = listOf(
                Message.System(systemPrompt),
                Message.User(userPrompt, screenshotBase64)
            )

            Logger.i(Logger.AGENT, "Calling coordinator to supervise sub-task ${subTask.index}")
            Logger.startTimer("coordinator_supervision_request")

            val supervisionContent = StringBuilder()
            val response = coordinatorClient!!.chat(messages, object : ModelClient.StreamCallback {
                override fun onToken(token: String) {
                    supervisionContent.append(token)
                    Logger.d(Logger.AGENT, "Supervision token: $token")
                }

                override fun onThinkingComplete(thinking: String) {
                    Logger.i(Logger.AGENT, "Coordinator supervising: ${thinking.take(300)}...")
                }

                override fun onComplete(response: com.autoglm.assistant.ai.ModelResponse) {
                    Logger.i(Logger.AGENT, "Supervision TTFT: ${response.timeToFirstToken}ms, Total: ${response.totalTime}ms")
                    Logger.i(Logger.AGENT, "Supervision full response: ${supervisionContent.toString().take(500)}")
                }

                override fun onError(error: String) {
                    Logger.e(Logger.AGENT, "Supervision error: $error")
                }
            })

            val supervisionTime = Logger.endTimer("coordinator_supervision_request", Logger.AGENT)
            Logger.i(Logger.AGENT, "Supervision completed in ${supervisionTime}ms")

            val result = parseSupervisionResponse(response.action)

            if (result != null) {
                Logger.i(Logger.AGENT, "Supervision status: ${result.status}")
                Logger.i(Logger.AGENT, "Assessment: ${result.assessment}")

                if (result.status == SupervisionStatus.SUCCESS && result.gatheredInfo.isNotBlank()) {
                    gatheredInfo.add("[子任务${subTask.index}] ${result.gatheredInfo}")
                    Logger.i(Logger.AGENT, "Gathered info: ${result.gatheredInfo}")
                }

                if (result.status == SupervisionStatus.NEEDS_CORRECTION) {
                    Logger.w(Logger.AGENT, "Correction needed: ${result.correctionInstruction}")
                }
            }

            val totalTime = Logger.endTimer("smart_supervision", Logger.AGENT)
            Logger.i(Logger.AGENT, "========== SUPERVISION COMPLETE (${totalTime}ms) ==========")

            return result ?: SupervisionResult(
                status = SupervisionStatus.UNCERTAIN,
                assessment = "Failed to parse supervision response",
                correctionInstruction = null,
                gatheredInfo = ""
            )

        } catch (e: Exception) {
            Logger.e(Logger.AGENT, "Supervision failed", e)
            return SupervisionResult(
                status = SupervisionStatus.UNCERTAIN,
                assessment = "Supervision error: ${e.message}",
                correctionInstruction = null,
                gatheredInfo = ""
            )
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
    }

    /**
     * 解析规划响应
     */
    private fun parsePlanningResponse(responseText: String, originalTask: String): TaskPlan? {
        try {
            val jsonText = extractJson(responseText)
            if (jsonText.isBlank()) {
                Logger.e(Logger.AGENT, "No valid JSON found in planning response")
                return null
            }

            val planResponse = gson.fromJson(jsonText, PlanResponse::class.java)

            if (planResponse.subTasks.size > config.maxSubTasks) {
                Logger.w(Logger.AGENT, "Too many sub-tasks (${planResponse.subTasks.size}), truncating to ${config.maxSubTasks}")
                planResponse.subTasks = planResponse.subTasks.take(config.maxSubTasks)
            }

            return TaskPlan(
                originalTask = originalTask,
                analysis = planResponse.analysis,
                subTasks = planResponse.subTasks.map { subTask ->
                    PlannedSubTask(
                        index = subTask.index,
                        goal = subTask.goal,
                        currentState = subTask.currentState,
                        actions = subTask.actions,
                        context = subTask.context,
                        dependencies = subTask.dependencies
                    )
                },
                estimatedDuration = planResponse.estimatedDuration
            )

        } catch (e: JsonSyntaxException) {
            Logger.e(Logger.AGENT, "Failed to parse planning response", e)
            return null
        } catch (e: Exception) {
            Logger.e(Logger.AGENT, "Error parsing planning response", e)
            return null
        }
    }

    /**
     * 解析监督响应
     */
    private fun parseSupervisionResponse(responseText: String): SupervisionResult? {
        try {
            val jsonText = extractJson(responseText)
            if (jsonText.isBlank()) {
                return null
            }

            val response = gson.fromJson(jsonText, SupervisionResponse::class.java)

            return SupervisionResult(
                status = when (response.status.lowercase()) {
                    "success" -> SupervisionStatus.SUCCESS
                    "needs_correction" -> SupervisionStatus.NEEDS_CORRECTION
                    "failed" -> SupervisionStatus.FAILED
                    else -> SupervisionStatus.UNCERTAIN
                },
                assessment = response.assessment,
                correctionInstruction = response.correctionInstruction,
                gatheredInfo = response.gatheredInfo ?: ""
            )

        } catch (e: JsonSyntaxException) {
            Logger.e(Logger.AGENT, "Failed to parse supervision response", e)
            return null
        }
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
     * 构建监督系统提示词
     */
    private fun buildSupervisionSystemPrompt(language: String): String {
        return when (language) {
            "en" -> SUPERVISION_SYSTEM_PROMPT_EN
            else -> SUPERVISION_SYSTEM_PROMPT_CN
        }
    }

    /**
     * 构建监督用户提示词
     */
    private fun buildSupervisionPrompt(
        subTask: PlannedSubTask,
        agentThinking: String,
        agentAction: String,
        language: String
    ): String {
        return when (language) {
            "en" -> """
You need to supervise the execution of the following sub-task:

**Sub-task Goal:**
${subTask.goal}

**Expected Operations:**
${subTask.actions}

**Context:**
${subTask.context}

**UI Agent's Execution:**
- Thinking: $agentThinking
- Action: $agentAction

**Current Screenshot:**
Please check the screenshot to verify if the sub-task goal has been achieved.

Please analyze and provide your supervision result in JSON format.
            """.trimIndent()

            else -> """
你需要监督以下子任务的执行情况：

**子任务目标：**
${subTask.goal}

**应该执行的操作：**
${subTask.actions}

**上下文：**
${subTask.context}

**UI Agent的执行情况：**
- 思考过程：$agentThinking
- 执行的操作：$agentAction

**当前截图：**
请查看截图，判断子任务目标是否已达成。

请分析并以JSON格式提供你的监督结果。
            """.trimIndent()
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        coordinatorClient = null
        gatheredInfo.clear()
    }

    // ==================== 内部数据类 ====================

    private data class PlanResponse(
        @SerializedName("analysis")
        val analysis: String,

        @SerializedName("estimated_duration")
        val estimatedDuration: Int?,

        @SerializedName("sub_tasks")
        var subTasks: List<SubTaskResponse>
    )

    private data class SubTaskResponse(
        @SerializedName("index")
        val index: Int,

        @SerializedName("goal")
        val goal: String,

        @SerializedName("current_state")
        val currentState: String,

        @SerializedName("actions")
        val actions: String,

        @SerializedName("context")
        val context: String,

        @SerializedName("dependencies")
        val dependencies: List<Int> = emptyList()
    )

    private data class SupervisionResponse(
        @SerializedName("status")
        val status: String,

        @SerializedName("assessment")
        val assessment: String,

        @SerializedName("correction_instruction")
        val correctionInstruction: String?,

        @SerializedName("gathered_info")
        val gatheredInfo: String?
    )

    companion object {
        /**
         * 监督系统提示词（中文）
         */
        val SUPERVISION_SYSTEM_PROMPT_CN = """你是一个任务监督专家，负责监督UI Agent执行任务的质量。

你的职责：
1. 查看UI Agent的思考过程和执行的操作
2. 查看执行后的截图
3. 判断子任务目标是否已正确达成
4. 如果有问题，提供清晰的纠正指令
5. 提取和总结获取到的有用信息

请严格按照以下JSON格式输出监督结果：

```json
{
  "status": "success | needs_correction | failed | uncertain",
  "assessment": "对执行情况的详细评估",
  "correction_instruction": "纠正指令（如果status是needs_correction）",
  "gathered_info": "从当前截图或执行过程中获取到的有用信息"
}```

**状态说明：**
- **success**: 子任务已正确完成，达成了预期目标
- **needs_correction**: 操作方向正确但有偏差，需要纠正
- **failed**: 操作完全错误，未能达成目标
- **uncertain**: 无法判断，需要继续观察

**纠正指令原则：**
1. 指令要具体、可执行
2. 说明哪里出了问题
3. 提供明确的改进方向
4. 例如："当前点击位置偏上，实际的搜索框在坐标(500, 200)处，请重新点击正确位置"

**信息收集原则：**
1. 提取截图中的关键信息（如商品价格、店铺名称、评分等）
2. 总结操作达成的中间状态
3. 记录可能对后续任务有帮助的信息
4. 例如："找到星巴克门店，地址：XX路XX号，评分4.8分，配送费5元"
"""

        /**
         * 监督系统提示词（英文）
         */
        val SUPERVISION_SYSTEM_PROMPT_EN = """You are a task supervision expert responsible for monitoring the quality of UI Agent's task execution.

Your responsibilities:
1. Review UI Agent's thinking process and executed operations
2. View the screenshot after execution
3. Determine if the sub-task goal has been correctly achieved
4. If there are problems, provide clear correction instructions
5. Extract and summarize useful information obtained

Please output supervision results strictly in the following JSON format:

```json
{
  "status": "success | needs_correction | failed | uncertain",
  "assessment": "Detailed assessment of the execution",
  "correction_instruction": "Correction instruction (if status is needs_correction)",
  "gathered_info": "Useful information obtained from current screenshot or execution process"
}```

**Status explanation:**
- **success**: Sub-task correctly completed, achieved expected goal
- **needs_correction**: Operation direction correct but has deviation, needs correction
- **failed**: Operation completely wrong, failed to achieve goal
- **uncertain**: Cannot determine, need to continue observing
"""
    }
}

/**
 * 监督状态
 */
enum class SupervisionStatus {
    SUCCESS,            // 成功完成
    NEEDS_CORRECTION,   // 需要纠正
    FAILED,            // 失败
    UNCERTAIN          // 不确定
}

/**
 * 监督结果
 */
data class SupervisionResult(
    val status: SupervisionStatus,
    val assessment: String,
    val correctionInstruction: String?,
    val gatheredInfo: String
)
