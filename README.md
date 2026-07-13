# wxhook

微信聊天记录实时读取与数据库管理工具。

## 架构

- **xposed/** — XP 模块（运行在微信进程内）
  - 密钥捕获（setCipherKey hook）
  - 消息拦截（messenger.foundation hook）
  - 防撤回
  
- **app/** — 配套管理 App
  - 状态检测
  - 聊天记录浏览
  - 搜索查询
  - 数据库备份
  - 数据合并

## 前提

- 设备已 root（Magisk + LSPosed）
- 微信 8.0.74
- 密钥：`e9cd2ae`（7 字节）
- SQLCipher 参数：`cipher_compatibility=3, page_size=1024, kdf_iter=4000, hmac=OFF`

## 构建

```bash
# 远端编译（WSL2）
./build.sh
```

## 数据库解密

```sql
PRAGMA key = 'e9cd2ae';
PRAGMA cipher_compatibility = 3;
PRAGMA cipher_page_size = 1024;
PRAGMA kdf_iter = 4000;
PRAGMA cipher_use_hmac = OFF;
```
