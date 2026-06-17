package com.heartflow.app

// 注意：ChatScreen.kt 已拆分到子模块中，具体请参见：
// - chat/HeartFlowApp.kt  → HeartFlowApp（启动画面 + 路由） + ChatPage（主聊天页）
// - chat/ChatMessageItem.kt → 消息气泡组件
// - chat/ChatInput.kt → 输入框组件
// - chat/MarkdownRenderer.kt → Markdown 渲染
// - views/ImageViewer.kt → 全屏图片查看器
// - views/HistoryPage.kt → 对话历史页面
// - dialogs/QuickSettingsDialog.kt → 快速设置对话框
// - settings/SettingsPage.kt → 设置页面（含 SearchableSetting / SettingsSectionHeader）
// - settings/PersonalitySettings.kt → 性格选择设置
// - settings/ProfileSettings.kt → 个人画像设置
// - settings/ThemeSettings.kt → 主题与显示设置
// - settings/ApiSettings.kt → API 配置设置
// - settings/ToolSettings.kt → 工具设置
// - settings/SkillSettings.kt → 技能管理设置
// - settings/MediaSettings.kt → 媒体设置
// - settings/AdvancedSettings.kt → 高级设置

// 以上所有文件与 ChatScreen.kt 同属 com.heartflow.app 包，
// 因此 MainActivity.kt 无需任何导入修改即可引用 HeartFlowApp()。
