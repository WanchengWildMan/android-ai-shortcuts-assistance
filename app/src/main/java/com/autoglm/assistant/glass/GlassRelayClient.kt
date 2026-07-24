package com.autoglm.assistant.glass

import com.autoglm.assistant.util.Logger
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 眼镜识别文本经飞书 webhook 中转的客户端。
 *
 * 业务目的：vivo（眼镜桥接端）把 CXR-L 眼镜音频经本地 STT 得到的文本，
 * 通过飞书自定义机器人 webhook 发到指定群；电脑侧用另一个飞书应用的长连接
 * 订阅同一群，收到消息事件后转发给 Xiaomi（PhoneAgent 执行端）。
 * vivo 与电脑都只对飞书发起出站连接，都不需要公网 IP 或开放端口，
 * 不经过局域网直连，也不搭建隧道。
 *
 * webhook 不需要 token 鉴权，比应用 API 调用更简单稳定；
 * 也不受"应用自己发的消息不会回推给同一应用"规则的限制，因为 webhook 是独立发送方。
 *
 * 调用位置：WakeWordService.sendGlassResultToRelay()
 */
class GlassRelayClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /** @param webhookUrl 飞书自定义机器人 webhook 地址，例如 https://open.feishu.cn/open-apis/bot/v2/hook/xxx */
    fun sendText(webhookUrl: String, text: String, onResult: (success: Boolean, info: String) -> Unit) {
        val body = JSONObject().apply {
            put("msg_type", "text")
            put("content", JSONObject().apply { put("text", text) })
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder().url(webhookUrl).post(body).build()
        Logger.i(Logger.GLASS, "飞书 webhook 中转发送 text=${text.take(40)}")

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Logger.e(Logger.GLASS, "飞书 webhook 中转发送失败: ${e.message}")
                onResult(false, e.message ?: "网络错误")
            }

            override fun onResponse(call: Call, response: Response) {
                val bodyStr = response.body?.string().orEmpty()
                val ok = response.isSuccessful && runCatching {
                    JSONObject(bodyStr).optString("StatusCode", "0") == "0"
                }.getOrDefault(false)
                response.close()
                Logger.i(Logger.GLASS, "飞书 webhook 中转发送${if (ok) "成功" else "被拒绝"}: $bodyStr")
                onResult(ok, bodyStr)
            }
        })
    }
}
