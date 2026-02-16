#!/bin/bash

# 一键安装主应用和无障碍服务提供者应用
# 使用方法：./install_both.sh

echo "🚀 开始安装应用..."

# 主应用 APK 路径
MAIN_APK="app/build/outputs/apk/debug/app-debug.apk"

# 无障碍服务提供者 APK 路径（使用 debug 版本，已签名）
# HARD: 必须使用 debug 版本，release 版本需要单独配置签名
PROVIDER_APK="provider/build/outputs/apk/debug/provider-debug.apk"

# 检查主应用 APK 是否存在
if [ ! -f "$MAIN_APK" ]; then
    echo "❌ 主应用 APK 不存在: $MAIN_APK"
    echo "💡 请先运行: ./gradlew assembleDebug"
    exit 1
fi

# 检查提供者 APK 是否存在
if [ ! -f "$PROVIDER_APK" ]; then
    echo "⚠️  无障碍服务提供者 APK 不存在: $PROVIDER_APK"
    echo "💡 将只安装主应用"
    echo ""
    
    # 只安装主应用
    echo "📦 安装主应用..."
    adb install -r "$MAIN_APK"
    
    if [ $? -eq 0 ]; then
        echo "✅ 主应用安装成功！"
    else
        echo "❌ 主应用安装失败！"
        exit 1
    fi
else
    # 同时安装两个应用
    echo "📦 安装主应用和无障碍服务提供者..."
    adb install -r "$MAIN_APK" && adb install -r "$PROVIDER_APK"
    
    if [ $? -eq 0 ]; then
        echo ""
        echo "✅ 两个应用都安装成功！"
        echo ""
        echo "📋 下一步操作："
        echo "1. 打开主应用"
        echo "2. 系统会提示安装无障碍服务提供者（如果之前未安装）"
        echo "3. 前往 设置 > 无障碍 > 启用 Accessibility Provider"
        echo ""
    else
        echo "❌ 安装失败！"
        exit 1
    fi
fi
