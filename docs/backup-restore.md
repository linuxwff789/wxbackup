# 备份与恢复指南

## 概述

wxhook 提供三种备份模式 + 一种恢复功能：

| 功能 | 说明 | 产物 |
|------|------|------|
| **全量备份** | 完整备份数据库 + 所有附件 | `wxbackup_full_*.tar.zst` |
| **增量备份** | 只备份新增消息和文件 | 增量 SQL + `incr_attachments_*.tar.zst` |
| **定时备份** | 按设定时间自动执行备份 | 同上 |
| **从备份恢复** | 从备份包重建微信数据库并替换 | - |

---

## 备份流程详解

### 全量备份

```
doFullBackup()
├── 1. 检测微信数据目录
│   └── WeChatSourceResolver.findWxPaths()
├── 2. 数据库基线
│   ├── ArchiveService.decryptAndDump() → EnMicroMsg_baseline.sql
│   └── 保存 DB 状态 (maxRowId)
├── 3. 扫描附件
│   ├── image2/ voice2/ video/ emoji/ avatar/ ...
│   └── FileManifest.scanWeChatAttachments()
├── 4. 保存配置与状态
│   ├── BackupManifest.saveDbConfig()
│   ├── BackupManifest.saveState()
│   └── BackupManifest.addRecord()
├── 5. 打包 tar.zst
│   ├── NativeArchivePlan 生成文件清单
│   ├── writeTarZstd() 写入归档
│   └── verifyTarZstd() 验证完整性
└── 6. 云同步 (可选)
    └── cloudSync()
```

### 增量备份

```
doIncrementalBackup()
├── 1. 读取上次状态 (lastMessageRowId)
├── 2. DB 增量
│   ├── ArchiveService.decryptIncremental(lastRowId)
│   └── 生成 incr_{from}_to_{to}.sql
├── 3. 附件增量
│   ├── 对比上次附件清单 (FileManifest.diff)
│   └── 复制新增/修改的文件
├── 4. 更新清单
├── 5. 打包增量归档
├── 6. 清理临时目录
└── 7. 云同步
```

---

## 恢复流程详解

### 执行恢复

恢复功能通过 App 中的 **「⬇️ 从备份恢复微信」** 按钮触发，或通过命令行调用 `BackupOrchestrator.doRestore()`。

### 7 阶段流程

```
Phase 1: 扫描备份
├── scanBackupArchives()
├── 查找 wxbackup_full_*.tar.zst 全量包
└── 按时间排序，取最新的

Phase 2: 解析元数据
├── parseMetadata()
├── 从归档读取 db_config.json → 获取密码
└── 获取 userHash

Phase 3: 环境准备
├── prepareEnvironment()
├── 停止微信 (am force-stop)
├── 备份当前数据库到 restore_before/
└── 创建临时工作目录

Phase 4: 重建数据库
├── restoreDatabase()
├── 解压 EnMicroMsg_baseline.sql
├── 应用增量 SQL (incr.sql)
├── sqlcipher 重建加密 DB
│   ├── PRAGMA key = '...'
│   ├── PRAGMA cipher_compatibility = 3
│   ├── .read baseline.sql
│   ├── .clone EnMicroMsg.db
│   └── .quit
└── 验证输出 DB

Phase 5: 恢复附件
├── restoreAttachments()
├── tar -I zstd -xf 解压全量包
├── 解压增量包
└── cp -r 附件目录 → 微信数据目录

Phase 6: 写回数据库与权限
├── finalizeDatabase()
├── 复制 EnMicroMsg.db → 微信数据目录
├── chmod 660
├── chown u0_a620:u0_a620
└── 复制 -wal / -shm (如存在)

Phase 7: 清理
└── cleanupWorkDir()
    └── rm -rf /data/local/tmp/wxhook_restore
```

### 密码获取顺序

```
1. 最新增量包的 db_config.json
2. 全量包的 db_config.json
3. 本地 ArchiveService.getDbPassword()
```

### 恢复后文件权限

```bash
# 数据库文件
chmod 660 /data/data/com.tencent.mm/MicroMsg/{hash}/EnMicroMsg.db
chown u0_a620:u0_a620 /data/data/com.tencent.mm/MicroMsg/{hash}/EnMicroMsg.db

# 附件目录
chown -R u0_a620:u0_a620 /data/data/com.tencent.mm/MicroMsg/{hash}/image2/
chown -R u0_a620:u0_a620 /data/data/com.tencent.mm/MicroMsg/{hash}/voice2/
# ... 其他目录同理
```

---

## 备份状态管理

### db_state.json

每个用户独立的备份进度：

```json
{
  "lastBackupTag": "20250721_120000",
  "lastBackupTime": 1721548800000,
  "lastMessageRowIdFrom": 0,
  "lastMessageRowId": 12345
}
```

### backup_records.json

备份历史记录列表（最多 50 条）：

```json
[
  {
    "tag": "20250721_120000",
    "type": "full",
    "time": 1721548800000,
    "fileCount": 1234,
    "totalSize": 104857600,
    "message": "全量备份完成",
    "compression": "zstd",
    "durationMs": 45000
  }
]
```

### remote_config.json

云同步配置：

```json
{
  "enabled": true,
  "remote": "wxhook-backup"
}
```

---

## 定时备份/同步

通过 App 设置定时备份和同步时间：

| 设置项 | 说明 | 默认 |
|--------|------|------|
| 定时备份开关 | 启用/禁用自动备份 | 关闭 |
| 备份时间 | 每天自动执行备份的时间 | 03:00 |
| 定时同步开关 | 启用/禁用自动云同步 | 关闭 |
| 同步时间 | 每天自动同步的时间 | 04:00 |

定时设置保存到 `settings_config.json`，由 `ScheduleReceiver` 触发。

---

## 数据恢复注意事项

### ✅ 恢复前建议
- 先做一次全量备份，确保有最新的完整归档
- 确认备份包中的密码与当前微信一致
- 保持设备电量充足（建议 > 50%）

### ⚠️ 风险提示
- 恢复操作会**替换**微信当前数据库
- 恢复前会自动备份当前数据库到 `restore_before/` 目录
- 恢复后需要重启微信才能生效

### 🔙 手动回滚

如果恢复后出现问题：

```bash
# 进入微信数据目录
cd /data/data/com.tencent.mm/MicroMsg/{userHash}

# 停止微信
am force-stop com.tencent.mm

# 从备份还原
cp /sdcard/Download/wxhook_backup/restore_before/EnMicroMsg.db.restore_before EnMicroMsg.db
chown u0_a620:u0_a620 EnMicroMsg.db
chmod 660 EnMicroMsg.db
```
