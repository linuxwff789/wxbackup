#!/bin/bash
set -e

REMOTE="administrator@tcp.sealosbja.site"
SSH_OPTS="-i ~/.ssh/id_ed25519_hermes_wsl -p 47533"
SSH="ssh $SSH_OPTS $REMOTE"
RSYNC="rsync -avz --exclude='.git' --exclude='build' --exclude='.gradle' -e ssh $SSH_OPTS"
LOCAL_APK="/data/data/com.termux/files/home/wxbackup-debug.apk"
LOCAL_XPOSED_APK="/data/data/com.termux/files/home/wxbackup-xposed-debug.apk"
PROJECT="/data/data/com.termux/files/home/wxbackup"

echo "=== [1/4] 同步代码到远端 ==="
$RSYNC $PROJECT/ $REMOTE:/home/administrator/wxbackup/

echo "=== [2/4] 远端编译 ==="
$SSH "cd /home/administrator/wxbackup && ./gradlew :app:assembleDebug :xposed:assembleDebug --no-daemon --console=plain"

echo "=== [3/4] 拉回 APK ==="
scp $SSH_OPTS \
  $REMOTE:/home/administrator/wxbackup/app/build/outputs/apk/debug/app-debug.apk \
  $LOCAL_APK
scp $SSH_OPTS \
  $REMOTE:/home/administrator/wxbackup/xposed/build/outputs/apk/debug/xposed-debug.apk \
  $LOCAL_XPOSED_APK

echo "=== [4/4] 构建完成 ==="
ls -lh $LOCAL_APK $LOCAL_XPOSED_APK
