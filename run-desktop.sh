#!/bin/bash

# HeartFlow 桌面应用启动脚本

echo "启动 HeartFlow 桌面应用..."

# 检查 Java 是否安装
if ! command -v java &> /dev/null; then
    echo "错误: 未找到 Java。请安装 Java 17 或更高版本。"
    echo "推荐使用: https://adoptium.net/"
    exit 1
fi

# 检查 Java 版本
JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
if [ "$JAVA_VERSION" -lt 17 ]; then
    echo "警告: Java 版本可能过低。推荐使用 Java 17 或更高版本。"
fi

# 构建并运行桌面应用
echo "正在构建桌面应用..."
./gradlew :shared:desktopRun --no-daemon

echo "桌面应用已关闭。"