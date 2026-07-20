#!/bin/bash
set -e

echo "═══════════════════════════════════════════"
echo "  wxhook — 安装 Nightly APK"
echo "═══════════════════════════════════════════"

# 优先使用本地构建的 APK
LOCAL_APK="/data/data/com.termux/files/home/wxbackup/app/build/outputs/apk/debug/app-debug.apk"

if [ -f "$LOCAL_APK" ]; then
  echo "  使用本地 APK: $(ls -lh "$LOCAL_APK" | awk '{print $5}')"
  adb install -r "$LOCAL_APK" 2>/dev/null && { echo "✅ 安装成功"; exit 0; }
  echo "  ⚠️ 本地安装失败，尝试下载 nightly..."
fi

# 从 GitHub Actions nightly release 下载
TMP_APK=$(mktemp /tmp/wxhook-nightly-XXXXXX.apk)
trap 'rm -f "$TMP_APK"' EXIT

URL="https://github.com/linuxwff789/wxbackup/releases/download/nightly/app-debug.apk"
echo "  下载: $URL"
curl -fL --retry 3 --retry-all-errors --progress-bar -o "$TMP_APK" "$URL"

echo "  大小: $(ls -lh "$TMP_APK" | awk '{print $5}')"
echo ""

# 安装
echo "=== 安装到设备 ==="
adb install -r "$TMP_APK"

echo ""
echo "=== 验证 ==="
adb shell pm list packages | grep com.nous.wxhook && echo "✅ 安装成功" || echo "❌ 安装失败"
