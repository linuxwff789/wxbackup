# 架构文档

## 整体架构

```
┌─────────────────────────────────────────────────────────────┐
│                     LSPosed Framework                        │
│  ┌──────────────────────────────────────────────────────┐   │
│  │              Xposed 模块 (xposed/)                    │   │
│  │  ┌──────────┐  ┌──────────┐  ┌────────────────┐     │   │
│  │  │KeyCapture│  │ Message  │  │  BackupHook    │     │   │
│  │  │ Hook     │  │ Hook     │  │  AntiRecallHook │     │   │
│  │  └────┬─────┘  └────┬─────┘  └───────┬────────┘     │   │
│  │       │              │               │               │   │
│  │       ▼              ▼               ▼               │   │
│  │  ┌─────────────────────────────────────┐             │   │
│  │  │      微信进程 (com.tencent.mm)       │             │   │
│  │  │  ┌──────────┐  ┌──────────────────┐ │             │   │
│  │  │  │ 密钥文件  │  │  SQLCipher DB   │ │             │   │
│  │  │  │/data/... │  │ EnMicroMsg.db   │ │             │   │
│  │  │  └──────────┘  └──────────────────┘ │             │   │
│  │  └─────────────────────────────────────┘             │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘

                     │ Root 权限 (su)
                     ▼
┌─────────────────────────────────────────────────────────────┐
│                  管理 App (app/)                             │
│                                                             │
│  ┌──────────────────────────────────────────────────────┐  │
│  │               UI 层 (ui/)                             │  │
│  │  ModuleActivity  ChatList  ChatDetail  Settings       │  │
│  │  SearchActivity  CloudConfig  BackupActivity  Status  │  │
│  └──────────────────────┬───────────────────────────────┘  │
│                         │                                   │
│  ┌──────────────────────▼───────────────────────────────┐  │
│  │             ViewModel + Service 层                    │  │
│  │  ModuleViewModel  │  BackupService  │  SyncService   │  │
│  │  SettingsViewModel│  DecryptService │  BackupPackage │  │
│  └──────────────────────┬───────────────────────────────┘  │
│                         │                                   │
│  ┌──────────────────────▼───────────────────────────────┐  │
│  │               Root 桥接层                             │  │
│  │  BackupHookLocal (API入口)  │  RootGateways (统一入口) │  │
│  │  RootGatewayImpl           │  RootManager (libsu)     │  │
│  │  WxRootBinder              │  WxRootService           │  │
│  └──────────────────────┬───────────────────────────────┘  │
│                         │                                   │
│  ┌──────────────────────▼───────────────────────────────┐  │
│  │               备份引擎 (backup/)                      │  │
│  │  BackupOrchestrator (协调器)                          │  │
│  │    ├─ doFullBackup()     ── 全量备份                  │  │
│  │    ├─ doIncrementalBackup() ── 增量备份               │  │
│  │    ├─ doRestore()        ── 从备份恢复                │  │
│  │    └─ rebuildDbState()   ── 重建备份链状态            │  │
│  │                                                       │  │
│  │  ArchiveService    ── 数据库解密/归档                 │  │
│  │  BackupManifest    ── 状态与记录持久化                │  │
│  │  FileManifest      ── 附件清单管理                   │  │
│  │  WeChatSourceResolver ── 微信目录发现                 │  │
│  │  TargetAppController  ── 微信进程生命周期             │  │
│  │  NativeArchive     ── JNI 归档 (tar/zstd)            │  │
│  └──────────────────────┬───────────────────────────────┘  │
│                         │                                   │
│  ┌──────────────────────▼───────────────────────────────┐  │
│  │              云同步 (sync/)                           │  │
│  │  Syncer  WebDavClient  OpenListCloudClient           │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                             │
│  ┌──────────────────────┐  ┌────────────────────────┐      │
│  │  数据库 (db/)        │  │  存储 (storage/)       │      │
│  │  WeChatDbDecryptor   │  │  WxHookPaths           │      │
│  │  MessageParser       │  │  StorageCapability     │      │
│  │  MergeEngine         │  │                        │      │
│  │  BackupManager       │  │                        │      │
│  └──────────────────────┘  └────────────────────────┘      │
└─────────────────────────────────────────────────────────────┘
```

## 核心模块详解

