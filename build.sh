#!/bin/bash
set -e

REMOTE="administrator@tcp.sealosbja.site"
SSH_OPTS="-i ~/.ssh/id_ed25519_hermes_wsl -p 47533"
SSH="ssh $SSH_OPTS $REMOTE"
RSYNC="rsync -avz --exclude='.git' --exclude='build' --exclude='.gradle' -e ssh $SSH_OPTS"
LOCAL_APK="/data/data/com.termux/files/home/wxhook-debug.apk"
PROJECT="/data/data/com.termux/files/home/wxhook"

echo "=== [1/4] 同步代码到远端 ==="
$RSYNC $PROJECT/ $REMOTE:/home/administrator/wxhook/

echo "=== [2/4] 远端编译 ==="
$SSH "cd /home/administrator/wxhook && ./gradlew :app:assembleDebug :xposed:assembleDebug --no-daemon --console=plain"

echo "=== [3/4] 拉回 APK ==="
scp $SSH_OPTS \
  $REMOTE:/home/administrator/wxhook/app/build/outputs/apk/debug/app-debug.apk \
  $LOCAL_APK

echo "=== [4/4] 构建完成 ==="
ls -la $LOCAL_APK
echo "APK: $LOCAL_APK"
