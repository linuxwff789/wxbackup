#!/bin/bash
set -e

REMOTE="administrator@tcp.sealosbja.site"
SSH_OPTS="-i ~/.ssh/id_ed25519_hermes_wsl -p 47533"
SSH="ssh $SSH_OPTS $REMOTE"
PROJECT="/data/data/com.termux/files/home/wxbackup"

echo "=== 清理本地构建 ==="
rm -rf "$PROJECT/app/build" "$PROJECT/xposed/build" "$PROJECT/build" "$PROJECT/.gradle"
rm -f /data/data/com.termux/files/home/wxbackup-debug.apk
rm -f /data/data/com.termux/files/home/wxbackup-xposed-debug.apk
echo "✅ 本地清理完成"

echo "=== 清理远端构建 ==="
$SSH "rm -rf /home/administrator/wxbackup/app/build /home/administrator/wxbackup/xposed/build /home/administrator/wxbackup/build /home/administrator/wxbackup/.gradle" 2>/dev/null && echo "✅ 远端清理完成" || echo "⚠️ 远端清理跳过(可能未同步过)"
