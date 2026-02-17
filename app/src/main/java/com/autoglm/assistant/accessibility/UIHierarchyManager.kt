package com.autoglm.assistant.accessibility

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.autoglm.assistant.R
import com.autoglm.assistant.provider.IAccessibilityProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

/**
 * UI 层次结构管理器
 * 
 * 业务目的：负责与独立的无障碍服务提供者应用进行通信，实现无 root 的屏幕操作
 * 
 * 核心功能：
 * 1. 服务绑定：绑定到外部无障碍服务提供者应用
 * 2. 操作实现：点击、长按、滑动等无障碍操作
 * 3. 状态管理：维护服务连接状态
 * 4. 安装管理：从 assets 提取并安装提供者 APK
 */
object UIHierarchyManager {
    private const val TAG = "UIHierarchyManager"
    private const val BIND_SERVICE_TIMEOUT_MS = 3000L // 3秒超时
    
    // 步骤1：无障碍服务提供者应用配置
    // HARD: 这些包名和 Action 必须与独立的无障碍服务提供者应用保持一致
    private const val PROVIDER_PACKAGE_NAME = "com.autoglm.assistant.provider"
    private const val PROVIDER_APK_NAME = "accessibility_provider.apk"
    private const val PROVIDER_ACTION = "com.autoglm.assistant.provider.IAccessibilityProvider"
    
    // 步骤2：服务连接状态管理
    @Volatile
    private var accessibilityProvider: IAccessibilityProvider? = null
    
    private val _isBound = MutableStateFlow(false)
    val isBound = _isBound.asStateFlow()
    
    private val bindingMutex = Mutex()
    
    @Volatile
    private var connectionContinuation: ((Boolean) -> Unit)? = null
    
