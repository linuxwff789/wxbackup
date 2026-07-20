#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

repo='linuxwff789/wxbackup'
url="https://github.com/$repo/releases/download/nightly/app-debug.apk"
out="$HOME/wxhook-nightly.apk"

echo "=== 下载 nightly APK ==="
echo "  来源: $url"

tmp="$(mktemp "$HOME/.cache/wxhook-release-XXXXXX.apk")"
trap 'rm -f "$tmp"' EXIT

curl -fL --retry 3 --retry-all-errors --progress-bar "$url" -o "$tmp"
unzip -tqq "$tmp"
mv "$tmp" "$out"

echo "  保存: $out ($(ls -lh "$out" | awk '{print $5}'))"
echo ""

echo "=== 安装 ==="
su -c "pm install -r '$out'" && echo "✅ 安装成功" || echo "❌ 安装失败"
