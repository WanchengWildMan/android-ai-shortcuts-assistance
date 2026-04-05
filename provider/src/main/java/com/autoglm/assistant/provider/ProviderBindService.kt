package com.autoglm.assistant.provider

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * # AIDL 绑定服务
 * 
 * ## 业务目的
 * 提供可绑定的 Service，暴露 IAccessibilityProvider AIDL 接口供主应用调用
 * 
 * ## 实现逻辑
 * - 步骤1: onBind 返回 AIDL Binder
 * - 步骤2: Binder 内部调用 AccessibilityProviderService 的静态实例
 * - 步骤3: 如果无障碍服务未启用，返回错误状态
 * 
 * ## 注意事项
 * - HARD: action 必须与主应用的 PROVIDER_ACTION 一致
 * - 无障碍服务必须先启用，否则所有操作都会失败
 */
class ProviderBindService : Service() {
    
    companion object {
        private const val TAG = "ProviderBindService"
    }
    
    // 步骤1: 创建 AIDL Binder 实现
    private val binder = object : IAccessibilityProvider.Stub() {
        
        override fun getUiHierarchy(): String {
            Log.d(TAG, "🌲 getUiHierarchy")
            val service = AccessibilityProviderService.instance
            if (service == null) {
                Log.e(TAG, "❌ AccessibilityService 未启用")
                return ""
            }
            return service.getUiHierarchyInternal()
        }
        
        override fun performClick(x: Int, y: Int): Boolean {
            Log.d(TAG, "📍 performClick: ($x, $y)")
            val service = AccessibilityProviderService.instance
            if (service == null) {
                Log.e(TAG, "❌ AccessibilityService 未启用")
                return false
            }
            return service.performClickInternal(x.toFloat(), y.toFloat())
        }
        
        override fun performLongPress(x: Int, y: Int): Boolean {
            Log.d(TAG, "⏱️ performLongPress: ($x, $y)")
            val service = AccessibilityProviderService.instance
            if (service == null) {
                Log.e(TAG, "❌ AccessibilityService 未启用")
                return false
            }
            return service.performLongPressInternal(x.toFloat(), y.toFloat(), 500)
        }
        
        override fun performGlobalAction(action: Int): Boolean {
            Log.d(TAG, "🌐 performGlobalAction: $action")
            val service = AccessibilityProviderService.instance
            if (service == null) {
                Log.e(TAG, "❌ AccessibilityService 未启用")
                return false
            }
            return service.performGlobalAction(action)
        }
        
        override fun performSwipe(
            startX: Int, 
            startY: Int, 
            endX: Int, 
            endY: Int, 
            duration: Long
        ): Boolean {
            Log.d(TAG, "👆 performSwipe: ($startX,$startY) -> ($endX,$endY), duration=$duration")
            val service = AccessibilityProviderService.instance
            if (service == null) {
                Log.e(TAG, "❌ AccessibilityService 未启用")
                return false
            }
            return service.performSwipeInternal(
                startX.toFloat(), startY.toFloat(),
                endX.toFloat(), endY.toFloat(), 
                duration
            )
        }
        
        override fun findFocusedNodeId(): String? {
            Log.d(TAG, "🔍 findFocusedNodeId")
            val service = AccessibilityProviderService.instance
            if (service == null) {
                Log.e(TAG, "❌ AccessibilityService 未启用")
                return null
            }
            return service.findFocusedNodeIdInternal()
        }
        
        override fun setTextOnNode(nodeId: String, text: String): Boolean {
            Log.d(TAG, "⌨️ setTextOnNode: nodeId=$nodeId, text='$text'")
            val service = AccessibilityProviderService.instance
            if (service == null) {
                Log.e(TAG, "❌ AccessibilityService 未启用")
                return false
            }
            return service.setTextOnNodeInternal(nodeId, text)
        }
        
        override fun takeScreenshot(fd: android.os.ParcelFileDescriptor, format: String): Boolean {
            Log.d(TAG, "📸 takeScreenshot: format=$format")
            val service = AccessibilityProviderService.instance
            if (service == null) {
                Log.e(TAG, "❌ AccessibilityService 未启用")
                return false
            }
            // 需要 Android 11+ (API 30)
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
                Log.e(TAG, "❌ takeScreenshot 需要 Android 11+ (API 30+)")
                return false
            }
            return service.takeScreenshotInternal(fd, format)
        }
        
        override fun isAccessibilityServiceEnabled(): Boolean {
            val enabled = AccessibilityProviderService.instance != null
            Log.d(TAG, "✅ isAccessibilityServiceEnabled: $enabled")
            return enabled
        }
        
        override fun getCurrentActivityName(): String? {
            Log.d(TAG, "🏠 getCurrentActivityName")
            val service = AccessibilityProviderService.instance
            if (service == null) {
                Log.e(TAG, "❌ AccessibilityService 未启用")
                return null
            }
            return service.rootInActiveWindow?.packageName?.toString()
        }
    }
    
    // 步骤2: onBind 返回 AIDL Binder
    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "🔗 onBind: action=${intent?.action}")
        // HARD: 这个 action 必须与主应用的 PROVIDER_ACTION 一致
        return binder
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "✅ ProviderBindService 已创建")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "❌ ProviderBindService 已销毁")
    }
}
