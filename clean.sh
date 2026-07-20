#!/bin/bash
set -e

PROJECT="/data/data/com.termux/files/home/wxbackup"

echo "=== 清理本地构建产物 ==="
rm -rf "$PROJECT/app/build" \
       "$PROJECT/xposed/build" \
       "$PROJECT/build" \
       "$PROJECT/.gradle/8.7"

rm -f /data/data/com.termux/files/home/wxbackup-debug.apk
rm -f /data/data/com.termux/files/home/wxbackup-xposed-debug.apk

echo "✅ 清理完成"
echo ""
echo "下次执行 build.sh 或 git push 后会通过 GitHub Actions 重新构建"
