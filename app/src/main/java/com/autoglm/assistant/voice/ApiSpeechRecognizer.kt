package com.autoglm.assistant.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import com.autoglm.assistant.util.Logger

/**
 * 基于 AudioRecord + API 的语音识别器，支持多种 STT 后端
 *
 * 支持的 API 类型：
 * - OPENAI: OpenAI Whisper 兼容 API（multipart WAV 上传）
 * - ALI_NLS: 阿里云 NLS 一句话识别 RESTful API（PCM 流式上传）
 *
 * 流程：
 * 1. 用 AudioRecord 直接录音（绕过系统 SpeechRecognizer 的后台限制）
 * 2. 使用 VAD（静音检测）自动停止录音
 * 3. 根据 apiType 选择对应的 API 进行识别
 */
class ApiSpeechRecognizer(private val context: Context) {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        // VAD 参数
        private const val SILENCE_THRESHOLD = 800     // 静音振幅阈值
        private const val SILENCE_DURATION_MS = 1500L  // 持续静音多久后自动停止
        private const val MAX_RECORD_DURATION_MS = 15000L // 最大录音时长
    }

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    var onResult: ((String) -> Unit)? = null
    var onPartialResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onReadyForSpeech: (() -> Unit)? = null
    var onEndOfSpeech: (() -> Unit)? = null

    // API 类型：OPENAI = OpenAI Whisper 兼容, ALI_NLS = 阿里 NLS
    var apiType: String = "OPENAI"

    // OpenAI 兼容 API 配置
    var apiBaseUrl: String = ""
    var apiKey: String = ""
    var model: String = "whisper-1"

    // 阿里 NLS 配置
    var aliNlsAkId: String = ""
    var aliNlsAkSecret: String = ""
    var aliNlsAppKey: String = ""

    // 阿里 NLS Token 缓存
    private var aliToken: String = ""
    private var aliTokenExpireTime: Long = 0

    private var recordingJob: Job? = null
    private var audioRecord: AudioRecord? = null

    /**
     * 开始录音并识别
     */
    fun startListening(language: String = "zh") {
        if (_isListening.value) return

        // 步骤1: 检查权限
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            onError?.invoke("麦克风权限未授予")
            return
        }

        // 步骤2: 检查 API 配置
        if (apiType == "ALI_NLS") {
            if (aliNlsAkId.isBlank() || aliNlsAkSecret.isBlank() || aliNlsAppKey.isBlank()) {
                onError?.invoke("阿里 NLS 未配置（需要 AK ID、AK Secret 和 AppKey）")
                return
            }
        } else {
            // OPENAI 模式检查
            if (apiBaseUrl.isBlank() || apiKey.isBlank()) {
                onError?.invoke("语音识别 API 未配置（需要 OpenAI 兼容的 STT 端点）")
                return
            }
        }

        _isListening.value = true

        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                // 步骤3: 初始化 AudioRecord
                val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                    .coerceAtLeast(SAMPLE_RATE * 2) // 至少 1 秒缓冲

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    withContext(Dispatchers.Main) {
                        onError?.invoke("音频录制器初始化失败")
                    }
                    return@launch
                }

                audioRecord?.startRecording()
                Logger.i(Logger.STT, "开始录音")

                withContext(Dispatchers.Main) {
                    onReadyForSpeech?.invoke()
                    onPartialResult?.invoke("正在录音...")
                }

                // 步骤4: 录音 + VAD 静音检测
                val pcmData = recordWithVad(bufferSize)

                withContext(Dispatchers.Main) {
                    onEndOfSpeech?.invoke()
                    onPartialResult?.invoke("正在识别...")
                }

                Logger.i(Logger.STT, "录音完成，PCM 大小: ${pcmData.size} bytes")

                if (pcmData.size < SAMPLE_RATE) { // 少于 0.5 秒的音频
                    withContext(Dispatchers.Main) {
                        onResult?.invoke("")
                    }
                    return@launch
                }

                // 步骤5: 根据 API 类型调用识别
                val text = if (apiType == "ALI_NLS") {
                    callAliNlsApi(pcmData, language)
                } else {
                    // OpenAI 模式需要 WAV 格式
                    val wavBytes = pcmToWav(pcmData, SAMPLE_RATE, 1, 16)
                    Logger.i(Logger.STT, "WAV 大小: ${wavBytes.size} bytes")
                    callOpenAiSttApi(wavBytes, language)
                }
                Logger.i(Logger.STT, "识别结果: $text")

                withContext(Dispatchers.Main) {
                    onResult?.invoke(text)
                }

            } catch (e: CancellationException) {
                Logger.i(Logger.STT, "录音被取消")
            } catch (e: Exception) {
                Logger.e(Logger.STT, "语音识别失败", e)
                withContext(Dispatchers.Main) {
                    onError?.invoke("语音识别失败: ${e.message}")
                }
            } finally {
                releaseAudioRecord()
                _isListening.value = false
            }
        }
    }

    /**
     * 直接识别外部 16kHz/单声道/16bit PCM，不再读取手机麦克风。
     */
    fun recognizePcm(pcmData: ByteArray, language: String = "zh") {
        if (_isListening.value) return
        if (pcmData.size < SAMPLE_RATE) {
            onResult?.invoke("")
            return
        }
        if (apiType == "ALI_NLS") {
            if (aliNlsAkId.isBlank() || aliNlsAkSecret.isBlank() || aliNlsAppKey.isBlank()) {
                onError?.invoke("阿里 NLS 未配置（需要 AK ID、AK Secret 和 AppKey）")
                return
            }
        } else if (apiBaseUrl.isBlank() || apiKey.isBlank()) {
            onError?.invoke("语音识别 API 未配置（需要 OpenAI 兼容的 STT 端点）")
            return
        }

        _isListening.value = true
        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                withContext(Dispatchers.Main) { onPartialResult?.invoke("正在识别...") }
                val text = if (apiType == "ALI_NLS") {
                    callAliNlsApi(pcmData, language)
                } else {
                    callOpenAiSttApi(pcmToWav(pcmData, SAMPLE_RATE, 1, 16), language)
                }
                withContext(Dispatchers.Main) { onResult?.invoke(text) }
            } catch (e: CancellationException) {
                Logger.i(Logger.STT, "外部 PCM 识别被取消")
            } catch (e: Exception) {
                Logger.e(Logger.STT, "外部 PCM 识别失败", e)
                withContext(Dispatchers.Main) { onError?.invoke("语音识别失败: ${e.message}") }
            } finally {
                _isListening.value = false
            }
        }
    }

    /**
     * 录音 + 简易 VAD 静音检测
     * 当连续静音超过 SILENCE_DURATION_MS 或总时长超过 MAX_RECORD_DURATION_MS 时自动停止
     */
    private suspend fun recordWithVad(bufferSize: Int): ByteArray = coroutineScope {
        val output = ByteArrayOutputStream()
        val buffer = ShortArray(bufferSize / 2)
        var silenceStartMs = 0L
        val recordStartMs = System.currentTimeMillis()
        var hasSpeech = false

        while (isActive) {
            val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: -1
            if (readCount <= 0) break

            // 写入 PCM 数据
            val byteBuffer = ByteArray(readCount * 2)
            for (i in 0 until readCount) {
                byteBuffer[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
                byteBuffer[i * 2 + 1] = (buffer[i].toInt() shr 8 and 0xFF).toByte()
            }
            output.write(byteBuffer)

            // 计算当前帧的 RMS 振幅
            val rms = calculateRms(buffer, readCount)

            val now = System.currentTimeMillis()
            val elapsed = now - recordStartMs

            if (rms > SILENCE_THRESHOLD) {
                // 检测到语音
                hasSpeech = true
                silenceStartMs = 0L
            } else if (hasSpeech) {
                // 已有语音后进入静音
                if (silenceStartMs == 0L) {
                    silenceStartMs = now
                } else if (now - silenceStartMs > SILENCE_DURATION_MS) {
                    Logger.i(Logger.STT, "VAD: 静音 ${SILENCE_DURATION_MS}ms，自动停止")
                    break
                }
            }

            // 超时保护
            if (elapsed > MAX_RECORD_DURATION_MS) {
                Logger.i(Logger.STT, "VAD: 达到最大录音时长 ${MAX_RECORD_DURATION_MS}ms")
                break
            }
        }

        output.toByteArray()
    }

    /**
     * 计算 RMS 振幅
     */
    private fun calculateRms(buffer: ShortArray, count: Int): Double {
        var sum = 0.0
        for (i in 0 until count) {
            sum += buffer[i].toDouble() * buffer[i].toDouble()
        }
        return kotlin.math.sqrt(sum / count)
    }

    /**
     * PCM 数据转 WAV 格式
     */
    private fun pcmToWav(
        pcmData: ByteArray,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val totalSize = 36 + dataSize

        val output = ByteArrayOutputStream()
        val dos = DataOutputStream(output)

        // RIFF header
        dos.writeBytes("RIFF")
        dos.writeInt(Integer.reverseBytes(totalSize))
        dos.writeBytes("WAVE")

        // fmt sub-chunk
        dos.writeBytes("fmt ")
        dos.writeInt(Integer.reverseBytes(16)) // sub-chunk size
        dos.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt()) // PCM
        dos.writeShort(java.lang.Short.reverseBytes(channels.toShort()).toInt())
        dos.writeInt(Integer.reverseBytes(sampleRate))
        dos.writeInt(Integer.reverseBytes(byteRate))
        dos.writeShort(java.lang.Short.reverseBytes(blockAlign.toShort()).toInt())
        dos.writeShort(java.lang.Short.reverseBytes(bitsPerSample.toShort()).toInt())

        // data sub-chunk
        dos.writeBytes("data")
        dos.writeInt(Integer.reverseBytes(dataSize))
        dos.write(pcmData)

        dos.flush()
        return output.toByteArray()
    }

    /**
     * 调用 OpenAI 兼容的 /audio/transcriptions API
     */
    private fun callOpenAiSttApi(wavBytes: ByteArray, language: String): String {
        val url = "${apiBaseUrl.trimEnd('/')}/audio/transcriptions"
        Logger.i(Logger.STT, "调用 STT API: $url, model=$model, language=$language")

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("language", language)
            .addFormDataPart(
                "file", "audio.wav",
                wavBytes.toRequestBody("audio/wav".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        val client = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            val body = response.body?.string() ?: ""
            throw IOException("STT API 返回错误 ${response.code}: $body")
        }

        val responseBody = response.body?.string() ?: ""

        // 解析 JSON 响应 { "text": "识别结果" }
        return try {
            val json = JsonParser.parseString(responseBody).asJsonObject
            json.get("text")?.asString ?: ""
        } catch (e: Exception) {
            // 如果不是 JSON，可能是纯文本
            responseBody.trim()
        }
    }

    // ─── 阿里 NLS 一句话识别 RESTful API ───

    /**
     * 调用阿里 NLS 一句话识别 RESTful API
     * POST https://nls-gateway-cn-shanghai.aliyuncs.com/stream/v1/asr
     */
    private fun callAliNlsApi(pcmData: ByteArray, language: String): String {
        // 步骤1: 获取/刷新 Token
        val token = getOrRefreshAliToken()
        if (token.isBlank()) {
            throw IOException("获取阿里云 NLS Token 失败")
        }

        // 步骤2: 构造请求 URL
        val url = "https://nls-gateway-cn-shanghai.aliyuncs.com/stream/v1/asr" +
            "?appkey=${URLEncoder.encode(aliNlsAppKey.trim(), "UTF-8")}" +
            "&format=pcm" +
            "&sample_rate=$SAMPLE_RATE" +
            "&enable_punctuation_prediction=true"
        Logger.i(Logger.STT, "调用阿里 NLS API: appkey=${aliNlsAppKey}, pcm=${pcmData.size} bytes")

        // 步骤3: 发送 PCM 数据
        val request = Request.Builder()
            .url(url)
            .addHeader("X-NLS-Token", token)
            .addHeader("Content-Type", "application/octet-stream")
            .post(pcmData.toRequestBody("application/octet-stream".toMediaType()))
            .build()

        val client = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            throw IOException("阿里 NLS API 返回错误 ${response.code}: $responseBody")
        }

        // 步骤4: 解析响应 {"result":"识别文本","status":20000000,"message":"SUCCESS"}
        return try {
            val json = JsonParser.parseString(responseBody).asJsonObject
            val status = json.get("status")?.asInt ?: -1
            if (status != 20000000) {
                val msg = json.get("message")?.asString ?: "未知错误"
                throw IOException("阿里 NLS 识别失败: status=$status, message=$msg")
            }
            json.get("result")?.asString ?: ""
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            Logger.w(Logger.STT, "阿里 NLS 响应解析异常: $responseBody", e)
            responseBody.trim()
        }
    }

    /**
     * 获取或刷新阿里云 NLS Token
     * 通过阿里云 POP API 签名调用 CreateToken
     */
    @Synchronized
    private fun getOrRefreshAliToken(): String {
        val now = System.currentTimeMillis() / 1000
        // 提前 5 分钟刷新，避免临界过期
        if (aliToken.isNotBlank() && aliTokenExpireTime > now + 300) {
            return aliToken
        }

        try {
            // 步骤1: 构造阿里云 POP API 公共参数
            val params = sortedMapOf(
                "Action" to "CreateToken",
                "Version" to "2019-02-28",
                "Format" to "JSON",
                "AccessKeyId" to aliNlsAkId.trim(),
                "SignatureMethod" to "HMAC-SHA1",
                "SignatureVersion" to "1.0",
                "SignatureNonce" to UUID.randomUUID().toString(),
                "Timestamp" to run {
                    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                    sdf.timeZone = TimeZone.getTimeZone("UTC")
                    sdf.format(Date())
                }
            )

            // 步骤2: 构造规范化查询字符串
            val canonicalQueryString = params.entries.joinToString("&") { (k, v) ->
                "${percentEncode(k)}=${percentEncode(v)}"
            }

            // 步骤3: 构造待签名字符串
            val stringToSign = "POST&${percentEncode("/")}&${percentEncode(canonicalQueryString)}"

            // 步骤4: HMAC-SHA1 签名
            val signKey = "${aliNlsAkSecret.trim()}&"
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(signKey.toByteArray(Charsets.UTF_8), "HmacSHA1"))
            val signature = Base64.encodeToString(
                mac.doFinal(stringToSign.toByteArray(Charsets.UTF_8)),
                Base64.NO_WRAP
            )

            // 步骤5: 发送请求
            val allParams = params.toMutableMap()
            allParams["Signature"] = signature

            val formBody = FormBody.Builder().apply {
                allParams.forEach { (k, v) -> add(k, v) }
            }.build()

            val request = Request.Builder()
                .url("https://nls-meta.cn-shanghai.aliyuncs.com/")
                .post(formBody)
                .build()

            val client = OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Logger.e(Logger.STT, "获取阿里云 Token 失败: ${response.code}, body=$body")
                return ""
            }

            // 解析 {"Token":{"Id":"xxx","ExpireTime":1234567890}}
            val json = JsonParser.parseString(body).asJsonObject
            val tokenObj = json.getAsJsonObject("Token")
            val tokenId = tokenObj?.get("Id")?.asString ?: ""
            val expireTime = tokenObj?.get("ExpireTime")?.asLong ?: 0

            if (tokenId.isNotBlank() && expireTime > 0) {
                aliToken = tokenId
                aliTokenExpireTime = expireTime
                Logger.i(Logger.STT, "阿里云 Token 获取成功，过期时间: ${Date(expireTime * 1000)}")
                return aliToken
            }

            Logger.w(Logger.STT, "阿里云 Token 响应不符合预期: $body")
            return ""
        } catch (e: Exception) {
            Logger.e(Logger.STT, "获取阿里云 Token 异常", e)
            return ""
        }
    }

    /**
     * 阿里云 POP API 签名所需的 percent encode
     * 与标准 URLEncoder 的区别：空格编码为 %20 而非 +，* 编码为 %2A，~ 不编码
     */
    private fun percentEncode(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")
    }

    /**
     * 停止录音
     */
    fun stopListening() {
        recordingJob?.cancel()
        recordingJob = null
        releaseAudioRecord()
        _isListening.value = false
    }

    fun cancel() {
        stopListening()
    }

    fun release() {
        stopListening()
    }

    private fun releaseAudioRecord() {
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }
}