### 1️⃣ Xposed 模块 (`xposed/`)

Xposed 模块注入到微信进程，通过 Hook 微信内部方法实现功能。

| Hook | 目标方法 | 功能 |
|------|----------|------|
| `KeyCaptureHook` | `setCipherKey` (SQLCipher) | 捕获数据库加密密钥，写入 `/data/local/tmp/.wechat_key` |
| `MessageHook` | `messenger.foundation` (消息协议) | 拦截新消息（文本、图片、语音等）并写入本地 SQLite |
| `AntiRecallHook` | `RevokeMsgListener` | 阻止撤回消息 |
| `BackupHook` | `backupAndRestore` | 注入备份/恢复生命周期钩子 |
| `KeepAliveHook` | `ActivityThread` | 防止模块被系统卸载 |
| `SettingsEntryHook` | `MoreTab` (微信设置) | 在微信设置页面添加模块入口 |

### 2️⃣ 备份引擎 (`backup/`)

#### BackupOrchestrator

核心协调器，管理备份/恢复的完整流程：

- **全量备份**：停止微信 → 扫描数据 → dump SQL → 扫描附件 → 打包 `.tar.zst` → 上传云盘
- **增量备份**：对比上次状态 → 导出增量 SQL → 增量附件差量拷贝 → 打包 → 上传
- **从备份恢复**：7 阶段流程（扫描 → 元数据 → 环境准备 → DB 恢复 → 附件恢复 → 写回 → 清理）
- **重建 DB 状态**：分析全量/增量归档链 → 计算最长一致性链 → 恢复各用户状态

#### ArchiveService

处理微信加密数据库的导出/导入，使用 `sqlcipher` 命令行工具：

- `decryptAndDump(path)` → 解密并导出 SQL
- `decryptIncremental(path, lastRowId)` → 导出增量 SQL
- `getDbPassword()` → 获取数据库密码

#### BackupManifest

持久化备份状态和记录：

- `saveDbState()` / `loadDbState()` — 每个用户的备份进度
- `saveState()` / `loadState()` — 全局备份状态
- `addRecord()` / `writeSortedRecords()` — 备份历史记录
- `saveDbConfig()` — 数据库密码配置

### 3️⃣ Root 权限网关 (`root/`)

```
RootGateways (单例入口)
  ├─ run()          # 执行 su 命令
  ├─ runQuiet()     # 静默执行
  ├─ writeFile()    # 写入文件 (root)
  ├─ copy()         # 复制文件
  ├─ delete()       # 删除文件
  ├─ exists()       # 检查文件存在
  └─ mkdirs()       # 创建目录

  └─ gateway: RootGateway
       └─ RootGatewayImpl (libsu 实现)
            ├─ WxRootBinder (Binder IPC)
            └─ WxRootService (前台服务保持 Root)
```

### 4️⃣ 云同步 (`sync/`)

支持三种云存储后端：

| 后端 | 类 | 认证方式 |
|------|-----|----------|
| WebDAV | `WebDavClient` | 用户名 + 密码 |
| 阿里云盘 | `OpenListCloudClient` | Refresh Token + OpenList API |
| S3 兼容 | 通过 OpenList | API Key |

`Syncer` 负责协调同步流程：扫描本地备份 → 上传新文件 → 清理远程过期文件。

### 5️⃣ 数据流

```
微信进程                 管理 App                 云存储
  │                       │                       │
  │──(Hook)──▶ 消息 DB ──▶ 读取/解密              │
  │                       │                       │
  │──(KeyCapture)──▶ 密钥 ──▶ 备份引擎             │
  │                       │                       │
  │──(BackupHook)──▶ 停止微信                      │
  │                       │                       │
  │                       ├── dump SQL (sqlcipher) │
  │                       ├── 扫描附件             │
  │                       ├── tar.zst打包          │
  │                       ├── 上传 ──────────────▶  │
  │                       │                       │
  │◀── (恢复) ────────────┤                       │
  │   解压 ← tar.zst ◀────┤                       │
  │   sqlcipher 重建 DB ◀─┘                       │
  │   附件还原 ◀──────────────────┘               │
```

## 目录结构

