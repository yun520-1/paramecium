# HeartFlow v2.3.6 搜索工具修复（general-1完成）

## 修复内容
- 所有搜索引擎（百度/必应/搜狗/360）添加8秒超时
- 添加SocketTimeoutException/UnknownHostException专门捕获
- 限制HTML处理数据量（最多100KB）
- 优化正则表达式避免回溯问题
- 超时时不再尝试其他搜索引擎
