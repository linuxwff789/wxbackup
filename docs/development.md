# 开发指南

## 开发环境

### 必要工具

| 工具 | 版本 | 用途 |
|------|------|------|
| JDK | 17+ | Kotlin/JVM 编译 |
| Android SDK | 35 (compileSdk) | Android 编译 |
| NDK | 27.0.12077973 | 原生代码编译 |
| CMake | 3.22.1+ | NDK 构建 |
| Gradle | 8.7 (wrapper) | 构建系统 |
| Kotlin | 2.0.x | 主开发语言 |

### 推荐 IDE

- **Android Studio** Ladybug+ (2024.x)
- 安装插件：Kotlin、Android APK Support、LSPosed Development Kit

---

## 项目设置

### 1. 克隆仓库

```bash
git clone https://github.com/linuxwff789/wxbackup.git
cd wxbackup
```

### 2. 本地构建（可选）

项目主要依赖 GitHub Actions 自动构建。如需本地构建：

```bash
# 确保 ANDROID_HOME 已设置
export ANDROID_HOME=/path/to/android/sdk

# 编译 Debug APK
./gradlew assembleDebug

# 产物位置
# app/build/outputs/apk/debug/app-debug.apk
# xposed/build/outputs/apk/debug/xposed-debug.apk
```

### 3. 使用 GitHub Actions（推荐）

```bash
# 推送代码自动触发 CI 构建
git add .
git commit -m "feat: 我的改动"
git push origin main

# 等待 2-3 分钟后在 Actions 页面下载 APK
# 或使用 install.sh 自动下载安装
./install.sh
```

---

## 项目脚本

| 脚本 | 功能 | 用法 |
|------|------|------|
| `push.sh` | 提交并推送代码 | `./push.sh "feat: 说明"` |
| `install.sh` | 从 nightly release 下载并安装 APK | `./install.sh` |
| `build.sh` | 一键推送 + 等待 CI + 安装 | `./build.sh "feat: 说明"` |
| `clean.sh` | 清理构建产物 | `./clean.sh` |

### push.sh

```bash
#!/bin/bash
git add . && git commit -m "$1" && git push origin main
```

### install.sh

从 GitHub nightly release 下载并安装最新 APK：

```bash
curl -fL -o /data/local/tmp/app-debug.apk \
  https://github.com/linuxwff789/wxbackup/releases/download/nightly/app-debug.apk
curl -fL -o /data/local/tmp/xposed-debug.apk \
  https://github.com/linuxwff789/wxbackup/releases/download/nightly/xposed-debug.apk

su -c "pm install -r /data/local/tmp/app-debug.apk"
su -c "pm install -r /data/local/tmp/xposed-debug.apk"
```

---

## CI/CD 工作流

工作流文件：`.github/workflows/build.yml`

### 触发条件

- `push` 到 `main` 分支
- `pull_request` 到 `main` 分支
- `workflow_dispatch`（手动触发）

### 构建步骤

```
1. checkout 代码
2. 设置 JDK 17
3. 安装 Android SDK 35 + NDK 27 + CMake
4. 下载 native 二进制 (zstd)
5. 下载 OpenList AAR
6. 配置签名
7. ./gradlew assembleDebug
8. 上传 APK 为 Artifact
9. 发布到 nightly release
```

### 签名配置

Release 签名通过 GitHub Secrets 注入：

| Secret | 说明 |
|--------|------|
| `KEYSTORE_BASE64` | 签名文件 (Base64) |
| `KEYSTORE_PASSWORD` | 签名密码 |
| `KEY_ALIAS` | 别名 |
| `KEY_PASSWORD` | 别名密码 |

---

## 代码规范

### Kotlin

- 使用 Kotlin 2.0 语法
- 遵循 [Kotlin 官方编码规范](https://kotlinlang.org/docs/coding-conventions.html)
- 使用 `object` 单例模式替代静态工具类
- 协程使用 `viewModelScope` + `Dispatchers.IO` 执行 IO 操作

### 命名约定

| 类别 | 规范 | 示例 |
|------|------|------|
| 包名 | `com.nous.wxhook.{module}` | `com.nous.wxhook.backup` |
| 类/对象 | PascalCase | `BackupOrchestrator` |
| 方法 | camelCase | `doFullBackup()` |
| 变量 | camelCase | `totalFiles` |
| 常量 | UPPER_SNAKE | `MAX_LOG_LINES` |

### 错误处理

- 关键操作使用 `try-catch` 并记录 `Log.e`
- Root 命令使用 `RootGateways.run()` 并检查返回值
- 不抛出未捕获异常的协程

### 日志

```kotlin
// 调试日志
Log.d("wxhook:tag", "message")

// 错误日志
Log.e("wxhook:tag", "message", exception)

// 用户可见日志
callback?.onProgress("进度信息", current, total)
```

---

## 模块开发指南

### 添加新的备份阶段

1. 在 `BackupOrchestrator` 中添加私有方法
2. 在 `doFullBackup()` 或 `doIncrementalBackup()` 中调用
3. 通过 `callback?.onProgress()` 报告进度
4. 使用 `try-catch` 包裹并返回 `BackupHookLocal.Result`

### 添加新的 Xposed Hook

1. 在 `xposed/src/main/java/.../hook/` 下创建新类
2. 实现 `IXposedHookLoadPackage` 接口
3. 在 `WeChatHookEntry.kt` 中注册

### 添加新的 UI 页面

1. 在 `ui/` 下创建新的 Activity
2. 在 `AndroidManifest.xml` 中注册
3. 使用 Material Design 3 组件
4. 遵循 ViewModel + StateFlow 模式

---

## 测试

```bash
# 运行单元测试
./gradlew test

# 运行 instrumented 测试
./gradlew connectedAndroidTest
```

现有测试：
- `FullBackupLayoutTest.kt` — 全量备份布局测试
- `NativeArchivePlanTest.kt` — 归档计划测试

---

## 发布流程

1. 在 `build.gradle.kts` 中更新版本号
2. 提交并推送代码
3. GitHub Actions 自动构建并发布到 nightly release
4. 如需正式 Release，在 GitHub 上创建新的 Release Tag