```
app/src/main/java/com/nous/wxhook/
├── App.kt                    # Application，模块初始化
├── MainActivity.kt           # 入口 Activity
│
├── backup/                   # 备份引擎
│   ├── BackupOrchestrator.kt # 备份/恢复编排
│   ├── ArchiveService.kt     # 数据库解密归档
│   ├── BackupManifest.kt     # 状态记录
│   ├── FileManifest.kt       # 附件清单
│   ├── FullBackupLayout.kt   # 全量备份布局
│   ├── NativeArchive.kt      # JNI 归档接口
│   ├── NativeArchivePlan.kt  # 归档打包计划
│   ├── SdcardOps.kt          # SD 卡操作
│   ├── TargetAppController.kt # 微信进程控制
│   ├── BackupEnv.kt          # 环境变量
│   └── WeChatSourceResolver.kt # 微信目录发现
│
├── core/command/             # Shell 命令工具
│   ├── CommandResult.kt
│   └── ShellEscaper.kt
│
├── data/local/               # Room 本地数据库
│   ├── WxHookDatabase.kt     # 数据库定义
│   ├── dao/Daos.kt           # 数据访问对象
│   └── entity/Entities.kt    # 实体类
│
├── database/                 # SQLCipher 执行器
│   └── SqlCipherExecutor.kt
│
├── db/                       # 微信数据库操作
│   ├── BackupManager.kt      # 备份管理
│   ├── DbCleanup.kt          # 数据库清理
│   ├── MergeEngine.kt        # 数据合并
│   ├── MessageParser.kt      # 消息解析
│   ├── WeChatDbDecryptor.kt  # 微信 DB 解密
│   └── WxHookProvider.kt     # ContentProvider
│
├── receiver/                 # BroadcastReceiver
│   ├── KeyReceiver.kt        # 密钥接收
│   ├── MessageReceiver.kt    # 消息接收
│   ├── RebuildReceiver.kt    # 重建广播
│   └── ScheduleReceiver.kt   # 定时广播
│
├── root/                     # Root 权限
│   ├── RootGateway.kt        # 网关接口
│   ├── RootGatewayImpl.kt    # 网关实现
│   ├── RootGateways.kt       # 统一入口
│   └── libsu/                # libsu 守护进程
│       ├── RootManager.kt
│       ├── WxRootBinder.kt
│       └── WxRootService.kt
│
├── rootbridge/               # Root 桥接
│   ├── RootCommandRunner.kt
│   └── backup/
│       └── BackupHookLocal.kt # 备份 API 入口
│
├── service/                  # Android 服务
│   ├── BackupPackage.kt      # 备份包信息
│   ├── BackupService.kt      # 备份前台服务
│   ├── DecryptService.kt     # 解密服务
│   └── SyncService.kt        # 同步服务
│
├── storage/                  # 存储路径
│   ├── StorageCapability.kt
│   └── WxHookPaths.kt
│
├── sync/                     # 云同步
│   ├── CloudClient.kt        # 客户端接口
│   ├── OpenListCloudClient.kt # 阿里云盘
│   ├── Syncer.kt             # 同步引擎
│   └── WebDavClient.kt       # WebDAV
│
├── ui/                       # 用户界面
│   ├── M3Components.kt       # Material3 组件
│   ├── backup/BackupActivity.kt
│   ├── chatdetail/ChatDetailActivity.kt
│   ├── chatlist/ChatListActivity.kt
│   ├── cloud/CloudConfigActivity.kt
│   ├── merge/MergeActivity.kt
│   ├── module/ModuleActivity.kt
│   ├── search/SearchActivity.kt
│   ├── settings/SettingsActivity.kt
│   ├── status/StatusActivity.kt
│   └── viewer/ImageViewerActivity.kt
│
├── util/
│   └── SetupManager.kt       # 启动初始化
│
└── worker/
    └── TaskState.kt          # 任务状态

xposed/src/main/java/com/nous/wxhook/xposed/
├── WeChatHookEntry.kt        # Xposed 入口
└── hook/
    ├── AntiRecallHook.kt     # 防撤回
    ├── BackupHook.kt         # 备份钩子
    ├── KeepAliveHook.kt      # 保活
    ├── KeyCaptureHook.kt     # 密钥捕获
    ├── MessageHook.kt        # 消息拦截
    └── SettingsEntryHook.kt  # 设置入口
```
