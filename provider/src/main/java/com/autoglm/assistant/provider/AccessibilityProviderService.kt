package com.autoglm.assistant.provider

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi

// Android 11+ 截图API
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback

/**
 * # 无障碍服务提供者
 * 
 * ## 业务目的
 * 作为独立应用提供无障碍功能，通过 AIDL 接口供主应用调用
 * 
 * ## 实现逻辑
 * - 步骤1: 系统启用无障碍服务后，创建单例实例
 * - 步骤2: ProviderBindService 通过单例调用无障碍功能  
 * - 步骤3: 使用 Gesture API 执行点击、滑动等操作
 * - 步骤4: 遍历 UI 层级获取窗口信息
 * 
 * ## 注意事项
 * - HARD: 必须在系统设置中手动启用此无障碍服务
 * - HARD: 需要 Android 7.0+ (API 24) 支持 dispatchGesture
 */
@RequiresApi(Build.VERSION_CODES.N)
class AccessibilityProviderService : AccessibilityService() {
    
    companion object {
        private const val TAG = "AccessibilityProvider"
        
        // HARD: 单例实例，供 ProviderBindService 调用
        @Volatile
        var instance: AccessibilityProviderService? = null
            private set
    }
    
    // ========== 生命周期回调 ==========
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "✅ 无障碍服务已连接")
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 步骤: 接收无障碍事件（暂不处理）
    }
    
    override fun onInterrupt() {
        Log.w(TAG, "⚠️ 无障碍服务被中断")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "❌ 服务已销毁")
    }
    
    // ========== 公开方法 (供 ProviderBindService 调用) ==========
    
    /**
     * 步骤2: 执行点击操作
     * - 业务目的: 在指定坐标执行点击手势
     * - 实现逻辑: 使用 GestureDescription 构建点击路径
     */
    fun performClickInternal(x: Float, y: Float): Boolean {
        Log.d(TAG, "📍 执行点击: ($x, $y)")
        
        // 步骤2.1: 构建点击路径
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 10))  // 10ms 点击
            .build()
        
        // 步骤2.2: 分发手势
        var result = false
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                result = true
                Log.d(TAG, "✅ 点击完成")
            }
            
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.e(TAG, "❌ 点击被取消")
            }
        }
        
        dispatchGesture(gesture, callback, null)
        Thread.sleep(50)  // 等待手势完成
        return result
    }
    
    /**
     * 步骤3: 执行长按操作
     */
    fun performLongPressInternal(x: Float, y: Float, duration: Long): Boolean {
        Log.d(TAG, "⏱️ 执行长按: ($x, $y), duration=$duration")
        
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        
        var result = false
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                result = true
                Log.d(TAG, "✅ 长按完成")
            }
        }, null)
        
        Thread.sleep(duration + 50)
        return result
    }
    
    /**
     * 步骤4: 执行滑动操作
     */
    fun performSwipeInternal(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        duration: Long
    ): Boolean {
        Log.d(TAG, "👆 执行滑动: ($startX,$startY) -> ($endX,$endY)")
        
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        
        var result = false
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                result = true
                Log.d(TAG, "✅ 滑动完成")
            }
        }, null)
        
        Thread.sleep(duration + 50)
        return result
    }
    
    /**
     * 步骤5: 获取 UI 层级
     * - 业务目的: 遍历所有窗口节点，生成 XML 格式的层级结构
     * - 实现逻辑: 递归遍历 AccessibilityNodeInfo 树
     */
    fun getUiHierarchyInternal(): String {
        Log.d(TAG, "🌲 获取 UI 层级")
        
        val root = rootInActiveWindow
        if (root == null) {
            Log.w(TAG, "⚠️ 无法获取根节点")
            return "<hierarchy></hierarchy>"
        }
        
        val sb = StringBuilder("<hierarchy>")
        traverseNode(root, sb, 0)
        sb.append("</hierarchy>")
        
        return sb.toString()
    }
    
    /**
     * 步骤5.1: 递归遍历节点
     */
    private fun traverseNode(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        // 步骤: 添加节点信息
        val indent = "  ".repeat(depth)
        sb.append("\n$indent<node")
        
        // 步骤: 添加属性
        node.className?.let { sb.append(" class=\"$it\"") }
        node.text?.let { sb.append(" text=\"${escapeXml(it.toString())}\"") }
        node.contentDescription?.let { sb.append(" desc=\"${escapeXml(it.toString())}\"") }
        node.viewIdResourceName?.let { sb.append(" id=\"$it\"") }
        sb.append(" clickable=\"${node.isClickable}\"")
        sb.append(" enabled=\"${node.isEnabled}\"")
        
        // 步骤: 添加边界
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        sb.append(" bounds=\"${bounds.toShortString()}\"")
        
        // 步骤: 递归子节点
        val childCount = node.childCount
        if (childCount > 0) {
            sb.append(">")
            for (i in 0 until childCount) {
                node.getChild(i)?.let { child ->
                    traverseNode(child, sb, depth + 1)
                    child.recycle()
                }
            }
            sb.append("\n$indent</node>")
        } else {
            sb.append(" />")
        }
    }
    
    /**
     * 步骤6: 根据文本查找节点
     */
    fun findNodeByTextInternal(text: String, exact: Boolean): String {
        Log.d(TAG, "🔍 查找文本: '$text', exact=$exact")
        
        val root = rootInActiveWindow ?: return ""
        val nodes = if (exact) {
            root.findAccessibilityNodeInfosByText(text)
        } else {
            findNodesByTextPartial(root, text)
        }
        
        val sb = StringBuilder("<nodes>")
        nodes.forEach { node ->
            sb.append("\n<node")
            node.className?.let { sb.append(" class=\"$it\"") }
            node.text?.let { sb.append(" text=\"${escapeXml(it.toString())}\"") }
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            sb.append(" bounds=\"${bounds.toShortString()}\"")
            sb.append(" />")
            node.recycle()
        }
        sb.append("\n</nodes>")
        
        return sb.toString()
    }
    
    /**
     * 步骤7: 根据 ID 查找节点
     */
    fun findNodeByIdInternal(resourceId: String): String {
        Log.d(TAG, "🆔 查找 ID: $resourceId")
        
        val root = rootInActiveWindow ?: return ""
        val nodes = root.findAccessibilityNodeInfosByViewId(resourceId)
        
        val sb = StringBuilder("<nodes>")
        nodes.forEach { node ->
            sb.append("\n<node")
            node.className?.let { sb.append(" class=\"$it\"") }
            node.viewIdResourceName?.let { sb.append(" id=\"$it\"") }
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            sb.append(" bounds=\"${bounds.toShortString()}\"")
            sb.append(" />")
            node.recycle()
        }
        sb.append("\n</nodes>")
        
        return sb.toString()
    }
    
    /**
     * 步骤8: 点击文本节点
     */
    fun clickNodeByTextInternal(text: String, exact: Boolean): Boolean {
        Log.d(TAG, "🖱️ 点击文本: '$text'")
        
        val root = rootInActiveWindow ?: return false
        val nodes = if (exact) {
            root.findAccessibilityNodeInfosByText(text)
        } else {
            findNodesByTextPartial(root, text)
        }
        
        return if (nodes.isNotEmpty()) {
            val node = nodes[0]
            val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            node.recycle()
            Log.d(TAG, if (result) "✅ 点击成功" else "❌ 点击失败")
            result
        } else {
            Log.w(TAG, "⚠️ 未找到节点")
            false
        }
    }
    
    /**
     * 步骤9: 点击 ID 节点
     */
    fun clickNodeByIdInternal(resourceId: String): Boolean {
        Log.d(TAG, "🖱️ 点击 ID: $resourceId")
        
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByViewId(resourceId)
        
        return if (nodes.isNotEmpty()) {
            val node = nodes[0]
            val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            node.recycle()
            Log.d(TAG, if (result) "✅ 点击成功" else "❌ 点击失败")
            result
        } else {
            Log.w(TAG, "⚠️ 未找到节点")
            false
        }
    }
    
    /**
     * 步骤10: 输入文本
     */
    fun inputTextInternal(text: String): Boolean {
        Log.d(TAG, "⌨️ 输入文本: '$text'")
        
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        
        if (focused == null) {
            Log.w(TAG, "⚠️ 未找到聚焦节点")
            return false
        }
        
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val result = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        focused.recycle()
        
        Log.d(TAG, if (result) "✅ 输入成功" else "❌ 输入失败")
        return result
    }
    
    // ========== 辅助方法 ==========
    
    /**
     * 部分匹配文本查找
     */
    private fun findNodesByTextPartial(root: AccessibilityNodeInfo, text: String): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        findNodesByTextRecursive(root, text.lowercase(), results)
        return results
    }
    
    private fun findNodesByTextRecursive(
        node: AccessibilityNodeInfo,
        text: String,
        results: MutableList<AccessibilityNodeInfo>
    ) {
        node.text?.toString()?.lowercase()?.let {
            if (it.contains(text)) {
                results.add(AccessibilityNodeInfo.obtain(node))
            }
        }
        
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                findNodesByTextRecursive(child, text, results)
                child.recycle()
            }
        }
    }
    
    /**
     * XML 转义
     */
    private fun escapeXml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
    
    /**
     * 步骤: 截取屏幕截图
     * 
     * 业务目的: 通过无障碍服务API截取当前屏幕
     * 操作实现: 使用Android API 30+ 的takeScreenshot方法保存到文件描述符
     * 
     * @param fd 保存文件的描述符
     * @param format 图片格式 (PNG, JPEG)
     * @return 截图是否成功
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun takeScreenshotInternal(fd: android.os.ParcelFileDescriptor, format: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.e(TAG, "❌ takeScreenshot 需要 Android 11+ (API 30+)")
            return false
        }
        
        try {
            Log.d(TAG, "📸 开始截图: format=$format")
            
            // 步骤1: 使用 CountDownLatch 等待异步截图完成
            val countDownLatch = java.util.concurrent.CountDownLatch(1)
            var success = false
            
            // 步骤2: 调用系统截图API（Android 11+ AccessibilityService.takeScreenshot）
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                application.mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            Log.d(TAG, "📸 截图回调成功")
                            
                            // 步骤3: 从 ScreenshotResult 获取 HardwareBuffer，转换为 Bitmap
                            val hardwareBuffer = screenshot.hardwareBuffer
                            val colorSpace = screenshot.colorSpace
                            
                            val bitmap = android.graphics.Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                            hardwareBuffer.close()
                            
                            if (bitmap == null) {
                                Log.e(TAG, "❌ wrapHardwareBuffer 返回 null")
                                return
                            }
                            
                            // 步骤4: 将 Bitmap 保存到指定文件描述符
                            val compressFormat = when (format.uppercase()) {
                                "JPEG", "JPG" -> android.graphics.Bitmap.CompressFormat.JPEG
                                else -> android.graphics.Bitmap.CompressFormat.PNG
                            }
                            
                            android.os.ParcelFileDescriptor.AutoCloseOutputStream(fd).use { out ->
                                bitmap.compress(compressFormat, 100, out)
                            }
                            bitmap.recycle()
                            
                            success = true
                            Log.d(TAG, "✅ 截图保存成功")
                            
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ 处理截图失败", e)
                        } finally {
                            countDownLatch.countDown()
                        }
                    }
                    
                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "❌ 截图失败: errorCode=$errorCode")
                        countDownLatch.countDown()
                    }
                }
            )
            
            // 步骤5: 等待截图完成（最多5秒）
            countDownLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            
            return success
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 截图过程异常", e)
            return false
        }
    }
}
