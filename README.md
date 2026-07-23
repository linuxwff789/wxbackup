# wxhook

> 微信聊天记录实时读取、备份与数据库管理工具（需 Root + LSPosed）

[![Android CI](https://github.com/linuxwff789/wxbackup/actions/workflows/build.yml/badge.svg)](https://github.com/linuxwff789/wxbackup/actions)
[![nightly release](https://img.shields.io/github/v/release/linuxwff789/wxbackup?include_prereleases&label=nightly)](https://github.com/linuxwff789/wxbackup/releases/tag/nightly)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

---

## 📋 目录

- [功能总览](#-功能总览)
- [前提条件](#-前提条件)
- [快速开始](#-快速开始)
- [项目架构](#-项目架构)
- [备份功能详解](#-备份功能详解)
- [数据库解密](#-数据库解密)
- [开发指南](#-开发指南)
- [常见问题](#-常见问题)

---

## ✨ 功能总览

### Xposed 模块（运行在微信进程内）
| 功能 | 说明 |
|------|------|
| 🔑 **密钥捕获** | `KeyCaptureHook` — 自动捕获微信 SQLCipher 密钥并写入 `/data/local/tmp/.wechat_key` |
| 💬 **消息拦截** | `MessageHook` — 实时拦截微信消息并写入本地 SQLite 数据库 |
| 🔇 **防撤回** | `AntiRecallHook` — 拦截撤回指令，阻止消息被撤回 |
| 💾 **备份钩子** | `BackupHook` — 运行时注入备份逻辑 |
| 🔄 **保活** | `KeepAliveHook` — 防止模块被系统回收 |
| ⚙️ **设置入口** | `SettingsEntryHook` — 在微信设置中注入模块入口 |

### 管理 App（独立应用）
| 功能 | 说明 |
|------|------|
| 📊 **状态检测** | 一键检测 Root、Xposed、微信进程、密钥等环境 |
| 💾 **数据库备份** | 全量备份（DB + 附件）+ 增量备份（仅新消息） |
| 🔄 **从备份恢复** | 从 `.tar.zst` 备份包重建微信数据库并直接替换 |
| ☁️ **云同步** | 支持 WebDAV、阿里云盘、S3 兼容存储 |
| 📋 **聊天记录浏览** | 按联系人查看聊天记录，支持全文搜索 |
| 🧩 **数据合并** | 合并多个备份时间段的数据 |
| ⏰ **定时备份/同步** | 设置自动备份和自动同步时间 |

---

## 🔧 前提条件

| 要求 | 说明 |
|------|------|
| 📱 **设备** | Android 12+（推荐），最低 Android 8.0 |
| 🔓 **Root** | Magisk 24+（需开启 Zygisk） |
| 🔌 **LSPosed** | LSPosed v1.9+（Zygisk 模式） |
| 💬 **微信** | 微信 8.0.74（其他版本可能需适配） |
| 🔑 **密钥** | `e9cd2ae`（7 字节，由模块自动捕获） |
| 🛢️ **SQLCipher** | 参数：`cipher_compatibility=3, page_size=1024, kdf_iter=4000, hmac=OFF` |

---

## 🚀 快速开始

### 1. 安装模块与 App

从 [nightly release](https://github.com/linuxwff789/wxbackup/releases/tag/nightly) 下载最新 APK：

```bash
# 主 App
curl -fL -o wxhook.apk \
  https://github.com/linuxwff789/wxbackup/releases/download/nightly/app-debug.apk

# Xposed 模块
curl -fL -o wxhook-xposed.apk \
  https://github.com/linuxwff789/wxbackup/releases/download/nightly/xposed-debug.apk
```

- 安装 `wxhook.apk`（管理 App）
- 安装 `wxhook-xposed.apk`（Xposed 模块）
- 在 LSPosed 中启用「wxhook」模块，作用域勾选「微信」
- **重启微信**（或重启设备）

### 2. 验证环境

打开 wxhook App → 点击 **「🔍 检测环境」**，确认所有项目均为 ✅：

```
✅ Root: 正常 (uid=0)
✅ Xposed 模块: 已安装
✅ LSPosed: 模块已注册
✅ Xposed Hook: 已加载
✅ 微信: 运行中 (pid=...)
✅ 密钥: key=e9cd2ae
```

### 3. 执行备份

- **全量备份**：备份完整数据库 + 所有附件（图片、语音、视频等），生成 `.tar.zst` 压缩包
- **增量备份**：只备份上次全量以来的新增消息和文件，速度更快

### 4. 使用项目脚本

```bash
# 推送代码到 GitHub（自动触发 CI 构建 APK）
./push.sh "feat: 我的改动"

# 从 GitHub nightly release 下载并安装最新 APK
./install.sh

# 一键推送 + 等待构建 + 安装
./build.sh "feat: 我的改动"
```

---

## 🏗 项目架构

```
wxbackup/
├── app/                          # 管理 App（主应用）
│   ├── src/main/java/com/nous/wxhook/
│   │   ├── App.kt                # Application 入口
│   │   ├── MainActivity.kt       # 主页
│   │   ├── backup/                # 备份引擎
│   │   │   ├── BackupOrchestrator.kt  # 备份/恢复编排（核心）
│   │   │   ├── ArchiveService.kt      # 数据库解密与归档
│   │   │   ├── BackupManifest.kt      # 状态与记录持久化
│   │   │   ├── FileManifest.kt        # 附件清单管理
│   │   │   ├── NativeArchive.kt       # JNI 原生归档接口
│   │   │   ├── BackupEnv.kt           # 备份环境配置
│   │   │   └── WeChatSourceResolver.kt # 微信数据目录发现
│   │   ├── db/                    # 数据库操作
│   │   │   ├── WeChatDbDecryptor.kt   # 微信 DB 解密
│   │   │   ├── MessageParser.kt       # 消息解析
│   │   │   ├── MergeEngine.kt         # 数据合并引擎
│   │   │   └── BackupManager.kt       # 备份管理工具
│   │   ├── root/                  # Root 权限封装
│   │   │   ├── RootGateways.kt        # 统一 Root 执行入口
│   │   │   └── libsu/                 # libsu 守护进程
│   │   ├── rootbridge/             # 备份桥接层
│   │   │   └── BackupHookLocal.kt     # 备份 API 入口
│   │   ├── service/               # Android 前台服务
│   │   │   ├── BackupService.kt       # 备份服务
│   │   │   └── SyncService.kt         # 同步服务
│   │   ├── sync/                  # 云同步
│   │   │   ├── Syncer.kt              # 同步引擎
│   │   │   ├── WebDavClient.kt        # WebDAV 客户端
│   │   │   └── OpenListCloudClient.kt # 阿里云盘客户端
│   │   ├── ui/                    # 用户界面
│   │   │   ├── module/                # 备份管理界面
│   │   │   ├── chatlist/              # 聊天列表
│   │   │   ├── chatdetail/            # 聊天详情
│   │   │   ├── search/                # 搜索
│   │   │   ├── settings/              # 设置
│   │   │   └── cloud/                 # 云存储配置
│   │   └── storage/               # 路径常量
│   └── build.gradle.kts
├── xposed/                       # Xposed 模块
│   ├── src/main/java/com/nous/wxhook/xposed/
│   │   ├── WeChatHookEntry.kt        # Xposed 入口
│   │   └── hook/
│   │       ├── KeyCaptureHook.kt     # 密钥捕获
│   │       ├── MessageHook.kt        # 消息拦截
│   │       ├── AntiRecallHook.kt     # 防撤回
│   │       ├── BackupHook.kt         # 备份 Hook
│   │       ├── KeepAliveHook.kt      # 保活
│   │       └── SettingsEntryHook.kt  # 设置入口
│   └── build.gradle.kts
├── scripts/                      # 辅助脚本
├── .github/workflows/            # CI 工作流
└── docs/                         # 文档
```

详见 [docs/architecture.md](docs/architecture.md)

---

## 💾 备份功能详解

### 备份格式

备份包使用 `.tar.zst` 格式（zstd 压缩的 tar 归档）：

```
wxbackup_full_20250721_120000.tar.zst  # 全量备份
incr_attachments_20250721_130000.tar.zst  # 增量备份（仅附件）
```

### 归档内部结构

```
{userHash}/
├── EnMicroMsg_baseline.sql      # DB 全量 SQL 导出
├── incr_100_to_200.sql          # 增量 SQL（可选，增量包）
├── db_config.json               # 数据库密码配置
├── db_state.json                # 备份状态
├── file_manifest.json           # 附件清单
├── image2/                      # 图片附件
├── voice2/                      # 语音附件
├── video/                       # 视频附件
├── emoji/                       # 表情
├── avatar/                      # 头像
├── cdn/                         # CDN 缓存
├── record/                      # 录制文件
└── favorite/                    # 收藏
```

### 恢复流程

1. **扫描备份** — 查找全量 `.tar.zst` 包
2. **解析元数据** — 从归档中提取 `db_config.json` 获取密码
3. **环境准备** — 停止微信，备份当前数据库
4. **重建数据库** — 解压 SQL 并用 `sqlcipher` 重建加密 DB
5. **恢复附件** — 从全量和增量归档中提取附件目录
6. **写入并修复权限** — 复制 DB 到微信目录，设置 uid/gid 和权限
7. **清理** — 删除临时工作目录

详细用法见 [docs/backup-restore.md](docs/backup-restore.md)

---

## 🔑 数据库解密

若需在外部工具（如 DB Browser for SQLite）中查看备份的数据库：

```sql
PRAGMA key = 'e9cd2ae';
PRAGMA cipher_compatibility = 3;
PRAGMA cipher_page_size = 1024;
PRAGMA kdf_iter = 4000;
PRAGMA cipher_use_hmac = OFF;
```

也可以使用内置的 SQLCipher 命令行工具：

```bash
LD_PRELOAD='/data/local/tmp/wxhook_bin/libz.so.1:/data/local/tmp/wxhook_bin/libcrypto.so.3:/data/local/tmp/wxhook_bin/libedit.so:/data/local/tmp/wxhook_bin/libncursesw.so.6' \
  /data/local/tmp/wxhook_bin/sqlcipher /path/to/EnMicroMsg.db
```

然后在 sqlcipher 提示符中输入上述 PRAGMA 命令。

---

## 👨‍💻 开发指南

### 本地构建

项目使用 GitHub Actions 自动构建，无需本地搭建 Android SDK。但若需本地构建：

```bash
# 需要 Android SDK 35 + NDK 27
./gradlew assembleDebug
```

### 推送与 CI

```bash
git add .
git commit -m "feat: 我的改动"
git push origin main
```

推送后 GitHub Actions 自动：
1. 编译 `app`（主应用）和 `xposed`（Xposed 模块）
2. 上传 APK 为 Artifact
3. 发布到 nightly release

详见 [docs/development.md](docs/development.md)

### 项目脚本

| 脚本 | 功能 |
|------|------|
| `push.sh` | 推送代码到 GitHub |
| `install.sh` | 从 nightly release 下载最新 APK 并安装 |
| `build.sh` | `push.sh` + 等待 CI 完成 + `install.sh` 一站式操作 |
| `clean.sh` | 清理构建产物 |

---

## ❓ 常见问题

### Q: 密钥未捕获怎么办？
确保 LSPosed 模块已启用且作用域包含微信，重启微信后查看 `/data/local/tmp/.wechat_key` 文件是否存在。

### Q: 备份失败："微信未运行或未找到数据"
备份前请确保微信已登录并运行过至少一次。

### Q: 恢复后微信打不开
恢复功能会自动备份当前数据库到 `backupdata/restore_before/` 目录。如果恢复后出现问题，可以手动将备份文件复制回微信数据目录：
```bash
cp /sdcard/Download/wxhook_backup/restore_before/EnMicroMsg.db.restore_before \
  /data/data/com.tencent.mm/MicroMsg/{userHash}/EnMicroMsg.db
chown u0_a620:u0_a620 /data/data/com.tencent.mm/MicroMsg/{userHash}/EnMicroMsg.db
chmod 660 /data/data/com.tencent.mm/MicroMsg/{userHash}/EnMicroMsg.db
```

### Q: 如何查看运行日志？
在 App 中展开「📝 运行日志」查看实时日志，日志也保存在 `/sdcard/Download/wxhook_backup/backup_live.log`。

### Q: 增量备份报错 "无基线数据"
增量备份依赖全量备份的状态信息，请先执行一次全量备份。

---

## 📄 许可证

[MIT License](LICENSE)
