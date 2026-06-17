# HeartFlow v2.3.6 - Hermes完整分析总结

## Skills系统架构（已分析完成）

### 核心机制
1. **SKILL.md格式**：YAML frontmatter + Markdown内容
   - name: ≤64字符，小写+连字符
   - description: ≤1024字符
   - platforms: [macos, linux, windows]
   - metadata.herMES.tags: 技能标签

2. **渐进式披露**：
   - Tier 1: 元数据（name+description）
   - Tier 2: 完整指令（skill_view加载）
   - Tier 3: 关联文件（references/templates/assets）

3. **工具模块**：
   - skills_tool.py: skills_list() + skill_view()
   - skill_manager_tool.py: create/edit/patch/delete
   - skill_utils.py: YAML解析、平台匹配

## 插件机制（已分析完成）

### 三大扩展体系
1. **optional-skills**（17域80+技能）：按需安装到~/.hermes/skills/
2. **optional-mcps**（Linear/n8n）：MCP协议外部服务桥接
3. **plugins**（18域）：进程级Python扩展

### plugins架构
- plugin_utils.py: 线程安全懒加载单例
- browser/: 浏览器自动化
- context_engine/: 上下文管理
- memory/: 记忆系统
- model-providers/: 模型适配
- video_gen/: 视频生成

## Kotlin迁移路径

### 优先级1：核心循环
1. agent_loop.py → AgentLoop.kt
2. conversation_loop → runConversation()
3. tool_executor → executeConcurrent/Sequential

### 优先级2：记忆系统
1. memory_manager.py → MemoryManager.kt
2. 预取+注入+流式清理

### 优先级3：web搜索
1. web_tools.py → WebTools.kt
2. 国内方案：百度/必应/搜狗

## 待执行
- [ ] 构建v2.3.6 APK
- [ ] 集成agent_loop
- [ ] 集成provider系统
- [ ] 集成web搜索
