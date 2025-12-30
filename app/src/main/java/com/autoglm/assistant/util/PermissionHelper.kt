package com.autoglm.assistant.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object PermissionHelper {

    const val REQUEST_CODE_PERMISSIONS = 1001
    const val REQUEST_CODE_OVERLAY = 1002
    const val REQUEST_CODE_ACCESSIBILITY = 1003

    // 所需权限
    val REQUIRED_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS
        )
    } else {
        arrayOf(
            Manifest.permission.RECORD_AUDIO
        )
    }

    /**
     * 检查是否已授予所有必要权限
     */
    fun hasAllPermissions(context: Context): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        } && hasOverlayPermission(context) && isAccessibilityServiceEnabled(context)
    }

    /**
     * 检查是否有麦克风权限
     */
    fun hasMicrophonePermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查是否有悬浮窗权限
     */
    fun hasOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    /**
     * 检查无障碍服务是否已启用
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val serviceName = "${context.packageName}/${context.packageName}.service.AutomationService"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return enabledServices?.contains(serviceName) == true
    }

    /**
     * 请求麦克风权限
     */
    fun requestMicrophonePermission(activity: Activity) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_CODE_PERMISSIONS
        )
    }

    /**
     * 请求所有必要权限
     */
    fun requestAllPermissions(activity: Activity) {
        ActivityCompat.requestPermissions(
            activity,
            REQUIRED_PERMISSIONS,
            REQUEST_CODE_PERMISSIONS
        )
    }

    /**
     * 打开悬浮窗权限设置页面
     */
    fun openOverlayPermissionSettings(activity: Activity) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${activity.packageName}")
        )
        activity.startActivityForResult(intent, REQUEST_CODE_OVERLAY)
    }

    /**
     * 打开无障碍设置页面
     */
    fun openAccessibilitySettings(activity: Activity) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        activity.startActivityForResult(intent, REQUEST_CODE_ACCESSIBILITY)
    }

    /**
     * 打开应用详情设置页面
     */
    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        context.startActivity(intent)
    }

    /**
     * 权限状态数据类
     */
    data class PermissionStatus(
        val hasMicrophone: Boolean,
        val hasNotification: Boolean,
        val hasOverlay: Boolean,
        val hasAccessibility: Boolean
    ) {
        val isComplete: Boolean
            get() = hasMicrophone && hasNotification && hasOverlay && hasAccessibility
    }

    fun checkPermissionStatus(context: Context): PermissionStatus {
        return PermissionStatus(
            hasMicrophone = hasMicrophonePermission(context),
            hasNotification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else true,
            hasOverlay = hasOverlayPermission(context),
            hasAccessibility = isAccessibilityServiceEnabled(context)
        )
    }
}
