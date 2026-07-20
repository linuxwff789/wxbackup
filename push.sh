#!/bin/bash
set -e

MSG="${1:-chore: update}"
PROJECT="/data/data/com.termux/files/home/wxbackup"

echo "═══════════════════════════════════════════"
echo "  推送代码 → GitHub Actions 自动构建"
echo "═══════════════════════════════════════════"

cd "$PROJECT"
git add -A

if git diff --cached --quiet; then
  echo "  无改动"
  exit 0
fi

git commit -m "$MSG"
git push

echo ""
echo "✅ 推送完成"
echo "📦 GitHub Actions: https://github.com/linuxwff789/wxbackup/actions"
echo "📥 安装: bash install.sh"
echo ""
