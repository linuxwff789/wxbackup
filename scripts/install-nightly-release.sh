#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

repo='linuxwff789/wxhook'
out="$HOME/wxbackup-nightly.apk"
url="https://github.com/$repo/releases/download/nightly/app-debug.apk"

tmp="$(mktemp "$HOME/.cache/wxbackup-release-XXXXXX.apk")"
trap 'rm -f "$tmp"' EXIT
curl -fL --retry 3 --retry-all-errors --progress-bar "$url" -o "$tmp"
unzip -tqq "$tmp"
mv "$tmp" "$out"
su -c "pm install -r '$out'"
echo "Installed: $out"
