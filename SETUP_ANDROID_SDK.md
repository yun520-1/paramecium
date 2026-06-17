# HeartFlow 2.0 构建指南

## ⚠️ 当前状态

系统缺少 Android SDK，无法构建 APK。

## 🔧 解决方案

### 方法1: 安装 Android Studio（推荐）

1. 下载 Android Studio: https://developer.android.com/studio
2. 安装并打开 Android Studio
3. 完成 SDK 安装向导
4. 设置环境变量：

```bash
# 添加到 ~/.zshrc 或 ~/.bash_profile
export ANDROID_HOME=$HOME/Library/Android/sdk
export PATH=$PATH:$ANDROID_HOME/emulator
export PATH=$PATH:$ANDROID_HOME/platform-tools
```

### 方法2: 仅安装 Android SDK 命令行工具

```bash
# 创建 SDK 目录
mkdir -p ~/Library/Android/sdk

# 下载命令行工具
cd ~/Library/Android/sdk
curl -o commandlinetools.zip https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip
unzip commandlinetools.zip

# 设置环境变量
export ANDROID_HOME=$HOME/Library/Android/sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin

# 安装必要的 SDK 组件
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
```

## 🚀 构建步骤

安装 Android SDK 后：

```bash
cd /Users/apple/Downloads/heartflow_kotlin_src

# 设置 Java 环境
export JAVA_HOME=~/java/jdk-17.0.2.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"

# 设置 Android SDK 环境
export ANDROID_HOME=$HOME/Library/Android/sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools

# 构建 APK
./gradlew :app:assembleDebug
```

## 📱 APK 位置

```
app/build/outputs/apk/debug/app-debug.apk
```

## 📋 版本信息

- **版本**: 2.0.0
- **新增功能**:
  - 多引擎搜索（百度、360、搜狗、必应、谷歌）
  - 智能搜索分析
  - 网页深度分析
  - Skill 管理系统
  - 文件操作工具

---

**需要帮助安装 Android SDK 吗？**