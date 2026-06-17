# HeartFlow 2.0 - 功能清单

## 🆕 新增功能 (v2.0)

### 🌐 多引擎搜索能力

| 工具名称 | 功能 | 支持引擎 |
|---------|------|---------|
| `search_web` | 单引擎搜索 | 百度、360、搜狗、必应、谷歌、DuckDuckGo |
| `multi_search` | 多引擎聚合搜索 | 同时搜索多个引擎 |
| `smart_search` | 智能搜索 | 自动分析多个引擎结果 |
| `analyze_web` | 深度网页分析 | 提取标题、摘要、链接等 |
| `compare_web` | 多网页对比 | 对比分析多个网页 |
| `fetch_web` | 读取网页 | 获取网页纯文本内容 |
| `github_repo` | GitHub仓库 | 获取仓库详细信息 |
| `github_search` | GitHub搜索 | 搜索仓库和代码 |

**搜索引擎支持：**
- ✅ **百度** (baidu) - 中文搜索首选
- ✅ **360搜索** (360/qihoo) - 国内主流引擎
- ✅ **搜狗** (sogou) - 微信公众号搜索
- ✅ **必应** (bing) - 国际搜索
- ✅ **谷歌** (google) - 全球搜索
- ✅ **DuckDuckGo** (duckduckgo) - 隐私搜索

**智能分析能力：**
- ✅ 多引擎并发搜索，结果去重合并
- ✅ 网页内容深度分析（标题、摘要、链接、图片）
- ✅ 多网页对比分析
- ✅ 自动检测网页语言
- ✅ 文档结构提取（标题层级）

### 📦 Skill管理能力

| 工具名称 | 功能 | 示例命令 |
|---------|------|---------|
| `install_skill` | 安装Skill | `安装skill https://example.com/skill.json` |
| `list_skills` | 列出已安装Skill | `列出skill` |
| `uninstall_skill` | 卸载Skill | `卸载skill my-skill` |
| `create_skill` | 创建Skill模板 | `创建skill test-skill 测试技能` |

**安装源支持：**
- ✅ URL直接下载
- ✅ 本地文件安装
- ✅ GitHub仓库自动发现

**Skill存储位置：**
- macOS/Linux: `~/.heartflow/skills/`
- Windows: `%USERPROFILE%\.heartflow\skills\`

---

## 📁 文件操作 (v1.0)

| 工具名称 | 功能 | 示例命令 |
|---------|------|---------|
| `read_file` | 读取文件 | `读取文件 /path/to/file.txt` |
| `write_file` | 写入文件 | `写入文件 /path/to/file.txt 内容` |
| `append_file` | 追加内容 | `追加文件 /path/to/file.txt 内容` |
| `delete_file` | 删除文件 | `删除文件 /path/to/file.txt` |
| `list_files` | 列出目录 | `列出文件 /path/to/dir` |
| `create_directory` | 创建目录 | `创建目录 /path/to/new/dir` |
| `copy_file` | 复制文件 | `复制文件 源路径 目标路径` |
| `move_file` | 移动文件 | `移动文件 源路径 目标路径` |
| `file_info` | 文件信息 | `文件信息 /path/to/file.txt` |
| `search_files` | 搜索文件内容 | `搜索文件 /path/to/dir 关键词` |

---

## 🔧 系统工具 (v1.0)

| 工具名称 | 功能 | 示例命令 |
|---------|------|---------|
| `get_environment` | 系统环境信息 | `环境信息` |
| `get_system_properties` | 系统属性 | `系统属性 java.version` |
| `execute_command` | 执行命令 | `执行命令 ls -la` |

---

## 💬 使用示例

### 网页读取流程
```
用户: 读取网页 https://kotlinlang.org/docs/home.html
心虫: 网页内容 (https://kotlinlang.org/docs/home.html):
      Kotlin is a modern but also mature programming language...
      
用户: 搜索 Compose Multiplatform 教程
心虫: 搜索结果 (google):
      1. Getting Started - Compose Multiplatform
      2. Compose Multiplatform Tutorial
      ...
```

### Skill管理流程
```
用户: 搜索skill AI助手
心虫: 搜索到的Skill:
      
      📦 awesome-ai-assistant (⭐ 1200)
      📝 AI assistant with advanced features
      安装: 安装skill awesome-ai-assistant/ai-assistant

用户: 安装skill awesome-ai-assistant/ai-assistant
心虫: ✅ Skill安装成功!
      名称: ai-assistant
      来源: awesome-ai-assistant/ai-assistant
      位置: /Users/apple/.heartflow/skills/ai-assistant.skill

用户: 列出skill
心虫: 已安装的Skill (1个):
      
      📦 ai-assistant v1.0.0
      📝 AI assistant with advanced features
```

---

## 🛡️ 安全特性

- ✅ 危险命令拦截（rm -rf, shutdown等）
- ✅ 文件权限检查
- ✅ 网络请求超时保护
- ✅ 内容大小限制防止内存溢出
- ✅ URL验证防止恶意请求

---

## 📱 平台支持

| 平台 | 状态 | 说明 |
|------|------|------|
| Android | ✅ 支持 | APK直接安装 |
| macOS | ✅ 支持 | Compose Desktop |
| Windows | 🔄 计划中 | 未来版本支持 |
| Linux | 🔄 计划中 | 未来版本支持 |

---

## 🔗 相关链接

- GitHub: https://github.com/heartflow/heartflow-app
- 文档: https://docs.heartflow.app
- 问题反馈: https://github.com/heartflow/heartflow-app/issues