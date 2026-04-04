package com.autoglm.assistant.ai

import com.autoglm.assistant.util.Logger
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 模型 API 辅助工具
 * 提供与 OpenAI 兼容 API 端点交互的通用方法（如获取模型列表）。
 */
object ModelApiHelper {

    private val client = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * 从 OpenAI 兼容的 /models 端点获取可用模型列表
     *
     * 流程：
     * 1. 构造 GET 请求到 {baseUrl}/models
     * 2. 解析返回的 JSON（{data: [{id: "model-name"}, ...]}）
     * 3. 提取所有模型 ID 并按字母排序返回
     *
     * @param baseUrl API 基础地址（如 https://api.deepseek.com/v1）
     * @param apiKey API 认证密钥
     * @return 模型 ID 列表，失败返回空列表
     */
    suspend fun fetchModels(baseUrl: String, apiKey: String): List<String> {
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            Logger.w(Logger.MODEL, "fetchModels: baseUrl or apiKey is blank")
            return emptyList()
        }

        return withContext(Dispatchers.IO) {
            try {
                val url = "${baseUrl.trimEnd('/')}/models"
                Logger.i(Logger.MODEL, "Fetching models from: $url")

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    Logger.e(Logger.MODEL, "fetchModels failed: HTTP ${response.code}, body: ${body.take(200)}")
                    return@withContext emptyList()
                }

                // 解析 OpenAI 格式: {"data": [{"id": "model-name", ...}, ...]}
                val json = JsonParser.parseString(body).asJsonObject
                val models = json.getAsJsonArray("data")
                    ?.map { it.asJsonObject.get("id").asString }
                    ?.sorted()
                    ?: emptyList()

                Logger.i(Logger.MODEL, "Fetched ${models.size} models")
                models
            } catch (e: Exception) {
                Logger.e(Logger.MODEL, "fetchModels error: ${e.message}")
                emptyList()
            }
        }
    }
}
