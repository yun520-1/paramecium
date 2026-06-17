#!/bin/bash

# HeartFlow Android 构建脚本

echo "构建 HeartFlow Android 应用..."
echo "版本: 2.0.0 - 新增网页读取和Skill管理功能"

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

# 构建 Debug APK
echo "正在构建 Debug APK..."
./gradlew :app:assembleDebug --no-daemon

# 检查构建结果
if [ -f "app/build/outputs/apk/debug/app-debug.apk" ]; then
    echo "✅ 构建成功！"
    echo ""
    echo "📱 APK 信息:"
    echo "   位置: app/build/outputs/apk/debug/app-debug.apk"
    echo "   大小: $(du -h app/build/outputs/apk/debug/app-debug.apk | cut -f1)"
    echo ""
    echo "🆕 新增功能:"
    echo "   🌐 网页读取 - 获取网页内容"
    echo "   🔍 网络搜索 - 百度/Bing/Google搜索"
    echo "   📦 GitHub集成 - 仓库信息和代码搜索"
    echo "   🛠️ Skill管理 - 安装/卸载/创建Skill"
    echo ""
    echo "📲 安装到手机:"
    echo "   1. 将APK发送到手机"
    echo "   2. 打开文件，允许安装未知来源应用"
    echo "   3. 按提示完成安装"
else
    echo "❌ 构建失败"
    exit 1
fi