#!/bin/bash
set -e

TOKEN=$(cat ~/.git-credentials | sed -n 's#https://[^:]*:\([^@]*\)@github.com#\1#p')
REPO="linuxwff789/wxbackup"
KS="/data/data/com.termux/files/home/wxbackup/release.keystore"

if [ ! -f "$KS" ]; then
  echo "❌ $KS 不存在"
  exit 1
fi

KS_B64=$(base64 -w0 "$KS")

echo "=== 设置 GitHub Secrets ==="

set_secret() {
  local name=$1 val=$2
  curl -s -X PUT -H "Authorization: token $TOKEN" -H "Content-Type: application/json" \
    "https://api.github.com/repos/$REPO/actions/secrets/$name" \
    -d "{\"encrypted_value\":\"$val\"}" > /dev/null
  echo "✅ $name"
}

# 先用 GitHub API 获取 public key
PUB_KEY=$(curl -s -H "Authorization: token $TOKEN" \
  "https://api.github.com/repos/$REPO/actions/secrets/public-key")
KEY_ID=$(echo "$PUB_KEY" | python3 -c "import sys,json;print(json.load(sys.stdin)['key_id'])")
PUB_KEY_VAL=$(echo "$PUB_KEY" | python3 -c "import sys,json;print(json.load(sys.stdin)['key'])")

# 用 openssl 加密 (需要 github-lock-box 或 libsodium)
# 简化方案：直接用明文 secret（personal repo 够用）
# 但 GitHub API 要求 encrypted_value，所以用 sodium

# 检查是否有 python3 libsodium
python3 -c "import nacl" 2>/dev/null && HAS_NACL=1 || HAS_NACL=0

if [ "$HAS_NACL" = "1" ]; then
  python3 -c "
import nacl.public, nacl.encoding, base64, json, sys, urllib.request

pub_key_b64 = '$PUB_KEY_VAL'
pub_key = nacl.public.PublicKey(pub_key_b64.encode(), nacl.encoding.Base64Encoder)
seal_box = nacl.public.Seal(pub_key)

secrets = {
    'KEYSTORE_BASE64': '''$KS_B64''',
    'KEYSTORE_PASSWORD': 'wxhook123',
    'KEY_ALIAS': 'wxhook',
    'KEY_PASSWORD': 'wxhook123',
}

token = '$TOKEN'
repo = '$REPO'

for name, val in secrets.items():
    encrypted = seal_box.encrypt(val.encode())
    b64_encrypted = base64.b64encode(encrypted).decode()
    data = json.dumps({'encrypted_value': b64_encrypted}).encode()
    req = urllib.request.Request(
        f'https://api.github.com/repos/{repo}/actions/secrets/{name}',
        data=data,
        headers={'Authorization': f'token {token}', 'Content-Type': 'application/json'},
        method='PUT'
    )
    resp = urllib.request.urlopen(req)
    print(f'✅ {name}')
"
else
  echo "⚠️ 需要 pynacl: pip install pynacl"
  echo "   或手动在 GitHub Settings → Secrets 添加:"
  echo "   KEYSTORE_BASE64=$(echo $KS_B64 | head -c 40)..."
  echo "   KEYSTORE_PASSWORD=wxhook123"
  echo "   KEY_ALIAS=wxhook"
  echo "   KEY_PASSWORD=wxhook123"
fi
