package com.autoglm.assistant.core.planner

import com.autoglm.assistant.ai.Message
import com.autoglm.assistant.ai.ModelClient
import com.autoglm.assistant.ai.ModelConfig
import com.autoglm.assistant.util.Logger
import kotlinx.coroutines.withTimeout

/**
 * Prompt优化器
 * 将用户的简短指令扩展为更详细、具体的任务描述
 * 帮助UI Agent更好地理解和执行任务
 */
class PromptOptimizer(
    private val modelConfig: ModelConfig
) {
    private val client = ModelClient(modelConfig)

    // 回调
    var onOptimizing: (() -> Unit)? = null
    var onOptimized: ((String) -> Unit)? = null
    var onStreamToken: ((String) -> Unit)? = null
    var onSummarizing: (() -> Unit)? = null
    var onSummarized: ((String) -> Unit)? = null

    /**
     * 优化用户prompt
     * @param userPrompt 用户原始输入
     * @param language 语言 cn/en
     * @param conversationContext 对话上下文（可选），用于理解"继续"等指代性指令
     * @return 优化后的prompt，如果优化失败则返回原始prompt
     */
    suspend fun optimize(userPrompt: String, language: String = "cn", conversationContext: List<Pair<String, String>> = emptyList()): String {
        Logger.i(Logger.AGENT, "========== PROMPT OPTIMIZER: START ==========")
        Logger.i(Logger.AGENT, "Original prompt: $userPrompt")
        Logger.i(Logger.AGENT, "Context messages: ${conversationContext.size}")
        Logger.startTimer("prompt_optimization")

        onOptimizing?.invoke()

        try {
            val systemPrompt = if (language == "en") SYSTEM_PROMPT_EN else SYSTEM_PROMPT_CN
            val userMessage = buildUserPrompt(userPrompt, language, conversationContext)

            val messages = listOf(
                Message.System(systemPrompt),
                Message.User(userMessage)
            )

            Logger.i(Logger.AGENT, "Calling optimizer model: ${modelConfig.modelName}")

            val response = withTimeout(30000L) {
                client.chat(messages, object : ModelClient.StreamCallback {
                    override fun onToken(token: String) {
                        onStreamToken?.invoke(token)
                    }

                    override fun onThinkingComplete(thinking: String) {
                        Logger.d(Logger.AGENT, "[OPTIMIZER] Thinking: ${thinking.take(100)}...")
                    }

                    override fun onComplete(response: com.autoglm.assistant.ai.ModelResponse) {
                        Logger.i(Logger.AGENT, "[OPTIMIZER] Complete in ${response.totalTime}ms")
                    }

                    override fun onError(error: String) {
                        Logger.e(Logger.AGENT, "[OPTIMIZER] Error: $error")
                    }
                })
            }

            // 使用rawContent而不是action，因为优化器返回的是自然语言，不是Agent动作格式
            // 同时移除可能的思考标签（如DeepSeek的<think>标签）
            val rawContent = response.rawContent.trim()
            val optimizedPrompt = stripThinkingTags(rawContent)
            val time = Logger.endTimer("prompt_optimization", Logger.AGENT)
            Logger.i(Logger.AGENT, "Optimized prompt (${time}ms): $optimizedPrompt")
            Logger.i(Logger.AGENT, "========== PROMPT OPTIMIZER: END ==========")

            // 如果优化后的prompt为空或太短，使用原始prompt
            if (optimizedPrompt.isBlank() || optimizedPrompt.length < userPrompt.length / 2) {
                Logger.w(Logger.AGENT, "Optimized prompt too short or empty, using original")
                onOptimized?.invoke(userPrompt)
                return userPrompt
            }

            onOptimized?.invoke(optimizedPrompt)
            return optimizedPrompt

        } catch (e: Exception) {
            Logger.e(Logger.AGENT, "Prompt optimization failed, using original", e)
            Logger.endTimer("prompt_optimization", Logger.AGENT)
            return userPrompt
        }
    }

    private fun buildUserPrompt(userPrompt: String, language: String, conversationContext: List<Pair<String, String>>): String {
        val contextSection = if (conversationContext.isNotEmpty()) {
            val contextStr = conversationContext.takeLast(6).joinToString("\n") { (role, content) ->
                val roleLabel = if (language == "en") {
                    if (role == "user") "User" else "Assistant"
                } else {
                    if (role == "user") "用户" else "助手"
                }
                "$roleLabel: $content"
            }
            if (language == "en") {
                "\n\nRecent conversation context:\n$contextStr\n"
            } else {
                "\n\n最近的对话上下文：\n$contextStr\n"
            }
        } else ""

        return if (language == "en") {
            """
Please optimize this user instruction for a phone automation agent:
$contextSection
User instruction: "$userPrompt"

If the instruction references previous context (like "continue", "same thing", etc.), interpret it based on the conversation history.
Provide a clear, specific, and actionable task description.
            """.trimIndent()
        } else {
            """
请优化以下用户指令，使其更适合手机自动化Agent执行：
$contextSection
用户指令："$userPrompt"

如果指令引用了之前的上下文（如"继续"、"再来一次"等），请根据对话历史来理解其含义。
请提供清晰、具体、可执行的任务描述。
            """.trimIndent()
        }
    }

    /**
     * 生成任务总结
     * @param originalTask 原始任务描述
     * @param conversationContext 对话上下文（包含执行过程）
     * @param language 语言 cn/en
     * @return 任务总结，如果生成失败则返回默认消息
     */
    suspend fun summarize(
        originalTask: String,
        conversationContext: List<Pair<String, String>>,
        language: String = "cn"
    ): String {
        Logger.i(Logger.AGENT, "========== TASK SUMMARIZER: START ==========")
        Logger.i(Logger.AGENT, "Original task: $originalTask")
        Logger.i(Logger.AGENT, "Context messages: ${conversationContext.size}")
        Logger.startTimer("task_summary")

        onSummarizing?.invoke()

        try {
            val systemPrompt = if (language == "en") SUMMARY_SYSTEM_PROMPT_EN else SUMMARY_SYSTEM_PROMPT_CN
            val userMessage = buildSummaryPrompt(originalTask, language, conversationContext)

            val messages = listOf(
                Message.System(systemPrompt),
                Message.User(userMessage)
            )

            Logger.i(Logger.AGENT, "Calling summarizer model: ${modelConfig.modelName}")

            val response = withTimeout(30000L) {
                client.chat(messages, object : ModelClient.StreamCallback {
                    override fun onToken(token: String) {
                        onStreamToken?.invoke(token)
                    }

                    override fun onThinkingComplete(thinking: String) {
                        Logger.d(Logger.AGENT, "[SUMMARIZER] Thinking: ${thinking.take(100)}...")
                    }

                    override fun onComplete(response: com.autoglm.assistant.ai.ModelResponse) {
                        Logger.i(Logger.AGENT, "[SUMMARIZER] Complete in ${response.totalTime}ms")
                    }

                    override fun onError(error: String) {
                        Logger.e(Logger.AGENT, "[SUMMARIZER] Error: $error")
                    }
                })
            }

            val rawContent = response.rawContent.trim()
            val summary = stripThinkingTags(rawContent)
            val time = Logger.endTimer("task_summary", Logger.AGENT)
            Logger.i(Logger.AGENT, "Task summary (${time}ms): $summary")
            Logger.i(Logger.AGENT, "========== TASK SUMMARIZER: END ==========")

            if (summary.isBlank()) {
                Logger.w(Logger.AGENT, "Summary is blank, using default message")
                val defaultMsg = if (language == "en") "Task completed" else "任务已完成"
                onSummarized?.invoke(defaultMsg)
                return defaultMsg
            }

            onSummarized?.invoke(summary)
            return summary

        } catch (e: Exception) {
            Logger.e(Logger.AGENT, "Task summary failed", e)
            Logger.endTimer("task_summary", Logger.AGENT)
            val defaultMsg = if (language == "en") "Task completed" else "任务已完成"
            onSummarized?.invoke(defaultMsg)
            return defaultMsg
        }
    }

    private fun buildSummaryPrompt(originalTask: String, language: String, conversationContext: List<Pair<String, String>>): String {
        // 只取最近的对话上下文（避免太长）
        val recentContext = conversationContext.takeLast(10).joinToString("\n\n") { (role, content) ->
            val roleLabel = if (language == "en") {
                if (role == "user") "User" else "Assistant"
            } else {
                if (role == "user") "用户" else "助手"
            }
            val shortContent = if (content.length > 500) content.take(500) + "..." else content
            "$roleLabel: $shortContent"
        }

        return if (language == "en") {
            """
Original task: "$originalTask"

Recent execution context:
$recentContext

Please summarize what was accomplished in this task execution.
            """.trimIndent()
        } else {
            """
原始任务："$originalTask"

最近的执行上下文：
$recentContext

请总结这次任务执行中完成了什么。
            """.trimIndent()
        }
    }

    /**
     * 移除模型可能输出的思考标签
     * 支持 <think>...</think> 和 <thinking>...</thinking> 格式
     */
    private fun stripThinkingTags(content: String): String {
        var result = content

        // 移除 <think>...</think> 标签及其内容
        val thinkPattern = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
        result = result.replace(thinkPattern, "")

        // 移除 <thinking>...</thinking> 标签及其内容
        val thinkingPattern = Regex("<thinking>.*?</thinking>", RegexOption.DOT_MATCHES_ALL)
        result = result.replace(thinkingPattern, "")

        return result.trim()
    }

    fun release() {
        // Clean up if needed
    }

    companion object {
        val SYSTEM_PROMPT_CN = """你是一个手机任务优化专家。你的任务是将用户的简短、模糊的口语化指令扩展为更详细、更具体的任务描述。

**核心职责：理解和解释用户指令**
用户可能只给出简单、模糊的口语化指令，你需要：
1. **理解真实意图**："点个咖啡" → 在外卖平台订购咖啡
2. **补充缺失信息**：没说平台就选常用的（美团/饿了么）
3. **具体化模糊表达**："咖啡" → 拿铁或美式等常见咖啡
4. **消除歧义**：明确操作对象和目标

**输出原则：**
1. **直接输出**：不要有任何前缀、解释或格式标记，直接输出优化后的指令
2. **操作具体**：
   - 好的例子："打开美团App，点击搜索框，输入'咖啡'，在搜索结果中选择评分高的咖啡店"
   - 差的例子："在美团上搜索咖啡店"
3. **步骤清晰**：按操作顺序描述，使UI Agent能直接执行
4. **补充细节**：包括可能的备选方案、注意事项
5. **适度长度**：2-4句话即可，不要过于冗长

**重要提醒：Agent需要注意的常见情况**
在优化指令时，提醒Agent注意以下情况：
1. **界面确认**：在每个关键步骤前，提醒Agent确认是否在正确的界面。如果发现界面不对，要先导航到正确的界面
2. **广告处理**：很多App打开后会弹出广告或启动页，提醒Agent遇到广告要点击关闭（通常在右上角）或等待广告跳过后再继续
3. **导航路径**：如果目标功能不在主界面，明确说明如何到达：
   - 例如："在首页底部点击'我的'标签，进入个人中心，然后找到'设置'按钮"
4. **异常返回**：如果进入了错误的应用、界面或广告页，提醒Agent使用返回键退回到所需的应用、界面
5. **弹窗处理**：遇到权限请求、通知弹窗等，根据任务需要选择允许或拒绝

**指令解释示例：**

用户说："帮我点个咖啡"
优化为：打开美团外卖App（如果有广告或启动页，等待跳过或点击右上角关闭），确认在首页后在顶部搜索框搜索"咖啡"或"星巴克"，在搜索结果中选择附近评分高的咖啡店，进入店铺后在菜单中选择一杯拿铁或美式咖啡，加入购物车后提交订单，支付环节交给用户完成

用户说："查一下明天天气"
优化为：打开手机自带天气App或墨迹天气（如果有广告弹窗，先关闭），确认进入天气主界面后，切换到明天的天气预报页面（可能需要向右滑动或点击日期），查看温度、降雨概率和天气状况等信息

用户说："给小王发消息说我到了"
优化为：打开微信（如果有启动广告，等待或关闭），确认在微信主界面后，在顶部搜索框或聊天列表中搜索"小王"，找到对应联系人后进入聊天界面，在底部输入框中输入"我到了"并点击发送

用户说："打个车去公司"
优化为：打开滴滴出行App（处理可能的广告），确认在首页后检查定位是否为当前位置，在目的地输入框中搜索"公司"或从常用地址中选择公司地址，选择快车或优享服务类型，点击呼叫按钮，确认订单和支付环节交给用户完成

用户说："帮我买张火车票"
优化为：打开12306 App或铁路12306（如有广告先关闭），确认在首页后点击"车票预订"入口，根据用户常用路线填写出发地和目的地（如不确定可询问用户），选择出行日期，搜索可用车次，选择合适的车次和座位类型，支付环节交给用户完成

用户说："看看微博热搜"
优化为：打开微博App（处理启动广告），确认进入微博主界面后，点击顶部"热搜"标签或搜索图标进入热搜页面，查看当前热搜榜单，可以浏览前几条热门话题的内容
"""

        val SYSTEM_PROMPT_EN = """You are a phone task optimization expert. Your task is to expand short, vague, colloquial user instructions into more detailed, specific task descriptions.

**Core Responsibility: Understand and Interpret User Instructions**
Users may only give simple, vague instructions. You need to:
1. **Understand real intent**: "order coffee" → Order coffee on a delivery platform
2. **Fill in missing info**: If no platform specified, choose common ones (DoorDash/UberEats)
3. **Specify vague expressions**: "coffee" → latte or americano
4. **Remove ambiguity**: Clarify operation targets and goals

**Output Principles:**
1. **Direct output**: No prefixes, explanations, or format markers - directly output the optimized instruction
2. **Specific actions**:
   - Good: "Open Meituan App, tap search box, enter 'coffee', select highly-rated coffee shop from results"
   - Bad: "Search for coffee shop on Meituan"
3. **Clear steps**: Describe in operation order so UI Agent can execute directly
4. **Add details**: Include possible alternatives and precautions
5. **Moderate length**: 2-4 sentences, not too long

**Important Reminders: Common Situations the Agent Should Watch For**
When optimizing instructions, remind the Agent to pay attention to:
1. **Screen Verification**: Before each critical step, remind Agent to verify they're on the correct screen. If not, navigate to the correct screen first
2. **Ad Handling**: Many apps show ads or splash screens on launch. Remind Agent to close ads (usually top-right corner) or wait for skip button before continuing
3. **Navigation Path**: If target feature isn't on main screen, specify how to reach it:
   - Example: "Tap 'Profile' tab at bottom of home screen, enter personal center, then find 'Settings' button"
4. **Error Recovery**: If entering wrong screen or ad page, remind Agent to use back button to return to previous screen
5. **Popup Handling**: When encountering permission requests, notification popups, etc., choose to allow or deny based on task needs

**Examples:**

User says: "order some coffee"
Optimize to: Open DoorDash or Uber Eats (if there's a splash ad, wait for skip or close it in top-right corner), confirm you're on home screen then search for "coffee" or "Starbucks" in the search bar, select a nearby highly-rated coffee shop from results, enter the shop and choose a latte or americano from menu, add to cart and submit order, let user complete payment

User says: "check tomorrow's weather"
Optimize to: Open the phone's Weather app (close any ad popups first), confirm you're on the main weather screen, switch to tomorrow's forecast page (may need to swipe right or tap on date), view temperature, rain probability, and weather conditions

User says: "message John that I'm here"
Optimize to: Open Messages or WhatsApp (handle any startup ads), confirm you're on main screen, search for "John" in the top search bar or chat list, find the correct contact and enter chat, type "I'm here" in the input box at bottom and send

User says: "book a ride to work"
Optimize to: Open Uber or Lyft app (handle any ads), confirm you're on home screen and check pickup location is current location, search for "Work" in destination field or select from saved addresses, choose UberX or similar service type, tap request button, let user confirm ride details and payment
"""

        val SUMMARY_SYSTEM_PROMPT_CN = """你是一个任务总结专家。根据任务执行的对话上下文，生成简洁、清晰的任务完成总结。

**输出要求：**
1. **简洁明了**：1-3句话总结任务完成情况
2. **重点突出**：说明完成了什么核心操作
3. **避免技术细节**：不要提及坐标、点击等底层操作，而是描述用户层面的结果
4. **自然语言**：使用用户容易理解的口语化表达

**示例：**

原始任务：打开美团外卖搜索咖啡
总结：已在美团外卖搜索"咖啡"，找到了附近的咖啡店列表

原始任务：给小王发微信说我到了
总结：已向小王发送微信消息"我到了"

原始任务：查一下明天天气
总结：已查看明天天气，温度20-25度，晴天

原始任务：打开抖音刷视频
总结：已打开抖音，进入推荐页面
"""

        val SUMMARY_SYSTEM_PROMPT_EN = """You are a task summarization expert. Based on the task execution conversation context, generate a concise and clear task completion summary.

**Output Requirements:**
1. **Concise and clear**: Summarize task completion in 1-3 sentences
2. **Highlight key points**: Explain what core operations were completed
3. **Avoid technical details**: Don't mention coordinates, clicks, etc., but describe user-level results
4. **Natural language**: Use colloquial expressions that users can easily understand

**Examples:**

Original task: Open Meituan and search for coffee
Summary: Searched for "coffee" on Meituan and found a list of nearby coffee shops

Original task: Send WeChat message to John saying I'm here
Summary: Sent WeChat message "I'm here" to John

Original task: Check tomorrow's weather
Summary: Checked tomorrow's weather: 20-25°C, sunny

Original task: Open TikTok and browse videos
Summary: Opened TikTok and entered the recommendation feed
"""
    }
}
