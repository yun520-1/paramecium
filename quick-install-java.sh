#!/bin/bash

echo "🔧 HeartFlow 快速安装 Java 脚本"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

# 检查是否已安装Java
if command -v java &> /dev/null; then
    echo "✅ Java 已安装:"
    java -version
    echo ""
    echo "可以直接构建 APK:"
    echo "./gradlew :app:assembleDebug"
    exit 0
fi

echo "❌ 未检测到 Java，正在安装..."
echo ""

# 检查Homebrew
if ! command -v brew &> /dev/null; then
    echo "📦 正在安装 Homebrew..."
    /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
fi

echo "📦 正在安装 OpenJDK 17..."
brew install openjdk@17

echo ""
echo "🔗 创建符号链接..."
sudo ln -sfn /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-17.jdk

echo ""
echo "✅ Java 安装完成!"
echo ""
echo "验证安装:"
java -version

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "🚀 现在可以构建 APK:"
echo "./gradlew :app:assembleDebug"
echo ""
echo "📱 APK 位置:"
echo "app/build/outputs/apk/debug/app-debug.apk"
