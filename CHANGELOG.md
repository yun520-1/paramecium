# HeartFlow 更新日志

## v2.0.14 (2026-06-14)

### 🔧 构建优化
- ✅ 版本升级至 2.0.14（versionCode 16）
- ✅ 编译零警告构建（修复 6 个编译警告）

#### 弃用API修复
- ✅ 修复 `menuAnchor()` 弃用 —— 替换为 `menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)`（ChatScreen.kt）
- ✅ 修复 `Icons.Default.ArrowBack` 弃用 —— 替换为 `Icons.AutoMirrored.Filled.ArrowBack`（TerminalScreen.kt）
- ✅ 修复 `Icons.Default.Send` 弃用 —— 替换为 `Icons.AutoMirrored.Filled.Send`（TerminalScreen.kt）
- ✅ 修复 `optString(name, null)` Kotlin 类型不匹配（ChatHistory.kt、MemoryModels.kt）
- ✅ 同步 TerminalScreen 版本号字符串（2.0.12 → 2.0.14）

---

## v2.0.1 (2026-06-13)

### 🔧 API连接优化

#### 网络稳定性
- ✅ 添加网络请求自动重试机制（最多3次）
- ✅ 添加智能错误分类（网络/SSL/API/超时）
- ✅ 添加连接超时和读取超时优化
- ✅ 启用OkHttp自动重试

#### 错误处理
- ✅ 添加HTTP状态码智能解析
- ✅ 添加API Key验证提示
- ✅ 添加API地址有效性检查
- ✅ 添加详细的错误信息提示
- ✅ 区分网络错误、API错误、服务器错误

#### 安全增强
- ✅ 添加网络安全配置文件
- ✅ 支持本地开发服务器（localhost/127.0.0.1）
- ✅ 添加HTTP-Referer和X-Title头
- ✅ 完善ProGuard混淆规则

#### 代码质量
- ✅ 参考gpt_mobile（⭐1135）BYOK架构
- ✅ 参考chatgpt-android（⭐3869）错误处理模式
- ✅ 优化API请求头格式
- ✅ 改进流式响应解析

### 🐛 问题修复

- ✅ 修复ProGuard混淆导致的API连接失败
- ✅ 修复网络配置缺失问题
- ✅ 修复错误信息不明确问题
- ✅ 修复重试机制缺失问题

---

## v2.0.0 (2026-06-13)

### 🆕 新增功能

#### 多引擎搜索
- ✅ 新增百度搜索引擎支持
- ✅ 新增360搜索引擎支持
- ✅ 新增搜狗搜索引擎支持
- ✅ 新增必应搜索引擎支持
- ✅ 新增谷歌搜索引擎支持
- ✅ 新增DuckDuckGo搜索引擎支持

#### 搜索增强
- ✅ 新增多引擎聚合搜索 (`multi_search`)
- ✅ 新增智能搜索功能 (`smart_search`)
- ✅ 搜索结果自动去重
- ✅ 并发搜索提升速度

#### 网页分析
- ✅ 新增深度网页分析 (`analyze_web`)
- ✅ 新增多网页对比 (`compare_web`)
- ✅ 自动提取网页标题、描述
- ✅ 自动提取页面链接
- ✅ 自动检测网页语言
- ✅ 文档结构分析（标题层级）

#### Skill管理
- ✅ 新增Skill安装功能 (`install_skill`)
- ✅ 新增Skill卸载功能 (`uninstall_skill`)
- ✅ 新增Skill列表功能 (`list_skills`)
- ✅ 新增Skill创建功能 (`create_skill`)
- ✅ 支持URL、本地文件、GitHub仓库安装

#### 文件操作
- ✅ 新增文件读写功能
- ✅ 新增文件删除功能
- ✅ 新增目录创建功能
- ✅ 新增文件复制移动功能
- ✅ 新增文件信息查询
- ✅ 新增文件内容搜索

#### 系统工具
- ✅ 新增环境信息获取
- ✅ 新增系统属性查询
- ✅ 新增安全命令执行

### 🔧 优化改进

#### 用户体验
- ✅ 优化搜索结果显示格式
- ✅ 添加搜索引擎状态提示
- ✅ 改进错误提示信息
- ✅ 支持中英文搜索引擎名称

#### 技术优化
- ✅ 使用协程并发搜索
- ✅ 优化HTML清理算法
- ✅ 改进链接提取逻辑
- ✅ 增强网页解析能力

### 🐛 问题修复

- ✅ 修复搜索引擎URL编码问题
- ✅ 修复超时处理逻辑
- ✅ 修复特殊字符处理

---

## v1.0.0 (2026-06-12)

### 初始版本

#### 核心功能
- 基础聊天界面
- API配置管理
- 人格设置
- 主题切换
- 聊天历史

#### 工具集成
- 计算器工具
- 时间获取
- 随机数生成
- UUID生成
- 文本转换
- 单位转换
- 网页获取
- HTTP请求
- 记忆功能

---

## 未来计划 (v3.0)

### 计划功能
- [ ] 语音输入/输出
- [ ] 图片识别分析
- [ ] 文件预览功能
- [ ] 代码高亮显示
- [ ] Markdown渲染
- [ ] 插件市场
- [ ] 云端同步
- [ ] 多设备协作

### 平台扩展
- [ ] Windows桌面版
- [ ] Linux桌面版
- [ ] iOS版
- [ ] Web版