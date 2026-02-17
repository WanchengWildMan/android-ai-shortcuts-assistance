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
     * 步骤10: 输入文本（向当前焦点输入框）
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
    
    /**
     * 步骤11: 查找焦点节点的bounds字符串（Operit方式）
     * 
     * 业务目的: 找到当前输入焦点所在的节点，返回其边界坐标字符串
     * 操作实现: 遍历UI层级查找焦点，返回"[left,top][right,bottom]"格式的边界字符串
     * 
     * 注意: 使用bounds而非nodeId，避免跨进程缓存失效问题
     */
    fun findFocusedNodeIdInternal(): String? {
        Log.d(TAG, "🔍 [Provider] 开始查找焦点节点")
        
        val root = rootInActiveWindow ?: run {
            Log.e(TAG, "❌ [Provider] 无法获取根节点")
            return null
        }
        
        // 步骤1: 尝试多种方式查找焦点（某些应用焦点类型不同）
        var targetNode: AccessibilityNodeInfo? = null
        
        // 1.1 输入焦点 (标准方式)
        targetNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (targetNode != null) {
            Log.d(TAG, "🔍 [Provider] FOCUS_INPUT 候选: class=${targetNode.className}, id=${targetNode.viewIdResourceName}, visible=${targetNode.isVisibleToUser}")
            
            // 获取bounds信息（即使为空也继续）
            val rect = android.graphics.Rect()
            targetNode.getBoundsInScreen(rect)
            
            // 对于微信等应用，输入框在初始状态bounds可能为空
            // 我们仍然接受此节点，返回特殊标记让后续处理
            if (rect.isEmpty) {
                Log.w(TAG, "⚠️ [Provider] FOCUS_INPUT节点bounds为空，但仍使用: visible=${targetNode.isVisibleToUser}, bounds=$rect")
                // 不设置为null，继续使用
            } else {
                Log.d(TAG, "✅ [Provider] FOCUS_INPUT有效: bounds=[$rect]")
            }
        }
        
        // 1.2 无障碍焦点 (某些应用使用此焦点)
        if (targetNode == null) {
            val accessibilityNode = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            if (accessibilityNode != null && (accessibilityNode.isEditable || accessibilityNode.className == "android.widget.EditText")) {
                Log.d(TAG, "🔍 [Provider] FOCUS_ACCESSIBILITY 候选: class=${accessibilityNode.className}")
                
                val rect = android.graphics.Rect()
                accessibilityNode.getBoundsInScreen(rect)
                if (accessibilityNode.isVisibleToUser && !rect.isEmpty) {
                    targetNode = accessibilityNode
                    Log.d(TAG, "✅ [Provider] FOCUS_ACCESSIBILITY有效: bounds=[$rect]")
                } else {
                    Log.w(TAG, "⚠️ [Provider] FOCUS_ACCESSIBILITY节点无效: visible=${accessibilityNode.isVisibleToUser}, bounds=$rect")
                }
            }
        }
        
        // 1.3 递归查找 isFocused 的可编辑节点（带bounds验证）
        if (targetNode == null) {
            targetNode = findFocusedEditableNode(root)
            if (targetNode != null) {
                val rect = android.graphics.Rect()
                targetNode.getBoundsInScreen(rect)
                Log.d(TAG, "🔍 [Provider] 递归找到候选: class=${targetNode.className}, bounds=[$rect]")
                
                if (!targetNode.isVisibleToUser || rect.isEmpty) {
                    Log.w(TAG, "⚠️ [Provider] 递归节点无效: visible=${targetNode.isVisibleToUser}, bounds=$rect")
                    targetNode = null
                } else {
                    Log.d(TAG, "✅ [Provider] 递归节点有效")
                }
            }
        }
        
        if (targetNode == null) {
            Log.e(TAG, "❌ [Provider] 所有方式均未找到有效焦点节点")
            Log.e(TAG, "❌ [Provider] 请确保：1) 已点击输入框  2) 光标正在闪烁  3) 输入框可见  4) Provider无障碍服务已启用")
            return null
        }
        
        // 步骤2: 最终验证节点
        val finalRect = android.graphics.Rect()
        targetNode.getBoundsInScreen(finalRect)
        Log.d(TAG, "🔍 [Provider] 最终节点: class=${targetNode.className}, editable=${targetNode.isEditable}, enabled=${targetNode.isEnabled}, visible=${targetNode.isVisibleToUser}, focused=${targetNode.isFocused}, bounds=$finalRect")
        
        // 步骤3: 返回bounds字符串
        // 如果bounds为空，返回特殊标记"[FOCUS_INPUT]"让setTextOnNode直接查找FOCUS_INPUT节点
        val boundsString = if (finalRect.isEmpty) {
            Log.d(TAG, "✅ [Provider] 找到焦点节点但bounds为空，返回[FOCUS_INPUT]标记")
            "[FOCUS_INPUT]"
        } else {
            "[${finalRect.left},${finalRect.top}][${finalRect.right},${finalRect.bottom}]"
        }
        
        Log.d(TAG, "✅ [Provider] 成功找到焦点节点: bounds=$boundsString")
        targetNode.recycle()
        return boundsString
    }
    
    /**
     * 递归查找处于焦点状态的可编辑节点（带bounds验证）
     */
    private fun findFocusedEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 检查当前节点是否同时满足：焦点 + 可编辑 + 可见 + 有效bounds
        if (node.isFocused && (node.isEditable || node.className == "android.widget.EditText")) {
            if (node.isVisibleToUser && node.isEnabled) {
                // 额外检查bounds是否有效（不为空）
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                if (!rect.isEmpty) {
                    return AccessibilityNodeInfo.obtain(node)
                } else {
                    Log.w(TAG, "⚠️ [Provider] 递归找到焦点节点但bounds为空: class=${node.className}")
                }
            }
        }
        
        // 递归查找子节点
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val result = findFocusedEditableNode(child)
                child.recycle()
                if (result != null) {
                    return result
                }
            }
        }
        
        return null
    }
    
    /**
     * 步骤12: 在指定bounds的节点上设置文本（Operit方式）
     * 
     * 业务目的: 向指定边界坐标的节点输入文本
     * 操作实现: 通过bounds实时查找节点，执行ACTION_SET_TEXT操作
     * 
     * @param boundsString 格式: "[left,top][right,bottom]"
     * @param text 要输入的文本
     */
    fun setTextOnNodeInternal(boundsString: String, text: String): Boolean {
        Log.d(TAG, "⌨️ 设置节点文本: bounds=$boundsString, text='$text'")
        
        // 步骤1: 获取根节点
        val root = rootInActiveWindow ?: run {
            Log.e(TAG, "❌ 无法获取根节点")
            return false
        }
        
        // 步骤2: 查找目标节点
        val targetNode = if (boundsString == "[FOCUS_INPUT]") {
            // 特殊情况: bounds为空时直接查找FOCUS_INPUT节点
            Log.d(TAG, "🔍 [TEXT_INPUT] 使用FOCUS_INPUT特殊标记，等待100ms后重新查找")
            Thread.sleep(100)  // 等待键盘弹出和输入框初始化
            
            val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (node == null) {
                Log.e(TAG, "❌ 重新查找FOCUS_INPUT失败")
            } else {
                Log.d(TAG, "✅ 重新找到FOCUS_INPUT节点: class=${node.className}")
            }
            node
        } else {
            // 常规情况: 通过bounds查找
            val rect = parseBounds(boundsString)
            if (rect.isEmpty) {
                Log.e(TAG, "❌ bounds解析失败: $boundsString")
                return false
            }
            
            val node = findNodeByBounds(root, rect)
            if (node == null) {
                Log.e(TAG, "❌ 未找到匹配bounds的节点: $boundsString")
            }
            node
        }
        
        if (targetNode == null) {
            Log.e(TAG, "❌ 未能获取目标节点")
            return false
        }
        
        // 步骤3: 检查节点是否可编辑
        if (!targetNode.isEditable && targetNode.className != "android.widget.EditText") {
            Log.w(TAG, "⚠️ 节点不可编辑: class=${targetNode.className}, isEditable=${targetNode.isEditable}")
        }
        
        var success = false
        
        try {
            // 策略1: 先确保节点获得焦点
            Log.d(TAG, "[TEXT_INPUT] 策略1: ACTION_FOCUS")
            targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            Thread.sleep(50)  // 等待焦点稳定
            
            // 策略2: 先清空现有文本（如果有）
            val currentText = targetNode.text?.toString() ?: ""
            if (currentText.isNotEmpty()) {
                Log.d(TAG, "[TEXT_INPUT] 策略2: 清空现有文本 (当前='$currentText')")
                // 方法1: 全选 + 删除
                val selectArgs = android.os.Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, currentText.length)
                }
                targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectArgs)
                Thread.sleep(30)
                
                // 方法2: 设置空文本清空
                val clearArgs = android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                }
                targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
                Thread.sleep(50)
            }
            
            // 策略3: 设置新文本
            Log.d(TAG, "[TEXT_INPUT] 策略3: ACTION_SET_TEXT")
            val setArgs = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val setResult = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)
            Log.d(TAG, "[TEXT_INPUT] ACTION_SET_TEXT result=$setResult")
            
            if (setResult) {
                Thread.sleep(50)
                
                // 策略4: 移动光标到文本末尾（某些应用需要）
                Log.d(TAG, "[TEXT_INPUT] 策略4: 设置光标位置到末尾")
                val cursorArgs = android.os.Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
                }
                targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cursorArgs)
                
                success = true
            } else {
                // 策略5: 尝试粘贴方式（复制到剪贴板 + ACTION_PASTE）
                Log.d(TAG, "[TEXT_INPUT] 策略5: 尝试粘贴方式")
                try {
                    val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("autoglm_input", text)
                    clipboard.setPrimaryClip(clip)
                    Thread.sleep(30)
                    
                    val pasteResult = targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    Log.d(TAG, "[TEXT_INPUT] ACTION_PASTE result=$pasteResult")
                    success = pasteResult
                } catch (e: Exception) {
                    Log.e(TAG, "[TEXT_INPUT] 粘贴方式失败: ${e.message}")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 设置文本异常: ${e.message}", e)
        } finally {
            targetNode.recycle()
        }
        
        Log.d(TAG, if (success) "✅ 设置文本成功" else "❌ 设置文本失败")
        return success
    }
    
    /**
     * 解析bounds字符串 "[left,top][right,bottom]" -> Rect
     */
    private fun parseBounds(boundsString: String): android.graphics.Rect {
        val rect = android.graphics.Rect()
        try {
            // 去除中括号，按逗号分割："[1,2][3,4]" -> "1,2,3,4,"
            val parts = boundsString.replace("[", "").replace("]", ",").split(",")
            if (parts.size >= 4) {
                rect.left = parts[0].toInt()
                rect.top = parts[1].toInt()
                rect.right = parts[2].toInt()
                rect.bottom = parts[3].toInt()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 解析bounds失败: $boundsString", e)
        }
        return rect
    }
    
    /**
     * 通过bounds递归查找匹配的节点
     */
    private fun findNodeByBounds(node: AccessibilityNodeInfo, targetRect: android.graphics.Rect): AccessibilityNodeInfo? {
        val nodeRect = android.graphics.Rect()
        node.getBoundsInScreen(nodeRect)
        
        // 检查当前节点是否匹配
        if (nodeRect == targetRect) {
            Log.d(TAG, "✅ 找到匹配节点: class=${node.className}")
            return AccessibilityNodeInfo.obtain(node)
        }
        
        // 递归查找子节点
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val result = findNodeByBounds(child, targetRect)
                child.recycle()
                if (result != null) {
                    return result
                }
            }
        }
        
        return null
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
