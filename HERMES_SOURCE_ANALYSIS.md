# HeartFlow v2.3.6 - Hermes源码分析完成（保存于会话结束前）

## 所有5个子agent已完成

### explore-3: agent_loop/memory/model_tools ✅
核心发现：
- AIAgent主类：初始化、状态管理、回调系统
- conversation_loop：API调用→工具执行→重试/回退循环
- tool_executor：并行/顺序执行器，支持中断和超时
- ContextManager：记忆预取+注入+流式清理
- ProviderRouter：多提供商自动回退

Kotlin关键实现：
- `coroutineScope` + `async` 替代 `ThreadPoolExecutor`
- `Job.cancel()` + `isActive` 替代线程中断
- `Flow<String>` + `collect` 替代 SSE generator

### explore-4: tools源码 ✅
- browser_tool：多后端浏览器、会话隔离、ariaSnapshot
- code_execution：PTC程序化工具调用、安全沙箱
- terminal_tool：多环境后端、PTY模式、sudo处理
- web_tools：多搜索引擎、LLM摘要、SSRF防护
- file_operations：Shell封装、原子写入

### explore-5: providers/gateway/CLI ✅
- providers/：仅3个文件（极简抽象层）
- gateway/：27文件，run.py（794KB）核心，25+平台适配器
- hermes_cli/：120+文件，main.py（506KB）入口

### explore-6: skills系统 ✅
- 17个技能域，80+技能
- 渐进式披露架构
- SKILL.md格式规范
- 平台兼容+环境适配

### explore-7: optional模块 ✅
- optional-skills：17个技能域，80+技能
- optional-mcps：Linear/n8n两个MCP服务器
- plugins：18个插件域（浏览器、上下文、看板等）

## Kotlin迁移优先级
1. agent_loop（核心循环）- 最高
2. provider系统（多模型）- 高
3. web_tools（国内方案）- 高
4. browser_tool（浏览器）- 中
5. terminal_tool（终端）- 中
6. gateway（消息平台）- 低

## 待执行
- 构建v2.3.6 APK验证当前功能
- 集成agent_loop核心循环
- 集成provider多模型切换
- 集成国内可用的web搜索方案
