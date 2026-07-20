#!/bin/bash
set -e

PROJECT="/data/data/com.termux/files/home/wxbackup"
MSG="${1:-chore: build}"

echo "═══════════════════════════════════════════"
echo "  wxhook — GitHub Actions 构建流程"
echo "═══════════════════════════════════════════"
echo ""
echo "步骤:"
echo "  1. git add + commit + push → 触发 GitHub Actions"
echo "  2. Actions 自动编译 (JDK 17 + SDK 35 + NDK)"
echo "  3. 发布 nightly release"
echo "  4. 本地下载并安装"
echo ""

# 提交并推送
echo "=== [1/2] 推送代码到 GitHub ==="
cd "$PROJECT"
git add -A
if git diff --cached --quiet; then
  echo "  无新增改动"
else
  git commit -m "$MSG"
  git push
  echo "  ✅ 推送完成 → 触发 Actions"
  echo "  查看进度: https://github.com/linuxwff789/wxbackup/actions"
fi

# 下载 nightly APK
echo ""
echo "=== [2/2] 下载最新 Nightly APK ==="
OUT_DIR="$PROJECT/app/build/outputs/apk/debug"
mkdir -p "$OUT_DIR"

APP_URL="https://github.com/linuxwff789/wxbackup/releases/download/nightly/app-debug.apk"
APP_LOCAL="$OUT_DIR/app-debug.apk"

echo "  下载: $APP_URL"
if curl -fL --retry 3 --retry-all-errors --progress-bar -o "$APP_LOCAL" "$APP_URL" 2>/dev/null; then
  echo "  ✅ $(ls -lh "$APP_LOCAL" | awk '{print $5}')"
else
  echo "  ⏳ 构建可能尚未完成，稍后重试"
  echo "  手动下载: $APP_URL"
  echo "  或使用: bash scripts/install-nightly-release.sh"
fi

echo ""
echo "安装: adb install -r $APP_LOCAL"
echo ""
