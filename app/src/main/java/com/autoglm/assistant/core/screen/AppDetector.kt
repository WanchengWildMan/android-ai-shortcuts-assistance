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

    fun getPackageFromAppName(appName: String): String? {
        // 先检查中文名映射
        CN_APP_NAMES[appName]?.let { return it }

        // 再检查英文名映射
        APP_PACKAGES.entries.find { it.value.equals(appName, ignoreCase = true) }?.key?.let { return it }

        // 模糊匹配中文名
        CN_APP_NAMES.entries.find { appName.contains(it.key) || it.key.contains(appName) }?.value?.let { return it }

        return null
    }

    fun getSupportedApps(): Map<String, String> = APP_PACKAGES
}
