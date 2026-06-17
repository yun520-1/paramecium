# HeartFlow - 心虫 v2.0.1

一个智能AI助手应用，支持多模型对话、工具调用、记忆系统。

> 参考 [chatgpt-android](https://github.com/skydoves/chatgpt-android) (⭐3869) 和 [gpt_mobile](https://github.com/Taewan-P/gpt_mobile) (⭐1135) 架构设计

## 功能特性

### 🤖 多模型支持
- **OpenAI**: GPT-3.5/GPT-4
- **DeepSeek**: DeepSeek Chat
- **通义千问**: Qwen Turbo
- **智谱GLM**: GLM-4-Flash
- **Moonshot**: Moonshot v1-8k
- **自定义**: 支持任何OpenAI兼容API

### 🌐 多引擎搜索
- **单引擎搜索**: 百度、360、搜狗、必应、谷歌、DuckDuckGo
- **多引擎聚合**: 同时搜索多个引擎，结果去重合并
- **智能搜索**: 自动分析多个引擎，提供综合信息

### 📦 Skill管理
- **安装Skill**: 从URL、本地文件安装
- **卸载Skill**: 卸载已安装的Skill
- **列出Skill**: 查看所有已安装的Skill
- **创建Skill**: 创建新的Skill模板

### 🧠 三层记忆系统
- **工作记忆**: 当前对话上下文
- **情景记忆**: 历史对话记录
- **核心记忆**: 重要信息永久存储

### 💬 智能对话
- 情绪感知与共情回应
- 流式响应实时显示
- 工具调用结果展示
- 对话历史管理

## 技术架构

### 参考优秀项目

| 项目 | Stars | 参考内容 |
|------|-------|----------|
| [chatgpt-android](https://github.com/skydoves/chatgpt-android) | ⭐3869 | 错误处理、UI设计 |
| [gpt_mobile](https://github.com/Taewan-P/gpt_mobile) | ⭐1135 | BYOK架构、多模型支持 |

### 技术栈
- **Kotlin**: 主要编程语言
- **Jetpack Compose**: 声明式UI框架
- **Material Design 3**: UI设计规范
- **OkHttp**: 网络请求库
- **Kotlin Coroutines**: 异步编程

## 构建

```bash
# 设置环境变量
export JAVA_HOME=~/java/jdk-17.0.2.jdk/Contents/Home
export ANDROID_HOME=~/Library/Android/sdk

# 构建Debug APK
./gradlew assembleDebug

# APK输出位置
# app/build/outputs/apk/debug/app-debug.apk
```

## 安装

1. 将APK传输到Android设备
2. 打开文件管理器，找到APK文件
3. 允许安装未知来源应用
4. 按提示完成安装

## 配置

1. 打开应用，点击右上角 ⚙️ 图标
2. 选择API提供商（如Moonshot）
3. 输入API Key
4. 保存配置

## 许可证

MIT License