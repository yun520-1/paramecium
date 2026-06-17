# HeartFlow Android 项目要求

## 版本管理规范

### 版本号规则
- **每次优化修复后，版本号 +0.0.1**
- 格式：`主版本.次版本.修订号`（如 2.5.2 → 2.5.3）
- 版本号在 `AdvancedSettings.kt` 中更新

### APK 生成要求
- **每次优化必须生成 APK**
- 编译命令：`JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug`
- APK 保存位置：`app/build/outputs/apk/debug/app-debug.apk`

### APK 保存位置
- **必须复制到图片文件夹**：`~/Pictures/HeartFlow/`
- 文件命名格式：`HeartFlow_v{版本号}.apk`
- 示例：`HeartFlow_v2.5.3.apk`

## 自动化脚本

```bash
# 完整编译+复制流程
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug && \
  mkdir -p ~/Pictures/HeartFlow && \
  cp app/build/outputs/apk/debug/app-debug.apk ~/Pictures/HeartFlow/HeartFlow_v2.5.3.apk
```

## 版本更新流程

1. 完成代码优化/修复
2. 更新 `AdvancedSettings.kt` 中的版本号（+0.0.1）
3. 执行 `./gradlew assembleDebug` 编译 APK
4. 复制 APK 到 `~/Pictures/HeartFlow/`
5. 更新本文件 CHANGELOG 记录本次变更

## 当前版本
- **版本号**：2.5.3
- **最近更新**：专业级文档扫描（8种增强效果，超越夸克扫描王）

## CHANGELOG

| 版本 | 日期 | 变更内容 |
|------|------|----------|
| 2.5.3 | 2026-06-17 | 专业级文档扫描：8种增强效果（自动增强/文档增强/黑白/去噪/提亮/文字锐化/小票），基于iOS Core Image算法 |
| 2.5.2 | 2026-06-17 | Token推荐显示256K上下文、浏览器地址栏优化、扫描文件功能独立入口 |
| 2.5.1 | 2026-06-16 | 浏览器内核集成、独立文档扫描入口、API输入输出修复 |
| 2.5.0 | 2026-06-15 | 三层记忆系统、完整认知引擎 |

## 核心算法库

### ScanImageProcessor（专业图像处理引擎）

基于 iOS Core Image 滤镜图移植的算法：

| 滤镜 | 算法 | 参数 |
|------|------|------|
| AUTO_ENHANCE | 直方图1%/99%截断拉伸 + 饱和度+12% | CIImage.autoAdjustment |
| WHITE_DOCUMENT | 亮度+0.12、对比度+1.45、阴影+0.3 | CIColorControls |
| BLACK_AND_WHITE | 对比度+1.5、完全去饱和 | CIColorControls |
| SHARPEN_TEXT | USM锐化（3x3卷积核） | CIUnsharpMask |
| RECEIPT | 灰度+高对比+锐化 | 组合滤镜 |
| DENOISE | 3x3均值滤波+高光保护 | CINoiseReduction |
| BRIGHTEN | 曝光+0.7EV | CIExposureAdjust |

## 技术栈

- **Jetpack Compose** + Material 3
- **MVVM** 架构
- **Kotlin Coroutines** 异步处理
- **CameraX** 相机支持（可选）
- **ML Kit** 边缘检测（可选）