    // 步骤3：定义服务连接回调
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "无障碍服务提供者已连接")
            accessibilityProvider = IAccessibilityProvider.Stub.asInterface(service)
            _isBound.value = true
            connectionContinuation?.invoke(true)
            connectionContinuation = null
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "无障碍服务提供者已断开")
            accessibilityProvider = null
            _isBound.value = false
            connectionContinuation?.invoke(false)
            connectionContinuation = null
        }
    }
    
    /**
     * 步骤4：从 assets 提取无障碍服务提供者 APK
     * 
     * @param context 应用上下文
     * @return 提取成功返回 APK 文件，失败返回 null
     */
    private fun extractProviderApkFromAssets(context: Context): File? {
        return try {
            val apkFile = File(context.cacheDir, PROVIDER_APK_NAME)
            context.assets.open(PROVIDER_APK_NAME).use { inputStream ->
                FileOutputStream(apkFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            Log.d(TAG, "无障碍服务 APK 已提取到: ${apkFile.absolutePath}")
            apkFile
        } catch (e: Exception) {
            Log.e(TAG, "从 assets 提取无障碍服务 APK 失败", e)
            null
        }
    }
    
    /**
     * 步骤5：启动安装流程
     * 
     * 业务目的：引导用户安装独立的无障碍服务提供者应用
     */
    fun launchProviderInstall(context: Context) {
        GlobalScope.launch(Dispatchers.IO) {
            val apkFile = extractProviderApkFromAssets(context)
            if (apkFile == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "APK 提取失败",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }
            
            val apkUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
            } else {
                Uri.fromFile(apkFile)
            }
            
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            
            withContext(Dispatchers.Main) {
                try {
                    context.startActivity(installIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "启动安装界面失败", e)
                    Toast.makeText(
                        context,
                        "操作失败: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
    
    /**
     * 步骤6：检查提供者应用是否已安装
     */
    fun isProviderAppInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(PROVIDER_PACKAGE_NAME, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
    
    /**
     * 步骤7：绑定到外部无障碍服务
     * 
     * 业务目的：建立与独立无障碍服务应用的 IPC 连接
     * 操作实现：使用显式 Intent 绑定服务，带超时控制
     * 
     * @param context 应用上下文
     * @return 绑定是否成功
     */
    suspend fun bindToService(context: Context): Boolean {
        return bindingMutex.withLock {
            Log.d(TAG, "开始绑定服务，线程：${Thread.currentThread().name}")
            
            // 步骤7.1：检查是否已绑定或应用未安装
            if ((_isBound.value && accessibilityProvider != null) || !isProviderAppInstalled(context)) {
                Log.d(TAG, "服务已绑定或应用未安装，跳过")
                return@withLock _isBound.value
            }
            
            // 步骤7.2：解析服务
            val implicitIntent = Intent(PROVIDER_ACTION).setPackage(PROVIDER_PACKAGE_NAME)
            val resolveInfo: ResolveInfo? = context.packageManager.resolveService(implicitIntent, PackageManager.MATCH_ALL)
            
            if (resolveInfo == null) {
                Log.e(TAG, "无法解析服务: $PROVIDER_ACTION")
                return@withLock false
            }
            
            Log.d(TAG, "服务解析成功: ${resolveInfo.serviceInfo.packageName}/${resolveInfo.serviceInfo.name}")
            
            // 步骤7.3：创建显式 Intent
            val explicitIntent = Intent(PROVIDER_ACTION).apply {
                component = ComponentName(resolveInfo.serviceInfo.packageName, resolveInfo.serviceInfo.name)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            
            // 步骤7.4：带超时的绑定操作
            val result = withTimeoutOrNull(BIND_SERVICE_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    connectionContinuation = { success ->
                        if (continuation.isActive) {
                            continuation.resume(success)
                        }
                    }
                    
                    try {
                        val bound = context.applicationContext.bindService(
                            explicitIntent,
                            serviceConnection,
                            Context.BIND_AUTO_CREATE
                        )
                        Log.d(TAG, "bindService 结果: $bound")
                        
                        if (!bound) {
                            Log.e(TAG, "bindService 返回 false")
                            if (continuation.isActive) {
                                continuation.resume(false)
                            }
                            connectionContinuation = null
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "绑定服务异常", e)
                        if (continuation.isActive) {
                            continuation.resume(false)
                        }
                        connectionContinuation = null
                    }
                }
            }
            
            if (result == null) {
                Log.e(TAG, "绑定服务超时 (${BIND_SERVICE_TIMEOUT_MS}ms)")
                connectionContinuation = null
                _isBound.value = false
                try {
                    context.applicationContext.unbindService(serviceConnection)
                } catch (e: Exception) {
                    // 忽略
                }
                return@withLock false
            }
            
            Log.d(TAG, "服务绑定成功")
            result
        }
    }
    
    /**
     * 步骤8：解绑服务
     */
    fun unbindFromService(context: Context) {
        if (_isBound.value) {
            try {
                context.applicationContext.unbindService(serviceConnection)
            } catch (e: Exception) {
                Log.e(TAG, "解绑服务失败", e)
            }
            _isBound.value = false
            accessibilityProvider = null
            Log.d(TAG, "服务已解绑")
        }
    }
    
    /**
     * 步骤9：确保服务已绑定
     * 
     * 业务目的：在执行操作前自动检查并重新绑定服务
     */
    private suspend fun ensureBound(context: Context): Boolean {
        if (!_isBound.value || accessibilityProvider == null) {
            Log.w(TAG, "服务未绑定，尝试自动重新绑定...")
            val bound = bindToService(context)
            if (!bound) {
                Log.e(TAG, "自动重新绑定失败")
                return false
            }
        }
        return _isBound.value && accessibilityProvider != null
    }
    
    /**
     * 步骤10：执行点击操作
     * 
     * 业务目的：通过无障碍服务在指定坐标执行点击
     * 
     * @param context 应用上下文
     * @param x X 坐标
     * @param y Y 坐标
     * @return 操作是否成功
     */
    suspend fun performClick(context: Context, x: Int, y: Int): Boolean {
        if (!ensureBound(context)) {
            Log.w(TAG, "服务未绑定，无法执行点击")
            return false
        }
        return try {
            accessibilityProvider?.performClick(x, y) ?: false
        } catch (e: RemoteException) {
            Log.e(TAG, "执行点击操作失败", e)
            false
        }
    }
    
    /**
     * 步骤11：执行长按操作
     */
    suspend fun performLongPress(context: Context, x: Int, y: Int): Boolean {
        if (!ensureBound(context)) {
            Log.w(TAG, "服务未绑定，无法执行长按")
            return false
        }
        return try {
            accessibilityProvider?.performLongPress(x, y) ?: false
        } catch (e: RemoteException) {
            Log.e(TAG, "执行长按操作失败", e)
            false
        }
    }
    
    /**
     * 步骤12：执行滑动操作
     */
    suspend fun performSwipe(
        context: Context,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        duration: Long
    ): Boolean {
        if (!ensureBound(context)) {
            Log.w(TAG, "服务未绑定，无法执行滑动")
            return false
        }
        return try {
            accessibilityProvider?.performSwipe(startX, startY, endX, endY, duration) ?: false
        } catch (e: RemoteException) {
            Log.e(TAG, "执行滑动操作失败", e)
            false
        }
    }
    
    /**
     * 步骤13：获取 UI 层次结构
     */
    suspend fun getUIHierarchy(context: Context): String {
        if (!ensureBound(context)) {
            Log.e(TAG, "服务未绑定，无法获取 UI 层次结构")
            return ""
        }
        return try {
            accessibilityProvider?.uiHierarchy ?: ""
        } catch (e: RemoteException) {
            Log.e(TAG, "获取 UI 层次结构失败", e)
            ""
        }
    }
    
    /**
     * 步骤14：检查无障碍服务是否已启用
     */
    suspend fun isAccessibilityServiceEnabled(context: Context): Boolean {
        if (!ensureBound(context)) {
            Log.w(TAG, "服务未绑定，无法检查无障碍服务状态")
            return false
        }
        return try {
            accessibilityProvider?.isAccessibilityServiceEnabled ?: false
        } catch (e: RemoteException) {
            Log.e(TAG, "检查无障碍服务状态失败", e)
            false
        }
    }
    
    /**
     * 步骤15：截取屏幕截图
     * 
     * 业务目的：通过无障碍服务截取当前屏幕
     * 操作实现：调用 Provider 的 takeScreenshot AIDL 方法
     * 
     * @param context 应用上下文
     * @param savePath 保存路径
     * @param format 图片格式（PNG, JPEG）
     * @return 截图是否成功
     */
    suspend fun takeScreenshot(context: Context, savePath: String, format: String = "PNG"): Boolean {
        if (!ensureBound(context)) {
            Log.w(TAG, "服务未绑定，无法截取屏幕截图")
            return false
        }
        return try {
            val file = File(savePath)
            // 确保父目录存在
            file.parentFile?.mkdirs()
            
            // 使用 ParcelFileDescriptor 传递文件描述符，解决跨进程权限问题
            // MODE_CREATE: 如果文件不存在则创建
            // MODE_TRUNCATE: 如果文件存在则清空
            // MODE_WRITE_ONLY: 只写模式
            val pfd = ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_CREATE or 
                ParcelFileDescriptor.MODE_TRUNCATE or 
                ParcelFileDescriptor.MODE_WRITE_ONLY
            )
            
            val result = pfd.use { fd ->
                accessibilityProvider?.takeScreenshot(fd, format) ?: false
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "截取屏幕截图失败", e)
            false
        }
    }
    
    /**
     * 步骤16：查找当前焦点节点
     * 
     * 业务目的：获取当前具有输入焦点的节点ID，用于后续文本输入
     * 操作实现：调用 Provider 的 findFocusedNodeId AIDL 方法
     * 
     * @param context 应用上下文
     * @return 焦点节点ID，如果没有焦点则返回 null
     */
    suspend fun findFocusedNodeId(context: Context): String? {
        Log.d(TAG, "[TEXT_INPUT] 🔍 开始查找焦点节点")
        Log.d(TAG, "[TEXT_INPUT] 当前绑定状态: isBound=${_isBound.value}, provider=${accessibilityProvider != null}")
        
        if (!ensureBound(context)) {
            Log.e(TAG, "[TEXT_INPUT] ❌ 服务未绑定，无法查找焦点节点")
            return null
        }
        
        Log.d(TAG, "[TEXT_INPUT] ✅ 服务已绑定，调用 Provider.findFocusedNodeId()")
        return try {
            val nodeId = accessibilityProvider?.findFocusedNodeId()
            if (nodeId != null) {
                Log.d(TAG, "[TEXT_INPUT] ✅ 找到焦点节点: nodeId=$nodeId")
            } else {
                Log.w(TAG, "[TEXT_INPUT] ⚠️ Provider 返回 null (未找到焦点节点)")
            }
            nodeId
        } catch (e: RemoteException) {
            Log.e(TAG, "[TEXT_INPUT] ❌ 查找焦点节点失败 (RemoteException)", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "[TEXT_INPUT] ❌ 查找焦点节点失败 (异常)", e)
            null
        }
    }
    
    /**
     * 步骤17：在指定节点上设置文本
     * 
     * 业务目的：向指定节点输入文本（非root方式）
     * 操作实现：调用 Provider 的 setTextOnNode AIDL 方法
     * 
     * @param context 应用上下文
     * @param nodeId 节点ID（通过 findFocusedNodeId 获取）
     * @param text 要输入的文本
     * @return 操作是否成功
     */
    suspend fun setTextOnNode(context: Context, nodeId: String, text: String): Boolean {
        Log.d(TAG, "[TEXT_INPUT] ⌨️ 开始设置文本: nodeId=$nodeId, text='$text'")
        Log.d(TAG, "[TEXT_INPUT] 当前绑定状态: isBound=${_isBound.value}, provider=${accessibilityProvider != null}")
        
        if (!ensureBound(context)) {
            Log.e(TAG, "[TEXT_INPUT] ❌ 服务未绑定，无法设置文本")
            return false
        }
        
        Log.d(TAG, "[TEXT_INPUT] ✅ 服务已绑定，调用 Provider.setTextOnNode()")
        return try {
            val result = accessibilityProvider?.setTextOnNode(nodeId, text) ?: false
            if (result) {
                Log.d(TAG, "[TEXT_INPUT] ✅ 设置文本成功")
            } else {
                Log.w(TAG, "[TEXT_INPUT] ⚠️ Provider 返回 false (设置文本失败)")
            }
            result
        } catch (e: RemoteException) {
            Log.e(TAG, "[TEXT_INPUT] ❌ 设置文本失败 (RemoteException)", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "[TEXT_INPUT] ❌ 设置文本失败 (异常)", e)
            false
        }
    }
}
