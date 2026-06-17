# HeartFlow v2.3.6 升级进度

## 当前状态
- 5个子agent并行读取hermes源码中
- explore-3: agent_loop/memory/model_tools
- explore-4: tools (browser/code/terminal/web/file)
- explore-5: providers/gateway/CLI
- explore-6: skills系统
- explore-7: optional模块

## 已集成的hermes功能
1. memory_tool.py - 双存储记忆系统（USER.md + USER.md）
2. skills_tool.py - 技能系统（117个skills扫描）
3. registry.py - 工具注册中心概念

## 待集成
- agent_loop（工具执行循环）
- provider系统（多模型切换）
- gateway（Telegram/Discord/Slack）
- 自学习闭环
- browser/code/terminal工具
- tts/vision/video工具

## 下一步
等子agent完成 → 分析源码 → 集成核心功能 → 构建APK
