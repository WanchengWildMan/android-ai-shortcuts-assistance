#!/bin/bash

# Prompt 配置检查脚本
# 用于快速检查 Agent、Coordinator 和 Optimizer 的 prompt 配置是否生效

echo "=========================================="
echo "AutoGLM Prompt 配置检查工具"
echo "=========================================="
echo ""

# 检查设备连接
if ! adb devices | grep -q "device$"; then
    echo "❌ 错误: 未检测到 Android 设备"
    echo "请确保："
    echo "1. 设备已通过 USB 连接"
    echo "2. 已启用 USB 调试"
    echo "3. 已授权此电脑的调试权限"
    exit 1
fi

echo "✅ 设备已连接"
echo ""

# 导出 SharedPreferences
echo "正在读取配置..."
PREFS=$(adb shell run-as com.autoglm.assistant cat /data/data/com.autoglm.assistant/shared_prefs/autoglm_prefs.xml 2>&1)

if echo "$PREFS" | grep -q "No such file"; then
    echo "❌ 错误: 无法读取配置文件"
    echo "可能原因："
    echo "1. App 未安装"
    echo "2. App 包名不正确"
    echo "3. 设备不支持 run-as 命令（需要 debuggable 版本）"
    exit 1
fi

echo "✅ 配置文件读取成功"
echo ""

# 解析配置
echo "=========================================="
echo "1. Agent System Prompt"
echo "=========================================="
AGENT_PROMPT=$(echo "$PREFS" | grep -o 'name="agent_system_prompt"[^>]*>.*</string>' | sed 's/.*>\(.*\)<\/string>/\1/')
if [ -z "$AGENT_PROMPT" ]; then
    echo "状态: ⚪ 未配置（使用内置默认）"
else
    AGENT_LENGTH=${#AGENT_PROMPT}
    echo "状态: ✅ 已配置"
    echo "长度: $AGENT_LENGTH 字符"
    echo "预览: ${AGENT_PROMPT:0:100}..."
fi
echo ""

echo "=========================================="
echo "2. Coordinator System Prompt"
echo "=========================================="
COORDINATOR_ENABLED=$(echo "$PREFS" | grep -o 'name="smart_coordinator_enabled" value="[^"]*"' | sed 's/.*value="\([^"]*\)".*/\1/')
COORDINATOR_PROMPT=$(echo "$PREFS" | grep -o 'name="coordinator_system_prompt"[^>]*>.*</string>' | sed 's/.*>\(.*\)<\/string>/\1/')

echo "协调器启用: $COORDINATOR_ENABLED"
if [ -z "$COORDINATOR_PROMPT" ]; then
    echo "状态: ⚪ 未配置（使用内置默认）"
else
    COORDINATOR_LENGTH=${#COORDINATOR_PROMPT}
    echo "状态: ✅ 已配置"
    echo "长度: $COORDINATOR_LENGTH 字符"
    echo "预览: ${COORDINATOR_PROMPT:0:100}..."
fi

# 检查协调器 API 配置
COORDINATOR_URL=$(echo "$PREFS" | grep -o 'name="coordinator_api_url"[^>]*>.*</string>' | sed 's/.*>\(.*\)<\/string>/\1/')
COORDINATOR_MODEL=$(echo "$PREFS" | grep -o 'name="coordinator_model_name"[^>]*>.*</string>' | sed 's/.*>\(.*\)<\/string>/\1/')
echo "API URL: $COORDINATOR_URL"
echo "Model: $COORDINATOR_MODEL"
echo ""

echo "=========================================="
echo "3. Optimizer System Prompt"
echo "=========================================="
OPTIMIZER_ENABLED=$(echo "$PREFS" | grep -o 'name="prompt_optimizer_enabled" value="[^"]*"' | sed 's/.*value="\([^"]*\)".*/\1/')
OPTIMIZER_PROMPT=$(echo "$PREFS" | grep -o 'name="optimizer_system_prompt"[^>]*>.*</string>' | sed 's/.*>\(.*\)<\/string>/\1/')

echo "优化器启用: $OPTIMIZER_ENABLED"
if [ -z "$OPTIMIZER_PROMPT" ]; then
    echo "状态: ⚪ 未配置（使用内置默认）"
else
    OPTIMIZER_LENGTH=${#OPTIMIZER_PROMPT}
    echo "状态: ✅ 已配置"
    echo "长度: $OPTIMIZER_LENGTH 字符"
    echo "预览: ${OPTIMIZER_PROMPT:0:100}..."
fi

# 检查优化器 API 配置
OPTIMIZER_URL=$(echo "$PREFS" | grep -o 'name="optimizer_api_url"[^>]*>.*</string>' | sed 's/.*>\(.*\)<\/string>/\1/')
OPTIMIZER_MODEL=$(echo "$PREFS" | grep -o 'name="optimizer_model_name"[^>]*>.*</string>' | sed 's/.*>\(.*\)<\/string>/\1/')
echo "API URL: $OPTIMIZER_URL"
echo "Model: $OPTIMIZER_MODEL"
echo ""

echo "=========================================="
echo "4. 其他相关配置"
echo "=========================================="
MAX_STEPS=$(echo "$PREFS" | grep -o 'name="max_steps" value="[^"]*"' | sed 's/.*value="\([^"]*\)".*/\1/')
MAX_COORDINATOR_STEPS=$(echo "$PREFS" | grep -o 'name="max_coordinator_steps" value="[^"]*"' | sed 's/.*value="\([^"]*\)".*/\1/')
LANGUAGE=$(echo "$PREFS" | grep -o 'name="language"[^>]*>.*</string>' | sed 's/.*>\(.*\)<\/string>/\1/')

echo "最大步数: $MAX_STEPS"
echo "协调器最大步数: $MAX_COORDINATOR_STEPS"
echo "语言: $LANGUAGE"
echo ""

echo "=========================================="
echo "5. 诊断建议"
echo "=========================================="

# 检查问题
ISSUES=0

if [ "$COORDINATOR_ENABLED" = "true" ] && [ -z "$COORDINATOR_URL" ]; then
    echo "⚠️  协调器已启用但 API URL 未配置"
    ISSUES=$((ISSUES + 1))
fi

if [ "$OPTIMIZER_ENABLED" = "true" ] && [ -z "$OPTIMIZER_URL" ]; then
    echo "⚠️  优化器已启用但 API URL 未配置"
    ISSUES=$((ISSUES + 1))
fi

if [ $ISSUES -eq 0 ]; then
    echo "✅ 未发现配置问题"
    echo ""
    echo "如果 prompt 仍未生效，请："
    echo "1. 在 App 设置中修改配置后点击保存"
    echo "2. 重启 App 或服务"
    echo "3. 查看 Logcat 日志确认配置加载"
else
    echo ""
    echo "请修复上述问题后重试"
fi

echo ""
echo "=========================================="
echo "完整配置文件已保存到: prefs_dump.xml"
echo "=========================================="
echo "$PREFS" > prefs_dump.xml
