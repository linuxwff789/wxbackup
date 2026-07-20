# wxhook

微信聊天记录实时读取与数据库管理工具。

## 架构

- **xposed/** — XP 模块（运行在微信进程内）
  - 密钥捕获（KeyCaptureHook → setCipherKey hook）
  - 消息拦截（MessageHook → messenger.foundation hook）
  - 防撤回（AntiRecallHook）
  - 备份 Hook（BackupHook）

- **app/** — 配套管理 App
  - 状态检测
  - 聊天记录浏览
  - 搜索查询
  - 数据库备份（全量/增量）
  - 数据合并
  - 云同步（WebDAV / 阿里云盘 / S3）

## 前提

- 设备已 root（Magisk + LSPosed）
- 微信 8.0.74
- 密钥：`e9cd2ae`（7 字节）
- SQLCipher 参数：`cipher_compatibility=3, page_size=1024, kdf_iter=4000, hmac=OFF`

## 构建

项目使用 **GitHub Actions** 自动构建，无需本地 Android SDK。

```bash
# 1. 推送代码 → 触发 Actions 构建
./push.sh "feat: 我的改动"

# 2. 下载并安装自动构建的 APK
./install.sh

# 或一键完成
./build.sh "feat: 我的改动"
```

Actions 工作流程:
1. `git push` 到 `main` 分支
2. GitHub Actions 自动拉取代码，JDK 17 + SDK 35 + NDK 编译
3. 产物发布到 [nightly release](https://github.com/linuxwff789/wxbackup/releases/tag/nightly)
4. 本地 `install.sh` 从 nightly 下载并安装

查看构建状态: https://github.com/linuxwff789/wxbackup/actions

### 手动下载 APK

```bash
# 主 App
curl -fL -o wxhook.apk \
  https://github.com/linuxwff789/wxbackup/releases/download/nightly/app-debug.apk

# Xposed 模块
curl -fL -o wxhook-xposed.apk \
  https://github.com/linuxwff789/wxbackup/releases/download/nightly/xposed-debug.apk
```

## 数据库解密

```sql
PRAGMA key = 'e9cd2ae';
PRAGMA cipher_compatibility = 3;
PRAGMA cipher_page_size = 1024;
PRAGMA kdf_iter = 4000;
PRAGMA cipher_use_hmac = OFF;
```

## UI 设计

Material Design 3 (Material You) — 支持 Android 12+ 动态取色和深色模式。
