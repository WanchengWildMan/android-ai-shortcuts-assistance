package com.autoglm.assistant.voice

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import android.speech.SpeechRecognizer as AndroidSpeechRecognizer

/**
 * 透明 Activity，用于绕过 Android 11+ 后台麦克风限制
 * 流程：onWindowFocusChanged(true) → 检查权限 → 通知 WakeWordService 启动 STT
 * 使用 onWindowFocusChanged 而非 onResume，因为只有窗口真正获得焦点后
 * 系统语音识别服务才会认为应用处于"前台"
 */
class AssistantActivity : Activity() {
    companion object {
        private const val TAG = "AutoGLM_START"
        private const val REQUEST_RECORD_AUDIO = 100
    }

    private var hasStartedStt = false
    private var permissionRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "AssistantActivity onCreate")

        // 诊断日志：系统是否有可用的语音识别服务
        val sttAvailable = AndroidSpeechRecognizer.isRecognitionAvailable(this)
        Log.i(TAG, "系统语音识别可用: $sttAvailable")
        if (!sttAvailable) {
            Toast.makeText(this, "此设备不支持系统语音识别，请安装 Google 语音服务", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.i(TAG, "onWindowFocusChanged hasFocus=$hasFocus, hasStartedStt=$hasStartedStt")

        if (!hasFocus || hasStartedStt) return

        // 步骤1: 检查运行时权限
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (!permissionRequested) {
                permissionRequested = true
                Log.w(TAG, "RECORD_AUDIO 权限未授予，弹出系统权限弹窗")
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            }
            return
        }

        // 步骤2: 窗口已获得焦点 + 权限已授予 → 启动 STT
        doStartStt()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "RECORD_AUDIO 权限已授予（从弹窗）")
                doStartStt()
            } else {
                Log.e(TAG, "RECORD_AUDIO 权限被拒绝")
                if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                    Toast.makeText(this, "请在设置中手动开启麦克风权限", Toast.LENGTH_LONG).show()
                    try {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                        })
                    } catch (e: Exception) {
                        Log.e(TAG, "无法打开应用设置", e)
                    }
                } else {
                    Toast.makeText(this, "需要麦克风权限才能语音控制", Toast.LENGTH_LONG).show()
                }
                finish()
            }
        }
    }

    /**
     * 确保只执行一次：通知 WakeWordService 启动 STT
     */
    private fun doStartStt() {
        if (hasStartedStt) return
        hasStartedStt = true
        Log.i(TAG, "窗口已聚焦且权限已授予，通知 WakeWordService 启动 STT")
        com.autoglm.assistant.service.WakeWordService.instance?.onSttActivityCreated(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "AssistantActivity onDestroy")
        com.autoglm.assistant.service.WakeWordService.instance?.onSttActivityDestroyed()
    }
}
