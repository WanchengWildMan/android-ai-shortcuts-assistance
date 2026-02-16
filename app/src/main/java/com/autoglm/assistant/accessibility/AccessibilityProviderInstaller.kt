package com.autoglm.assistant.accessibility

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlin.math.min

/**
 * 无障碍服务提供者应用安装管理器
 * 
 * 业务目的：管理独立的无障碍服务提供者应用的版本检查和更新
 * 
 * 核心功能：
 * 1. 版本比较：检查已安装版本与内置版本
 * 2. 缓存管理：减少频繁的版本检查开销
 * 3. 更新提示：提供版本更新建议
 */
class AccessibilityProviderInstaller {
    companion object {
        private const val TAG = "AccessibilityProviderInstaller"
        
        // HARD: 必须与 UIHierarchyManager 中的包名保持一致
        private const val ACCESSIBILITY_PACKAGE_NAME = "com.autoglm.assistant.provider"
        
        // 步骤1：缓存配置
        private var cachedInstalledVersion: String? = null
        private var cachedBundledVersion: String? = null
        private var cachedUpdateNeeded: Boolean? = null
        private var lastCheckTime: Long = 0
        private const val CACHE_EXPIRE_TIME = 60 * 1000 // 缓存有效期1分钟
        
        /**
         * 步骤2：获取内置 APK 的版本信息
         * 
         * 业务目的：读取 assets 中的版本文件，确定内置的提供者应用版本
         * 操作实现：从 accessibility_version.txt 文件读取版本号
         * 
         * @param context 应用上下文
         * @return 版本号字符串，失败返回 "未知"
         */
        fun getBundledVersion(context: Context): String {
            if (cachedBundledVersion != null && !isCacheExpired()) {
                return cachedBundledVersion!!
            }
            
            try {
                val versionInfo = context.assets.open("accessibility_version.txt").use {
                    it.bufferedReader().readText().trim()
                }
                cachedBundledVersion = versionInfo
                return versionInfo
            } catch (e: Exception) {
                Log.e(TAG, "获取内置无障碍服务版本失败", e)
                val unknown = "未知"
                cachedBundledVersion = unknown
                return unknown
            }
        }
        
        /**
         * 步骤3：获取已安装的提供者应用版本
         * 
         * 业务目的：检查系统中是否已安装提供者应用及其版本
         * 
         * @param context 应用上下文
         * @return 版本号字符串，未安装返回 null
         */
        fun getInstalledVersion(context: Context): String? {
            if (cachedInstalledVersion != null && !isCacheExpired()) {
                return cachedInstalledVersion
            }
            
            try {
                val packageManager = context.packageManager
                val packageInfo: PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getPackageInfo(
                        ACCESSIBILITY_PACKAGE_NAME,
                        PackageManager.PackageInfoFlags.of(0)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(ACCESSIBILITY_PACKAGE_NAME, 0)
                }
                cachedInstalledVersion = packageInfo.versionName
                return packageInfo.versionName
            } catch (e: PackageManager.NameNotFoundException) {
                cachedInstalledVersion = null
                return null
            } catch (e: Exception) {
                Log.e(TAG, "获取已安装无障碍服务版本出错", e)
                cachedInstalledVersion = null
                return null
            }
        }
        
        /**
         * 步骤4：检查是否需要更新
         * 
         * 业务目的：比较已安装版本与内置版本，判断是否需要更新
         * 操作实现：使用语义化版本号比较（major.minor.patch）
         * 
         * @param context 应用上下文
         * @return true 表示需要更新，false 表示不需要
         */
        fun isUpdateNeeded(context: Context): Boolean {
            if (cachedUpdateNeeded != null && !isCacheExpired()) {
                return cachedUpdateNeeded!!
            }
            
            val installedVersion = getInstalledVersion(context)
            if (installedVersion == null) {
                // 未安装，不需要更新（应该安装）
                cachedUpdateNeeded = false
                updateCacheTimestamp()
                return false
            }
            
            val bundledVersion = getBundledVersion(context)
            if (bundledVersion == "未知") {
                // 无法确定内置版本，不建议更新
                cachedUpdateNeeded = false
                updateCacheTimestamp()
                return false
            }
            
            try {
                // 步骤4.1：解析版本号为数字数组
                val installed = installedVersion.split(".").map { it.toIntOrNull() ?: 0 }
                val bundled = bundledVersion.split(".").map { it.toIntOrNull() ?: 0 }
                
                // 步骤4.2：逐位比较版本号
                val commonPartLength = min(installed.size, bundled.size)
                for (i in 0 until commonPartLength) {
                    if (bundled[i] > installed[i]) {
                        // 内置版本更高，需要更新
                        cachedUpdateNeeded = true
                        updateCacheTimestamp()
                        return true
                    }
                    if (bundled[i] < installed[i]) {
                        // 已安装版本更高，不需要更新
                        cachedUpdateNeeded = false
                        updateCacheTimestamp()
                        return false
                    }
                }
                
                // 步骤4.3：检查版本号长度
                if (bundled.size > installed.size) {
                    // 内置版本有更多位数，需要更新
                    cachedUpdateNeeded = true
                    updateCacheTimestamp()
                    return true
                }
                
                // 版本相同，不需要更新
                cachedUpdateNeeded = false
                updateCacheTimestamp()
                return false
            } catch (e: Exception) {
                Log.e(TAG, "比较无障碍服务版本时出错", e)
                cachedUpdateNeeded = false
                updateCacheTimestamp()
                return false
            }
        }
        
        /**
         * 步骤5：启动安装流程
         * 
         * 业务目的：调用 UIHierarchyManager 启动提供者应用的安装
         */
        fun launchInstall(context: Context) {
            UIHierarchyManager.launchProviderInstall(context)
            clearCache() // 清除缓存以在安装后刷新状态
        }
        
        /**
         * 步骤6：更新缓存时间戳
         */
        private fun updateCacheTimestamp() {
            lastCheckTime = System.currentTimeMillis()
        }
        
        /**
         * 步骤7：检查缓存是否过期
         */
        private fun isCacheExpired(): Boolean {
            return System.currentTimeMillis() - lastCheckTime > CACHE_EXPIRE_TIME
        }
        
        /**
         * 步骤8：清除所有缓存
         * 
         * 业务目的：在安装或更新后强制刷新版本信息
         */
        fun clearCache() {
            cachedInstalledVersion = null
            cachedBundledVersion = null
            cachedUpdateNeeded = null
            lastCheckTime = 0
            Log.d(TAG, "无障碍服务版本缓存已清除")
        }
    }
}
