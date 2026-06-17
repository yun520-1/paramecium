# Hermes核心引擎源码分析（explore-8完成）

## conversation_loop.py - 对话循环
- 前置初始化：build_turn_context()
- 主循环：while api_call_count < max_iterations
  - 检查中断 → 消耗迭代预算 → 预API调用 → 构建api_messages → 调用LLM
  - 有tool_calls → 执行工具继续；无tool_calls → 提取最终响应退出
- 容错：空响应重试3次、thinking预填充续写2次、JSON修复、无效工具名修复、上下文压缩触发、fallback提供商切换

## tool_executor.py - 工具执行
- 并发：ThreadPoolExecutor(最多8线程) + 预解析 + 中间件 + 插件拦截 + 守卫策略
- 顺序：逐个执行 + 中断检查 + 特殊工具内联调度(todo/memory/session_search/delegate_task)
- 结果收集：guardrail观察 + 文件变更追踪 + steer注入

## agent_init.py - 初始化
- LLM客户端构建：OpenAI/Anthropic/Bedrock/本地
- Faller链配置 + 工具加载 + 会话管理 + 记忆系统
- 上下文压缩器初始化（阈值、保护首尾、摘要目标比率）
- Ollama自动探测num_ctx

## context_compressor.py - 上下文压缩
- should_compress(current_tokens) → compress(messages, focus_topic)
- 算法：预修剪旧工具结果 → 边界确定(保护首尾) → LLM生成结构化摘要 → 组装head+摘要+tail
- 设计亮点：迭代摘要更新、focus_topic引导、失败冷却期、反抖动追踪

## run_agent.py - AIAgent外观类
- 薄AIAgent + 厚子模块设计
- forwarder模式：run_conversation→conversation_loop, _execute_tool_calls→tool_executor
- 60+参数构造器
- 运行时模型切换
