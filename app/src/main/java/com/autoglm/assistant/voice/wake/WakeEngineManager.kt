package com.autoglm.assistant.voice.wake

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.autoglm.assistant.util.Logger

/**
 * 唤醒引擎管理器 — 负责引擎的创建、切换、生命周期管理
 * 同一时刻只有一个引擎处于活跃状态
 */
class WakeEngineManager(private val context: Context) {

    companion object {
    }

    private val mutex = Mutex()
    private var currentEngine: WakeEngine? = null

    private val _activeEngineType = MutableStateFlow<WakeEngine.EngineType?>(null)
    val activeEngineType: StateFlow<WakeEngine.EngineType?> = _activeEngineType

    private val _engineState = MutableStateFlow(WakeEngine.EngineState.UNINITIALIZED)
    val engineState: StateFlow<WakeEngine.EngineState> = _engineState

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    /**
     * 切换到指定引擎
     * 步骤: 1)停止当前引擎 2)释放当前引擎 3)创建新引擎 4)初始化
     */
    suspend fun switchEngine(type: WakeEngine.EngineType, config: WakeEngineConfig): Boolean {
        return mutex.withLock {
            Logger.i(Logger.WAKE, "切换引擎: ${_activeEngineType.value} -> $type")

            // 步骤1: 停止当前引擎
            currentEngine?.let {
                try {
                    it.stopListening()
                } catch (e: Exception) {
                    Logger.e(Logger.WAKE, "停止当前引擎失败", e)
                }
            }

            // 步骤2: 释放当前引擎
            currentEngine?.let {
                try {
                    it.release()
                } catch (e: Exception) {
                    Logger.e(Logger.WAKE, "释放当前引擎失败", e)
                }
            }
            currentEngine = null
            _activeEngineType.value = null

            // 步骤3: 创建新引擎
            val newEngine = createEngine(type)
            if (newEngine == null) {
                val errorMsg = "不支持的引擎类型: $type"
                _lastError.value = errorMsg
                _engineState.value = WakeEngine.EngineState.ERROR
                Logger.e(Logger.WAKE, errorMsg)
                return@withLock false
            }

            // 步骤4: 初始化新引擎
            val success = newEngine.initialize(config)
            if (success) {
                currentEngine = newEngine
                _activeEngineType.value = type
                // 代理状态到管理器
                _engineState.value = newEngine.engineState.value
                _lastError.value = newEngine.lastError.value
                Logger.i(Logger.WAKE, "引擎切换成功: $type")
            } else {
                newEngine.release()
                _engineState.value = WakeEngine.EngineState.ERROR
                _lastError.value = newEngine.lastError.value
                Logger.e(Logger.WAKE, "引擎初始化失败: $type")
            }

            success
        }
    }

    /** 开始监听（使用当前活跃引擎） */
    suspend fun startListening(onWakeDetected: (confidence: Float) -> Unit) {
        val engine = currentEngine
        if (engine == null) {
            val errorMsg = "无活跃引擎，无法开始监听"
            _lastError.value = errorMsg
            Logger.e(Logger.WAKE, errorMsg)
            return
        }

        engine.startListening(onWakeDetected)
        // 更新管理器状态
        _engineState.value = engine.engineState.value
        _lastError.value = engine.lastError.value
    }

    /** 停止监听 */
    suspend fun stopListening() {
        val engine = currentEngine ?: return
        engine.stopListening()
        // 更新管理器状态
        _engineState.value = engine.engineState.value
        _lastError.value = engine.lastError.value
    }

    /** 重新初始化当前引擎（配置变更时调用） */
    suspend fun reinitialize(config: WakeEngineConfig): Boolean {
        return mutex.withLock {
            val engine = currentEngine
            if (engine == null) {
                val errorMsg = "无活跃引擎，无法重新初始化"
                _lastError.value = errorMsg
                Logger.e(Logger.WAKE, errorMsg)
                return@withLock false
            }

            Logger.i(Logger.WAKE, "重新初始化引擎: ${engine.engineType}")

            // 停止监听
            try {
                engine.stopListening()
            } catch (e: Exception) {
                Logger.e(Logger.WAKE, "停止监听失败", e)
            }

            // 重新初始化
            val success = engine.initialize(config)
            _engineState.value = engine.engineState.value
            _lastError.value = engine.lastError.value

            if (success) {
                Logger.i(Logger.WAKE, "重新初始化成功")
            } else {
                Logger.e(Logger.WAKE, "重新初始化失败")
            }

            success
        }
    }

    /** 释放所有资源 */
    fun release() {
        Logger.i(Logger.WAKE, "释放所有资源")
        currentEngine?.release()
        currentEngine = null
        _activeEngineType.value = null
        _engineState.value = WakeEngine.EngineState.UNINITIALIZED
        _lastError.value = null
    }

    /** 创建指定类型的引擎实例 */
    private fun createEngine(type: WakeEngine.EngineType): WakeEngine? {
        return when (type) {
            WakeEngine.EngineType.PORCUPINE -> {
                Logger.i(Logger.WAKE, "正在创建 Porcupine 唤醒引擎")
                PorcupineWakeEngine(context)
            }
            WakeEngine.EngineType.PERSONAL_TEMPLATE -> {
                Logger.i(Logger.WAKE, "正在创建个人模板唤醒引擎")
                PersonalTemplateWakeEngine(context)
            }
            WakeEngine.EngineType.STT_SYSTEM -> {
                Logger.i(Logger.WAKE, "正在创建系统 STT 唤醒引擎")
                SystemSttWakeEngine(context)
            }
            WakeEngine.EngineType.STT_SHERPA -> {
                // TODO: Phase 3 实现
                Logger.e(Logger.WAKE, "无法启动：Sherpa STT 引擎尚未实现，请在设置中切换引擎")
                null
            }
        }
    }

    /** 获取当前引擎是否正在监听 */
    val isListening: Boolean
        get() = currentEngine?.isListening ?: false

    /** 获取当前引擎是否已初始化 */
    val isInitialized: Boolean
        get() = currentEngine?.isInitialized ?: false
}
