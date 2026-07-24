package com.autoglm.glass.activities

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.autoglm.glass.receiver.KeyReceiver
import com.autoglm.glass.receiver.KeyType
import com.autoglm.glass.ui.GlassStatusScreen
import com.autoglm.glass.ui.StatusViewModel

/**
 * 眼镜端入口 Activity。
 *
 * 业务目的：被手机端 appStart 拉起后，作为 CustomApp 会话目标，承担三件事——
 *   1) subscribe 手机下发的 Agent 状态并渲染（单绿色 UI）
 *   2) 把眼镜按键广播 sendMessage 回传手机
 *   3) 读取系统 agent 拉起时可能携带的 Intent extra（验证眼镜系统路由意图文本的传递路径）
 *
 * 按键映射（可按真机手感调整）：
 *   LONG_PRESS / AI_START → 停止当前任务
 *   DOUBLE_CLICK         → 触发介入（空文本，手机侧弹介入入口）
 *   其他                 → 仅回传事件，由手机侧决定
 */
class MainActivity : ComponentActivity() {

    private val viewModel: StatusViewModel by viewModels()

    private val keyReceiver = KeyReceiver { keyType ->
        Log.i(TAG, "按键事件: ${keyType.name}")
        // 按键本地映射 + 回传原始事件（手机侧也做映射，双保险）
        when (keyType) {
            KeyType.LONG_PRESS, KeyType.AI_START -> viewModel.sendStop()
            KeyType.DOUBLE_CLICK -> viewModel.sendIntervene("")
            else -> viewModel.sendKey(keyType)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 步骤1: 读取并记录系统拉起时的 Intent extra
        // 验证点：眼镜系统 agent 路由意图文本是否经 Intent extra 传递
        logLaunchIntent(intent)

        // 步骤2: 若 Intent extra 携带意图文本，直接回传手机（覆盖路径 A）
        extractIntentText(intent)?.let { text ->
            Log.i(TAG, "从 Intent extra 提取到意图文本: ${text.take(40)}")
            viewModel.sendIntent(text)
        }

        // 步骤3: 渲染状态 UI
        setContent { GlassStatusScreen(viewModel) }

        // 步骤4: 动态注册镜腿键/触控板系统广播
        val filter = android.content.IntentFilter().apply {
            KeyType.allActions.forEach { addAction(it) }
        }
        ContextCompat.registerReceiver(
            this, keyReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask 模式下，眼镜系统再次拉起时会走 onNewIntent
        Log.i(TAG, "onNewIntent extras=${intent.extras}")
        logLaunchIntent(intent)
        extractIntentText(intent)?.let { text ->
            Log.i(TAG, "onNewIntent 提取到意图文本: ${text.take(40)}")
            viewModel.sendIntent(text)
        }
    }

    /** 打印系统拉起 Intent 的全部 extra（真机验证用） */
    private fun logLaunchIntent(intent: Intent?) {
        val extras = intent?.extras
        Log.i(TAG, "onCreate intent=$intent action=${intent?.action} extras=${extras}")
        extras?.keySet()?.forEach { key ->
            Log.i(TAG, "  extra[$key]=${extras.get(key)}")
        }
    }

    /**
     * 尝试从 Intent extra 提取意图文本。
     * 业务目的：覆盖眼镜系统 agent 路由意图经 Intent extra 传递的情况（路径 A）。
     * 常见可能的 extra key：text / query / intent / content / asr_result，逐一尝试。
     * 返回非空字符串则视为命中。
     */
    private fun extractIntentText(intent: Intent?): String? {
        val extras = intent?.extras ?: return null
        val candidateKeys = listOf("text", "query", "intent", "content", "asr_result", "asr", "message", "command")
        for (key in candidateKeys) {
            val value = extras.get(key)
            if (value is String && value.isNotBlank()) {
                return value
            }
        }
        // 兜底：扫描所有 String 型 extra，取第一个非空（排除标准 Intent extra）
        for (key in extras.keySet()) {
            if (key in STANDARD_INTENT_KEYS) continue
            val value = extras.get(key)
            if (value is String && value.isNotBlank() && value.length > 1) {
                return value
            }
        }
        return null
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(keyReceiver) }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AutoGLM"
        // 标准 Intent extra key，扫描时跳过，避免误判
        private val STANDARD_INTENT_KEYS = setOf(
            "android.intent.extra.TEXT", "android.intent.extra.SUBJECT",
            "android.intent.extra.STREAM", "android.intent.extra.EMAIL",
            "android.intent.extra.TITLE", "android.intent.extra.PROCESS_TEXT"
        )
    }
}
