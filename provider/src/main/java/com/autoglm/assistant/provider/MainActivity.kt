package com.autoglm.assistant.provider

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity

/**
 * Provider应用的启动Activity
 * 
 * 业务目的: 提供桌面图标入口，引导用户启用无障碍服务
 * 
 * 实现逻辑:
 * - 步骤1: 启动时直接跳转到无障碍设置页面
 * - 步骤2: 引导用户启用"AutoGLM Provider"服务
 */
class MainActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 步骤1: 直接打开无障碍设置页面
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        
        // 步骤2: 提示用户启用服务后关闭自己
        finish()
    }
}
