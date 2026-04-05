#!/bin/bash
# 安装 ADB Keyboard - 用于非root设备的文本输入

echo "📥 下载 ADB Keyboard APK..."
curl -L -o /tmp/adb-keyboard.apk https://github.com/senzhk/ADBKeyBoard/releases/download/v2.0/ADBKeyboard.apk

if [ ! -f /tmp/adb-keyboard.apk ]; then
    echo "❌ 下载失败"
    exit 1
fi

echo "📦 安装 ADB Keyboard..."
adb install /tmp/adb-keyboard.apk

if [ $? -eq 0 ]; then
    echo "✅ ADB Keyboard 安装成功！"
    echo ""
    echo "📋 下一步："
    echo "1. 在手机上打开 设置 > 系统 > 语言和输入 > 虚拟键盘"
    echo "2. 启用 'ADB Keyboard'"
    echo "3. 通过 ADB 设置为默认输入法："
    echo "   adb shell ime enable com.android.adbkeyboard/.AdbIME"
    echo "   adb shell ime set com.android.adbkeyboard/.AdbIME"
    echo ""
    echo "🔄 执行以下命令启用 ADB Keyboard："
    adb shell ime enable com.android.adbkeyboard/.AdbIME
    adb shell ime set com.android.adbkeyboard/.AdbIME
    echo ""
    echo "✅ 完成！现在可以使用 ADB 输入文本了"
else
    echo "❌ 安装失败"
    exit 1
fi

# 清理
rm /tmp/adb-keyboard.apk
