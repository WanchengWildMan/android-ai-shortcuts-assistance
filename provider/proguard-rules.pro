# 保留 AIDL 接口
-keep class com.autoglm.assistant.provider.IAccessibilityProvider { *; }
-keep class com.autoglm.assistant.provider.IAccessibilityProvider$Stub { *; }
-keep class com.autoglm.assistant.provider.IAccessibilityProvider$Stub$Proxy { *; }

# 保留无障碍服务
-keep class com.autoglm.assistant.provider.** { *; }
