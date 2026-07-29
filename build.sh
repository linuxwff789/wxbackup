#!/bin/bash
set -e

PROJECT="/data/data/com.termux/files/home/wxbackup"
MSG="${1:-fix: 修复 key acquisition — ContentProvider authority 冲突 + 文件路径改为 WeChat filesDir}"

REPO="linuxwff789/wxbackup"
APP_URL="https://github.com/$REPO/releases/download/nightly/app-debug.apk"
XPOSED_URL="https://github.com/$REPO/releases/download/nightly/xposed-debug.apk"

OUT_DIR="$PROJECT/app/build/outputs/apk/debug"
XPOSED_OUT_DIR="$PROJECT/xposed/build/outputs/apk/debug"

echo "═══════════════════════════════════════════"
echo "  wxhook — 构建 + 部署"
echo "═══════════════════════════════════════════"
echo ""

cd "$PROJECT"

# 1. 提交并推送 → 触发 GitHub Actions
echo "=== [1/4] 推送代码到 GitHub ==="
git add -A
if git diff --cached --quiet; then
  echo "  无新增改动，跳过 commit"
else
  git commit -m "$MSG"
  git push
  echo "  ✅ 推送完成，Actions 已触发"
  echo "  查看进度: https://github.com/$REPO/actions"
fi
echo ""

# 2. 等待构建完成（轮询最多 10 分钟）
echo "=== [2/4] 等待 Actions 构建完成 ==="
WAIT_MAX=600
WAIT_INTERVAL=30
elapsed=0
while [ $elapsed -lt $WAIT_MAX ]; do
  STATUS=$(curl -sf "https://api.github.com/repos/$REPO/actions/workflows/build.yml/runs?branch=main&per_page=1" \
    | python3 -c "import sys,json; r=json.load(sys.stdin); print(r['workflow_runs'][0]['status'] if r['workflow_runs'] else 'unknown')" 2>/dev/null || echo "unknown")
  if [ "$STATUS" = "completed" ]; then
    CONCLUSION=$(curl -sf "https://api.github.com/repos/$REPO/actions/workflows/build.yml/runs?branch=main&per_page=1" \
      | python3 -c "import sys,json; r=json.load(sys.stdin); print(r['workflow_runs'][0]['conclusion'] if r['workflow_runs'] else 'unknown')" 2>/dev/null || echo "unknown")
    if [ "$CONCLUSION" = "success" ]; then
      echo "  ✅ Actions 构建成功"
    else
      echo "  ❌ Actions 构建失败 (conclusion=$CONCLUSION)"
      echo "  查看: https://github.com/$REPO/actions"
      exit 1
    fi
    break
  elif [ "$STATUS" = "in_progress" ] || [ "$STATUS" = "queued" ]; then
    echo "  构建中... (${elapsed}s)"
  else
    echo "  ⏳ 状态: $STATUS (${elapsed}s)"
  fi
  sleep $WAIT_INTERVAL
  elapsed=$((elapsed + WAIT_INTERVAL))
done
if [ $elapsed -ge $WAIT_MAX ]; then
  echo "  ⏰ 等待超时，请手动检查 Actions 进度"
  echo "  https://github.com/$REPO/actions"
  echo "  完成后手动下载:"
  echo "    curl -L -o app-debug.apk $APP_URL"
  echo "    curl -L -o xposed-debug.apk $XPOSED_URL"
  exit 1
fi
echo ""

# 3. 下载 APK
echo "=== [3/4] 下载 APK ==="
mkdir -p "$OUT_DIR" "$XPOSED_OUT_DIR"

echo "  下载 app-debug.apk..."
curl -fL --retry 3 --progress-bar -o "$OUT_DIR/app-debug.apk" "$APP_URL"
echo "    ✅ $(ls -lh "$OUT_DIR/app-debug.apk" | awk '{print $5}')"

echo "  下载 xposed-debug.apk..."
curl -fL --retry 3 --progress-bar -o "$XPOSED_OUT_DIR/xposed-debug.apk" "$XPOSED_URL"
echo "    ✅ $(ls -lh "$XPOSED_OUT_DIR/xposed-debug.apk" | awk '{print $5}')"
echo ""

# 4. 安装
echo "=== [4/4] 安装到设备 ==="
echo "  安装 app-debug.apk..."
adb install -r "$OUT_DIR/app-debug.apk" 2>/dev/null && echo "    ✅ app 安装成功" || echo "    ⚠️ adb 不可用，手动安装: adb install -r $OUT_DIR/app-debug.apk"

echo "  安装 xposed-debug.apk..."
adb install -r "$XPOSED_OUT_DIR/xposed-debug.apk" 2>/dev/null && echo "    ✅ xposed 安装成功" || echo "    ⚠️ adb 不可用，手动安装: adb install -r $XPOSED_OUT_DIR/xposed-debug.apk"

echo ""
echo "═══════════════════════════════════════════"
echo "  完成！安装后请重启微信以触发 KeyCaptureHook"
echo "═══════════════════════════════════════════"
