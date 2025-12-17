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

**指令解释示例：**

用户说："帮我点个咖啡"
优化为：在美团外卖上搜索"咖啡"或"星巴克"，选择附近评分高的咖啡店，在菜单中选择一杯拿铁或美式咖啡，加入购物车后提交订单，支付环节交给用户完成

用户说："查一下明天天气"
优化为：打开手机自带天气App或墨迹天气，切换到明天的天气预报页面，查看温度、降雨概率和天气状况等信息

用户说："给小王发消息说我到了"
优化为：打开微信，在聊天列表或通讯录中搜索并找到"小王"，进入聊天界面，在输入框中输入"我到了"并发送

用户说："打个车去公司"
优化为：打开滴滴出行App，确认定位是当前位置，在目的地输入框搜索并选择"公司"或常用地址中的公司，选择快车或优享服务，点击呼叫按钮，确认打车和支付环节交给用户完成

用户说："帮我买张火车票"
优化为：打开12306 App或铁路12306，进入车票预订页面，根据用户常用路线或询问用户出发地和目的地，选择出行日期，搜索可用车次，选择合适的车次和座位类型，支付环节交给用户完成

用户说："看看微博热搜"
优化为：打开微博App，点击"热搜"或"搜索"入口，查看当前热搜榜单，可以浏览前几条热门话题的内容
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

**Examples:**

User says: "order some coffee"
Optimize to: Open a food delivery app like DoorDash or Uber Eats, search for "coffee" or "Starbucks", select a nearby highly-rated coffee shop, choose a latte or americano from the menu, add to cart and submit order, let user complete payment

User says: "check tomorrow's weather"
Optimize to: Open the phone's built-in Weather app or a weather app, switch to tomorrow's forecast page, view temperature, rain probability, and weather conditions

User says: "message John that I'm here"
Optimize to: Open Messages or WhatsApp, find "John" in chat list or contacts, enter the chat, type "I'm here" in the input box and send

User says: "book a ride to work"
Optimize to: Open Uber or Lyft app, confirm pickup location is current location, search for and select "Work" or the saved work address as destination, choose UberX or similar service, tap request button, let user confirm ride and payment
"""
    }
}
