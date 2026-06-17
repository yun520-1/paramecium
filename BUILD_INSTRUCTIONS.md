# HeartFlow 2.0 构建说明

## 🚨 当前状态

系统未安装 Java，无法直接构建 APK。请按照以下步骤安装 Java 后重新构建。

## 📦 安装 Java 17

### 方法1: 使用 Homebrew (推荐)

```bash
# 安装 Homebrew (如果没有)
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"

# 安装 OpenJDK 17
brew install openjdk@17

# 创建符号链接
sudo ln -sfn /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-17.jdk

# 验证安装
java -version
```

### 方法2: 下载安装包

1. 访问 https://adoptium.net/
2. 下载 OpenJDK 17 (macOS / aarch64)
3. 双击 .pkg 安装包进行安装
4. 安装完成后重启终端

### 方法3: 使用 SDKMAN

```bash
# 安装 SDKMAN
curl -s "https://get.sdkman.io" | bash

# 安装 Java 17
sdk install java 17.0.2-open

# 切换版本
sdk use java 17.0.2-open
```

## 🔨 构建 APK

安装 Java 后，执行以下命令构建：

```bash
cd /Users/apple/Downloads/heartflow_kotlin_src

# 清理旧构建
./gradlew clean

# 构建 Debug APK
./gradlew :app:assembleDebug

# 构建 Release APK (需要签名配置)
./gradlew :app:assembleRelease
```

## 📱 APK 位置

构建成功后，APK 文件位于：

```
app/build/outputs/apk/debug/app-debug.apk
```

## 📋 版本信息

- **版本号**: 2.0.0
- **版本代码**: 2
- **新增功能**:
  - 多引擎搜索（百度、360、搜狗、必应、谷歌）
  - 智能搜索分析
  - 网页深度分析
  - Skill管理系统
  - 文件操作工具
  - 系统信息工具

## 🐛 常见问题

### 1. Gradle 下载慢

配置国内镜像源（已配置）：
- 阿里云 Maven 镜像
- 腾讯云 Maven 镜像

### 2. 内存不足

在 `gradle.properties` 中增加内存：
```properties
org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8
```

### 3. 签名问题

Debug 构建使用默认签名，无需配置。
Release 构建需要配置签名密钥。

## 📞 获取帮助

如果遇到问题，请检查：
1. Java 版本是否为 17+
2. 网络连接是否正常
3. 磁盘空间是否充足

---

**最新版本**: v2.0.0
**更新日期**: 2026-06-13