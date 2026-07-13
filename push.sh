#!/bin/bash
set -e

MSG="${1:-chore: update}"
cd /data/data/com.termux/files/home/wxbackup

echo "=== 提交并推送 ==="
git add -A
git diff --cached --quiet && echo "无改动" && exit 0
git commit -m "$MSG"
git push
echo "✅ 推送完成"
