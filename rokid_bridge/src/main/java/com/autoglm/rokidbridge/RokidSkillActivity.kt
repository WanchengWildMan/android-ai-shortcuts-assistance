package com.autoglm.rokidbridge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

class RokidSkillActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val asr = RokidSkillCommand.parse(
            intent?.getStringExtra(EXTRA_COMMAND),
            intent?.getStringExtra(EXTRA_ASR)
        )
        if (asr == null) {
            Log.w(TAG, "忽略无效的 Rokid 本地技能指令")
        } else {
            Log.i(TAG, "收到 Rokid ASR 文本: $asr")
        }
        finish()
    }

    companion object {
        private const val TAG = "AutoGLM-RokidBridge"
        private const val EXTRA_COMMAND = "cmd"
        private const val EXTRA_ASR = "asr"
    }
}
