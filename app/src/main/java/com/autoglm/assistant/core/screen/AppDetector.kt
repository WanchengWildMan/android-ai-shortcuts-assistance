package com.autoglm.assistant.core.screen

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import com.autoglm.assistant.util.ShellExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AppDetector {

    // Package name to app name mapping (subset of common apps)
    // 包含中英文名称
    private val APP_PACKAGES = mapOf(
        "com.tencent.mm" to "WeChat",
        "com.tencent.mobileqq" to "QQ",
        "com.sina.weibo" to "Weibo",
        "com.taobao.taobao" to "Taobao",
        "com.jingdong.app.mall" to "JD",
        "com.xunmeng.pinduoduo" to "Pinduoduo",
        "com.xingin.xhs" to "Xiaohongshu",
        "com.ss.android.ugc.aweme" to "Douyin",
        "com.smile.gifmaker" to "Kuaishou",
        "tv.danmaku.bili" to "Bilibili",
        "com.sankuai.meituan" to "Meituan",
        "com.sankuai.meituan.enterprise" to "MeituanEnterprise",
        "me.ele" to "Eleme",
        "com.dianping.v1" to "Dianping",
        "com.autonavi.minimap" to "Amap",
        "com.baidu.BaiduMap" to "Baidu Map",
        "com.netease.cloudmusic" to "NetEase Music",
        "com.tencent.qqmusic" to "QQ Music",
        "com.kugou.android" to "Kugou",
        "com.tencent.qqlive" to "Tencent Video",
        "com.qiyi.video" to "iQiyi",
        "com.youku.phone" to "Youku",
        "com.UCMobile" to "UC Browser",
        "com.android.chrome" to "Chrome",
        "com.android.settings" to "Settings",
        "com.android.contacts" to "Contacts",
        "com.android.mms" to "Messages",
        "com.android.dialer" to "Phone",
        "com.android.calendar" to "Calendar",
        "com.android.camera" to "Camera",
        "com.android.gallery3d" to "Gallery",
        "com.android.vending" to "Play Store",
        "com.google.android.apps.maps" to "Google Maps",
        "com.google.android.youtube" to "YouTube",
        "com.google.android.gm" to "Gmail",
        "com.whatsapp" to "WhatsApp",
        "com.facebook.katana" to "Facebook",
        "com.instagram.android" to "Instagram",
        "com.twitter.android" to "Twitter",
        "com.spotify.music" to "Spotify",
        "com.netflix.mediaclient" to "Netflix",
        "com.amazon.mShop.android.shopping" to "Amazon",
        "com.ctrip.ibu.market.newsvip" to "Ctrip",
        "com.Qunar" to "Qunar",
        "com.MobileTicket" to "12306"
    )

    suspend fun getCurrentApp(context: Context): String = withContext(Dispatchers.IO) {
        // Try shell command first (works with ADB permissions)
        val packageName = ShellExecutor.getCurrentPackage()
        if (packageName != null) {
            return@withContext getAppNameFromPackage(context, packageName)
        }

        // Fallback to UsageStatsManager
        try {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val endTime = System.currentTimeMillis()
            val startTime = endTime - 5000

            val usageStatsList = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, startTime, endTime
            )

            if (usageStatsList.isNotEmpty()) {
                val recentApp = usageStatsList
                    .filter { it.lastTimeUsed > startTime }
                    .maxByOrNull { it.lastTimeUsed }

                if (recentApp != null) {
                    return@withContext getAppNameFromPackage(context, recentApp.packageName)
                }
            }
        } catch (e: Exception) {
            // UsageStats permission not granted
        }

        return@withContext "System Home"
    }

    private fun getAppNameFromPackage(context: Context, packageName: String): String {
        // Check predefined mapping first
        APP_PACKAGES[packageName]?.let { return it }

        // Try to get app label from PackageManager
        return try {
            val packageManager = context.packageManager
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName.substringAfterLast('.')
        }
    }

    // 中文名到包名的映射
    private val CN_APP_NAMES = mapOf(
        "微信" to "com.tencent.mm",
        "QQ" to "com.tencent.mobileqq",
        "微博" to "com.sina.weibo",
        "淘宝" to "com.taobao.taobao",
        "京东" to "com.jingdong.app.mall",
        "拼多多" to "com.xunmeng.pinduoduo",
        "小红书" to "com.xingin.xhs",
        "抖音" to "com.ss.android.ugc.aweme",
        "快手" to "com.smile.gifmaker",
        "哔哩哔哩" to "tv.danmaku.bili",
        "B站" to "tv.danmaku.bili",
        "bilibili" to "tv.danmaku.bili",
        "美团" to "com.sankuai.meituan",
        "美团企业版" to "com.sankuai.meituan.enterprise",
        "饿了么" to "me.ele",
        "大众点评" to "com.dianping.v1",
        "高德地图" to "com.autonavi.minimap",
        "百度地图" to "com.baidu.BaiduMap",
        "网易云音乐" to "com.netease.cloudmusic",
        "QQ音乐" to "com.tencent.qqmusic",
        "酷狗音乐" to "com.kugou.android",
        "腾讯视频" to "com.tencent.qqlive",
        "爱奇艺" to "com.qiyi.video",
        "优酷" to "com.youku.phone",
        "UC浏览器" to "com.UCMobile",
        "谷歌浏览器" to "com.android.chrome",
        "Chrome" to "com.android.chrome",
        "设置" to "com.android.settings",
        "通讯录" to "com.android.contacts",
        "联系人" to "com.android.contacts",
        "短信" to "com.android.mms",
        "信息" to "com.android.mms",
        "电话" to "com.android.dialer",
        "日历" to "com.android.calendar",
        "相机" to "com.android.camera",
        "相册" to "com.android.gallery3d",
        "图库" to "com.android.gallery3d",
        "应用商店" to "com.android.vending",
        "谷歌地图" to "com.google.android.apps.maps",
        "YouTube" to "com.google.android.youtube",
        "Gmail" to "com.google.android.gm",
        "邮箱" to "com.google.android.gm",
        "WhatsApp" to "com.whatsapp",
        "Facebook" to "com.facebook.katana",
        "Instagram" to "com.instagram.android",
        "Twitter" to "com.twitter.android",
        "Spotify" to "com.spotify.music",
        "Netflix" to "com.netflix.mediaclient",
        "Amazon" to "com.amazon.mShop.android.shopping",
        "亚马逊" to "com.amazon.mShop.android.shopping",
        "携程" to "com.ctrip.ibu.market.newsvip",
        "去哪儿" to "com.Qunar",
        "12306" to "com.MobileTicket",
        "铁路12306" to "com.MobileTicket",
        // 办公应用
        "飞书" to "com.ss.android.lark",
        "钉钉" to "com.alibaba.android.rimet",
        "企业微信" to "com.tencent.wework",
        // AI 应用
        "豆包" to "com.larus.nova",
        "文心一言" to "com.baidu.newapp",
        "通义千问" to "com.alibaba.ailabs.tongyi",
        // 浏览器
        "夸克" to "com.quark.browser",
        "百度" to "com.baidu.searchbox",
        "Safari" to "com.apple.mobilesafari",
        // 社交
        "Soul" to "cn.soulapp.android",
        "探探" to "com.p1.mobile.putong",
        "陌陌" to "com.immomo.momo"
    )

    /**
     * 根据应用名查找包名
     * 支持精确匹配、模糊匹配和动态查找
     */
    suspend fun getPackageFromAppName(context: Context, appName: String): String? = withContext(Dispatchers.IO) {
        // 1. 先检查中文名映射（精确）
        CN_APP_NAMES[appName]?.let { return@withContext it }

        // 2. 再检查英文名映射（精确）
        APP_PACKAGES.entries.find { it.value.equals(appName, ignoreCase = true) }?.key?.let { return@withContext it }

        // 3. 模糊匹配预定义列表
        CN_APP_NAMES.entries.find { appName.contains(it.key) || it.key.contains(appName) }?.value?.let { return@withContext it }
        APP_PACKAGES.entries.find { appName.contains(it.value, ignoreCase = true) || it.value.contains(appName, ignoreCase = true) }?.key?.let { return@withContext it }

        // 4. 动态查找：从设备已安装应用中搜索
        return@withContext findAppFromDevice(context, appName)
    }

    /**
     * 同步版本的 getPackageFromAppName，仅使用预定义列表
     */
    fun getPackageFromAppName(appName: String): String? {
        // 先检查中文名映射
        CN_APP_NAMES[appName]?.let { return it }

        // 再检查英文名映射
        APP_PACKAGES.entries.find { it.value.equals(appName, ignoreCase = true) }?.key?.let { return it }

        // 模糊匹配中文名
        CN_APP_NAMES.entries.find { appName.contains(it.key) || it.key.contains(appName) }?.value?.let { return it }

        return null
    }

    /**
     * 从设备已安装应用中查找匹配的应用
     * 使用 PackageManager 获取应用标签进行匹配
     */
    private suspend fun findAppFromDevice(context: Context, appName: String): String? = withContext(Dispatchers.IO) {
        try {
            val pm = context.packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)

            com.autoglm.assistant.util.Logger.d("AppDetector", "Searching for '$appName' in ${packages.size} packages")

            val searchName = appName.lowercase()
            var bestMatch: Pair<String, Int>? = null  // packageName to score
            val candidates = mutableListOf<Triple<String, String, Int>>() // label, pkg, score

            for (appInfo in packages) {
                try {
                    val label = pm.getApplicationLabel(appInfo).toString()
                    val labelLower = label.lowercase()
                    val pkgLower = appInfo.packageName.lowercase()

                    // 计算匹配分数
                    val score = when {
                        // 精确匹配应用名
                        labelLower == searchName -> 100
                        // 应用名包含搜索词
                        labelLower.contains(searchName) -> 80
                        // 搜索词包含应用名
                        searchName.contains(labelLower) && labelLower.length >= 2 -> 70
                        // 包名包含搜索词（中文也支持）
                        pkgLower.contains(searchName) -> 50
                        // 包名中的关键词匹配（如 weather, gallery, note）
                        pkgLower.contains("weather") && searchName.contains("天气") -> 60
                        pkgLower.contains("gallery") && (searchName.contains("相册") || searchName.contains("图库")) -> 60
                        pkgLower.contains("note") && searchName.contains("笔记") -> 60
                        pkgLower.contains("camera") && searchName.contains("相机") -> 60
                        pkgLower.contains("calendar") && searchName.contains("日历") -> 60
                        pkgLower.contains("clock") && (searchName.contains("时钟") || searchName.contains("闹钟")) -> 60
                        pkgLower.contains("calculator") && searchName.contains("计算") -> 60
                        pkgLower.contains("music") && searchName.contains("音乐") -> 60
                        pkgLower.contains("video") && searchName.contains("视频") -> 60
                        pkgLower.contains("file") && searchName.contains("文件") -> 60
                        // 部分匹配
                        labelLower.split(" ", "·", "-").any { it.contains(searchName) || searchName.contains(it) } -> 40
                        else -> 0
                    }

                    if (score > 0) {
                        candidates.add(Triple(label, appInfo.packageName, score))
                        if (bestMatch == null || score > bestMatch.second) {
                            bestMatch = appInfo.packageName to score
                        }
                    }
                } catch (e: Exception) {
                    // Skip this app
                }
            }

            // 打印所有候选
            if (candidates.isNotEmpty()) {
                val top5 = candidates.sortedByDescending { it.third }.take(5)
                com.autoglm.assistant.util.Logger.d("AppDetector", "Top candidates for '$appName': ${top5.map { "${it.first}(${it.third})" }}")
            }

            // 只返回分数足够高的匹配
            if (bestMatch != null && bestMatch.second >= 40) {
                com.autoglm.assistant.util.Logger.d("AppDetector", "Found app '${appName}' -> ${bestMatch.first} (score: ${bestMatch.second})")
                return@withContext bestMatch.first
            }

            com.autoglm.assistant.util.Logger.w("AppDetector", "App not found: $appName (no candidates matched)")
            return@withContext null
        } catch (e: Exception) {
            com.autoglm.assistant.util.Logger.e("AppDetector", "Error finding app: $appName", e)
            return@withContext null
        }
    }

    /**
     * 获取设备上所有已安装应用的名称和包名映射
     * 用于调试或展示给用户
     */
    suspend fun getAllInstalledApps(context: Context): Map<String, String> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, String>()
        try {
            val pm = context.packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)

            for (appInfo in packages) {
                try {
                    // 只包含有启动器图标的应用（排除系统服务等）
                    val launchIntent = pm.getLaunchIntentForPackage(appInfo.packageName)
                    if (launchIntent != null) {
                        val label = pm.getApplicationLabel(appInfo).toString()
                        result[label] = appInfo.packageName
                    }
                } catch (e: Exception) {
                    // Skip
                }
            }
        } catch (e: Exception) {
            com.autoglm.assistant.util.Logger.e("AppDetector", "Error getting installed apps", e)
        }
        return@withContext result
    }

    fun getSupportedApps(): Map<String, String> = APP_PACKAGES
}
