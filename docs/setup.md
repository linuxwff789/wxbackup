# 安装与设置指南

## 环境要求

### 硬件要求
- Android 设备已 **Root**（推荐 Magisk 24+）
- 已安装 **LSPosed** 框架（Zygisk 模式）
- 已安装 **微信**（推荐 8.0.74）
- 建议 Android 12+ 以获得最佳 Material You 体验

### 前置检查

安装前请确认：
1. ✅ Magisk 正常运行（`su -c id` 返回 `uid=0`）
2. ✅ LSPosed 已启用
3. ✅ 微信已安装并登录
4. ✅ 存储权限已授予

---

## 安装步骤

### 方法一：从 GitHub nightly release 安装（推荐）

#### 1️⃣ 下载 APK

**在手机上打开浏览器**，访问：
```
https://github.com/linuxwff789/wxbackup/releases/tag/nightly
```

下载以下两个文件：
- `app-debug.apk` — 管理 App
- `xposed-debug.apk` — Xposed 模块

或在 Termux 中使用命令行：

```bash
# 主 App
curl -fL -o /sdcard/Download/wxhook.apk \
  https://github.com/linuxwff789/wxbackup/releases/download/nightly/app-debug.apk

# Xposed 模块
curl -fL -o /sdcard/Download/wxhook-xposed.apk \
  https://github.com/linuxwff789/wxbackup/releases/download/nightly/xposed-debug.apk
```

#### 2️⃣ 安装 APK

```bash
# 安装管理 App
su -c "pm install -r /sdcard/Download/wxhook.apk"

# 安装 Xposed 模块
su -c "pm install -r /sdcard/Download/wxhook-xposed.apk"
```

或在文件管理器中点击 APK 文件手动安装。

#### 3️⃣ 启用 Xposed 模块

1. 打开 **LSPosed** App
2. 找到 **wxhook** 模块
3. 启用模块
4. 作用域勾选 **微信 (com.tencent.mm)**
5. **重启微信**（建议重启设备）

#### 4️⃣ 验证安装

打开 wxhook App → 点击 **「🔍 检测环境」**，确认所有项目均为 ✅。

### 方法二：使用项目脚本自动安装

如果你有 Termux 和 GitHub 访问权限：

```bash
# 克隆仓库
git clone https://github.com/linuxwff789/wxbackup.git
cd wxbackup

# 从 nightly release 下载并安装最新版本
./install.sh
```

### 方法三：通过 GitHub Actions 手动构建

1. Fork 或推送代码到你的仓库
2. 在 GitHub Actions 页面手动触发 **Build APK** 工作流
3. 构建完成后在 Artifacts 中下载 APK

---

## 配置

### 首次启动

1. 打开 wxhook App
2. 点击 **「🔍 检测环境」** 确认环境正常
3. 确认密钥已捕获（`/data/local/tmp/.wechat_key` 文件存在且有内容）
4. 点击 **「全量备份」** 完成首次备份

### 云存储配置

#### WebDAV

1. 打开 App → **云同步** 卡片 → **⚙️ 配置**
2. 填写 WebDAV 服务器 URL、用户名、密码
3. 测试连接
4. 开启 **启用同步** 开关
5. 可选：设置 **定时同步** 时间

#### 阿里云盘

1. 在配置页面选择 **阿里云盘**
2. 输入 Refresh Token
3. 测试连接
4. 开启同步

---

## 升级

### 自动升级

```bash
# 使用项目脚本
./install.sh
```

### 手动升级

从 [nightly release](https://github.com/linuxwff789/wxbackup/releases/tag/nightly) 下载最新 APK，覆盖安装即可。

> 注意：Xposed 模块升级后需要**重启微信**才能生效。

---

## 卸载

### 卸载 App

```bash
su -c "pm uninstall com.nous.wxhook"
```

### 卸载 Xposed 模块

1. 在 LSPosed 中禁用 wxhook 模块
2. 或：
```bash
su -c "pm uninstall com.nous.wxhook.xposed"
```

### 清理备份数据

```bash
su -c "rm -rf /sdcard/Download/wxhook_backup"
su -c "rm -rf /data/local/tmp/wxhook_backup"
su -c "rm -f /data/local/tmp/.wechat_key"
```

---

## 常见安装问题

### ❌ "密钥未捕获"

```
可能原因：
1. LSPosed 模块未启用或作用域未包含微信
2. 微信版本不兼容（当前兼容 8.0.74）
3. 微信从未启动

解决：
1. 检查 LSPosed 中模块状态
2. 重启微信
3. 检查 /data/local/tmp/.wechat_key 文件
```

### ❌ "Root 检测失败"

```
可能原因：
1. 设备未 Root
2. Magisk 未授予 Termux root 权限
3. su 二进制不存在

解决：
1. 确认 Magisk 正常运行
2. 在 Magisk → 超级用户中允许 Termux
3. 运行 su -c id 测试
```

### ❌ "Xposed 模块未注册"

```
可能原因：
1. LSPosed 未安装
2. 模块 APK 未安装或版本不匹配
3. 需要重启设备

解决：
1. 确认 LSPosed 已安装并启用
2. 重新安装 xposed-debug.apk
3. 重启设备
```

### ❌ "数据库解密失败"

```
可能原因：
1. 微信版本变更，密钥位置变化
2. SQLCipher 参数不匹配
3. 密钥文件未正确捕获

解决：
1. 重新捕获密钥（重启微信）
2. 检查密钥文件内容格式
3. 在 App 中查看运行日志获取详细信息
```
