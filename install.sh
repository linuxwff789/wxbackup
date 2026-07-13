#!/bin/bash
set -e

PROJECT="/data/data/com.termux/files/home/wxbackup"
APK="$PROJECT/app/build/outputs/apk/debug/app-debug.apk"

# 先构建
bash "$PROJECT/build.sh"

echo "=== 安装到设备 ==="
adb install -r "$APK"

echo "=== 验证安装 ==="
adb shell pm list packages | grep com.nous.wxhook && echo "✅ 安装成功" || echo "❌ 安装失败"
