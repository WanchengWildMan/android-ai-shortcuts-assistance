package com.autoglm.assistant.example

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.autoglm.assistant.accessibility.UIHierarchyManager
import com.autoglm.assistant.accessibility.AccessibilityProviderInstaller

/**
 * 无障碍服务使用示例
 * 
 * 本文件展示如何在项目中集成和使用独立的无障碍服务提供者
 */
class AccessibilityExample {
    
    /**
     * 示例1：检查并安装提供者应用
     * 
     * 业务场景：应用启动时检查无障碍服务提供者是否已安装
     */
    fun checkAndInstallProvider(context: Context) {
        // 步骤1：检查是否已安装
        val isInstalled = UIHierarchyManager.isProviderAppInstalled(context)
        
        if (!isInstalled) {
            // 步骤2：提示用户并启动安装
            // 可以在 UI 中显示对话框说明为什么需要安装
            UIHierarchyManager.launchProviderInstall(context)
        }
    }
    
    /**
     * 示例2：检查版本并提示更新
     * 
     * 业务场景：定期检查提供者应用版本，提示用户更新
     */
    fun checkForUpdate(context: Context): UpdateInfo {
        // 步骤1：获取版本信息
        val installedVersion = AccessibilityProviderInstaller.getInstalledVersion(context)
        val bundledVersion = AccessibilityProviderInstaller.getBundledVersion(context)
        
        // 步骤2：检查是否需要更新
        val needsUpdate = AccessibilityProviderInstaller.isUpdateNeeded(context)
        
        return UpdateInfo(
            isInstalled = installedVersion != null,
            installedVersion = installedVersion,
            bundledVersion = bundledVersion,
            needsUpdate = needsUpdate
        )
    }
    
    /**
     * 示例3：引导用户启用无障碍服务
     * 
     * 业务场景：提供者应用已安装，但无障碍服务未启用
     */
    suspend fun checkAndEnableAccessibility(context: Context): Boolean {
        // 步骤1：绑定服务
        if (!UIHierarchyManager.bindToService(context)) {
            return false
        }
        
        // 步骤2：检查服务是否已启用
        val isEnabled = UIHierarchyManager.isAccessibilityServiceEnabled(context)
        
        if (!isEnabled) {
            // 步骤3：打开无障碍设置页面
            withContext(Dispatchers.Main) {
                try {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        
        return isEnabled
    }
    
    /**
     * 示例4：执行点击操作
     * 
     * 业务场景：在协调器中执行屏幕点击
     */
    suspend fun performClickOperation(context: Context, x: Int, y: Int): OperationResult {
        // 步骤1：确保服务已绑定
        if (!UIHierarchyManager.bindToService(context)) {
            return OperationResult(
                success = false,
                message = "服务绑定失败，请检查提供者应用是否已安装"
            )
        }
        
        // 步骤2：检查无障碍服务是否已启用
        val isEnabled = UIHierarchyManager.isAccessibilityServiceEnabled(context)
        if (!isEnabled) {
            return OperationResult(
                success = false,
                message = "无障碍服务未启用，请在系统设置中启用"
            )
        }
        
        // 步骤3：执行点击
        val success = UIHierarchyManager.performClick(context, x, y)
        
        return OperationResult(
            success = success,
            message = if (success) "点击成功" else "点击失败"
        )
    }
    
    /**
     * 示例5：执行滑动操作
     * 
     * 业务场景：在协调器中执行屏幕滑动
     */
    suspend fun performSwipeOperation(
        context: Context,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        duration: Long = 300
    ): OperationResult {
        // 步骤1：确保服务已绑定
        if (!UIHierarchyManager.bindToService(context)) {
            return OperationResult(
                success = false,
                message = "服务绑定失败"
            )
        }
        
        // 步骤2：执行滑动
        val success = UIHierarchyManager.performSwipe(
            context,
            startX,
            startY,
            endX,
            endY,
            duration
        )
        
        return OperationResult(
            success = success,
            message = if (success) "滑动成功" else "滑动失败"
        )
    }
    
    /**
     * 示例6：获取 UI 层次结构
     * 
     * 业务场景：分析当前屏幕内容，供 AI 决策
     */
    suspend fun getScreenHierarchy(context: Context): String {
        // 步骤1：确保服务已绑定
        if (!UIHierarchyManager.bindToService(context)) {
            return ""
        }
        
        // 步骤2：获取 UI 层次结构
        return UIHierarchyManager.getUIHierarchy(context)
    }
    
    /**
     * 示例7：完整的操作流程
     * 
     * 业务场景：从检查到执行的完整流程
     */
    suspend fun completeWorkflow(context: Context): WorkflowResult {
        // 步骤1：检查提供者应用
        if (!UIHierarchyManager.isProviderAppInstalled(context)) {
            return WorkflowResult(
                stage = "检查安装",
                success = false,
                message = "提供者应用未安装，请先安装"
            )
        }
        
        // 步骤2：检查版本
        if (AccessibilityProviderInstaller.isUpdateNeeded(context)) {
            return WorkflowResult(
                stage = "检查版本",
                success = false,
                message = "提供者应用需要更新"
            )
        }
        
        // 步骤3：绑定服务
        if (!UIHierarchyManager.bindToService(context)) {
            return WorkflowResult(
                stage = "绑定服务",
                success = false,
                message = "服务绑定失败"
            )
        }
        
        // 步骤4：检查服务状态
        if (!UIHierarchyManager.isAccessibilityServiceEnabled(context)) {
            return WorkflowResult(
                stage = "检查服务",
                success = false,
                message = "无障碍服务未启用"
            )
        }
        
        // 步骤5：执行操作（示例：点击屏幕中心）
        val success = UIHierarchyManager.performClick(context, 540, 960)
        
        return WorkflowResult(
            stage = "执行操作",
            success = success,
            message = if (success) "操作完成" else "操作失败"
        )
    }
    
    /**
     * 示例8：在 Activity 中使用（带生命周期管理）
     */
    fun exampleInActivity(context: Context, lifecycleScope: kotlinx.coroutines.CoroutineScope) {
        lifecycleScope.launch(Dispatchers.IO) {
            // 步骤1：绑定服务
            val bound = UIHierarchyManager.bindToService(context)
            
            if (bound) {
                // 步骤2：执行操作
                val result = performClickOperation(context, 500, 800)
                
                // 步骤3：在主线程更新 UI
                withContext(Dispatchers.Main) {
                    // 更新 UI 显示结果
                    // 例如：Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                }
            }
            
            // 步骤4：在不需要时解绑（可选，通常在 onDestroy 中）
            // UIHierarchyManager.unbindFromService(context)
        }
    }
    
    /**
     * 示例9：清除缓存
     * 
     * 业务场景：在安装或更新提供者应用后，清除版本缓存
     */
    fun clearVersionCache() {
        AccessibilityProviderInstaller.clearCache()
    }
    
    // 数据类定义
    data class UpdateInfo(
        val isInstalled: Boolean,
        val installedVersion: String?,
        val bundledVersion: String,
        val needsUpdate: Boolean
    )
    
    data class OperationResult(
        val success: Boolean,
        val message: String
    )
    
    data class WorkflowResult(
        val stage: String,
        val success: Boolean,
        val message: String
    )
}
