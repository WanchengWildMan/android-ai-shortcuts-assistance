package com.autoglm.assistant.ai

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.util.concurrent.TimeUnit

class ModelClient(private val config: ModelConfig) {

    // 强制使用 HTTP/1.1 避免 HTTP/2 FLOW_CONTROL_ERROR
    private val client = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    interface StreamCallback {
        fun onToken(token: String)
        fun onThinkingComplete(thinking: String)
        fun onComplete(response: ModelResponse)
        fun onError(error: String)
    }

    suspend fun chat(
        messages: List<Message>,
        callback: StreamCallback? = null
    ): ModelResponse = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        var timeToFirstToken: Long? = null

        val requestBody = buildRequestBody(messages)

        val request = Request.Builder()
            .url(config.chatCompletionsUrl)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            val error = "API Error: ${response.code} - ${response.message}"
            callback?.onError(error)
            throw RuntimeException(error)
        }

        val contentBuilder = StringBuilder()
        val reader = BufferedReader(response.body?.charStream())

        reader.use { br ->
            var line: String?
            while (br.readLine().also { line = it } != null) {
                val data = line ?: continue

                if (!data.startsWith("data: ")) continue
                val jsonStr = data.substring(6).trim()

                if (jsonStr == "[DONE]") break
                if (jsonStr.isEmpty()) continue

                try {
                    val json = JsonParser.parseString(jsonStr).asJsonObject
                    val choices = json.getAsJsonArray("choices")
                    if (choices != null && choices.size() > 0) {
                        val delta = choices[0].asJsonObject.getAsJsonObject("delta")
                        val content = delta?.get("content")?.asString

                        if (content != null) {
                            if (timeToFirstToken == null) {
                                timeToFirstToken = System.currentTimeMillis() - startTime
                            }
                            contentBuilder.append(content)
                            callback?.onToken(content)
                        }
                    }
                } catch (e: Exception) {
                    // Skip malformed JSON
                }
            }
        }

        val totalTime = System.currentTimeMillis() - startTime
        val rawContent = contentBuilder.toString()
        val (thinking, action) = parseResponse(rawContent)

        callback?.onThinkingComplete(thinking)

        val modelResponse = ModelResponse(
            thinking = thinking,
            action = action,
            rawContent = rawContent,
            timeToFirstToken = timeToFirstToken,
            totalTime = totalTime
        )

        callback?.onComplete(modelResponse)
        modelResponse
    }

    suspend fun chatSync(messages: List<Message>): ModelResponse = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        val requestBody = buildNonStreamRequestBody(messages)

        val request = Request.Builder()
            .url(config.chatCompletionsUrl)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            throw RuntimeException("API Error: ${response.code} - ${response.message}")
        }

        val responseBody = response.body?.string() ?: throw RuntimeException("Empty response")
        val json = JsonParser.parseString(responseBody).asJsonObject
        val choices = json.getAsJsonArray("choices")
        val content = choices[0].asJsonObject
            .getAsJsonObject("message")
            .get("content").asString

        val totalTime = System.currentTimeMillis() - startTime
        val (thinking, action) = parseResponse(content)

        ModelResponse(
            thinking = thinking,
            action = action,
            rawContent = content,
            totalTime = totalTime
        )
    }

    private fun buildRequestBody(messages: List<Message>): String {
        val messagesArray = messages.map { it.toJsonMap() }

        val body = mapOf(
            "model" to config.modelName,
            "messages" to messagesArray,
            "max_tokens" to config.maxTokens,
            "temperature" to config.temperature,
            "top_p" to config.topP,
            "frequency_penalty" to config.frequencyPenalty,
            "stream" to true
        )

        return gson.toJson(body)
    }

    private fun buildNonStreamRequestBody(messages: List<Message>): String {
        val messagesArray = messages.map { it.toJsonMap() }

        val body = mapOf(
            "model" to config.modelName,
            "messages" to messagesArray,
            "max_tokens" to config.maxTokens,
            "temperature" to config.temperature,
            "top_p" to config.topP,
            "frequency_penalty" to config.frequencyPenalty,
            "stream" to false
        )

        return gson.toJson(body)
    }

    private fun parseResponse(content: String): Pair<String, String> {
        // Priority 1: finish(message=
        val finishIndex = content.indexOf("finish(message=")
        if (finishIndex != -1) {
            return Pair(
                content.substring(0, finishIndex).trim(),
                content.substring(finishIndex).trim()
            )
        }

        // Priority 2: do(action=
        val doIndex = content.indexOf("do(action=")
        if (doIndex != -1) {
            return Pair(
                content.substring(0, doIndex).trim(),
                content.substring(doIndex).trim()
            )
        }

        // Priority 3: <answer> tag (legacy support)
        val answerStartTag = "<answer>"
        val answerEndTag = "</answer>"
        val answerStart = content.indexOf(answerStartTag)
        val answerEnd = content.indexOf(answerEndTag)
        if (answerStart != -1 && answerEnd != -1) {
            val thinking = content.substring(0, answerStart).trim()
            val action = content.substring(answerStart + answerStartTag.length, answerEnd).trim()
            return Pair(thinking, action)
        }

        // Priority 4: Try to extract action from Chinese description (fallback for non-compliant models)
        val extractedAction = tryExtractActionFromDescription(content)
        if (extractedAction != null) {
            return Pair(content.trim(), extractedAction)
        }

        // Fallback: entire content as action
        return Pair("", content.trim())
    }

    /**
     * Try to extract action from Chinese description when model doesn't follow format
     * E.g., "需要启动美团应用" -> do(action="Launch", app="美团")
     */
    private fun tryExtractActionFromDescription(content: String): String? {
        // Launch action patterns
        val launchPatterns = listOf(
            Regex("启动(.+?)(?:应用|app|App|APP)"),
            Regex("打开(.+?)(?:应用|app|App|APP)"),
            Regex("Launch (.+?)(?:应用|app|App|APP)?")
        )
        for (pattern in launchPatterns) {
            val match = pattern.find(content)
            if (match != null) {
                val appName = match.groupValues[1].trim()
                if (appName.isNotEmpty() && appName.length < 20) {
                    android.util.Log.w("AutoGLM", "=== Extracted Launch action from description: app=$appName ===")
                    return "do(action=\"Launch\", app=\"$appName\")"
                }
            }
        }

        // Tap action patterns
        val tapPattern = Regex("点击\\s*\\[?(\\d+)\\s*,\\s*(\\d+)\\]?")
        val tapMatch = tapPattern.find(content)
        if (tapMatch != null) {
            val x = tapMatch.groupValues[1]
            val y = tapMatch.groupValues[2]
            android.util.Log.w("AutoGLM", "=== Extracted Tap action from description: x=$x, y=$y ===")
            return "do(action=\"Tap\", element=[$x, $y])"
        }

        // Back action
        if (content.contains("返回") && (content.contains("按") || content.contains("点击返回") || content.contains("执行返回"))) {
            android.util.Log.w("AutoGLM", "=== Extracted Back action from description ===")
            return "do(action=\"Back\")"
        }

        // Home action
        if (content.contains("返回主屏幕") || content.contains("回到桌面") || content.contains("按Home")) {
            android.util.Log.w("AutoGLM", "=== Extracted Home action from description ===")
            return "do(action=\"Home\")"
        }

        return null
    }
}
