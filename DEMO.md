# HeartFlow 功能演示

## 基本对话

用户: 你好
心虫: 你好！我是心虫，你的AI助手。
心虫: 我可以帮你读写文件、执行命令、获取环境信息等。
心虫: 试试对我说些话，或者让我帮你处理文件。

## 文件操作演示

### 读取文件
用户: 读取文件 /Users/apple/Documents/test.txt
心虫: 文件内容 (/Users/apple/Documents/test.txt):
      这是一个测试文件的内容。
      第二行内容。

### 写入文件
用户: 写入文件 /Users/apple/Documents/hello.txt 你好世界，这是心虫写入的内容。
心虫: 文件写入成功: /Users/apple/Documents/hello.txt (25 字符)

### 列出文件
用户: 列出文件 /Users/apple/Documents
心虫: 目录内容 (/Users/apple/Documents):
      📁 Projects
      📄 test.txt
      📄 hello.txt
      📄 README.md

### 创建目录
用户: 创建目录 /Users/apple/Documents/NewFolder
心虫: 目录创建成功: /Users/apple/Documents/NewFolder

### 文件信息
用户: 文件信息 /Users/apple/Documents/test.txt
心虫: 文件信息: /Users/apple/Documents/test.txt
      类型: 文件
      大小: 1234 字节
      最后修改: 2026-06-13 18:30:00
      可读: true
      可写: true
      可执行: false
      扩展名: txt

### 搜索文件
用户: 搜索文件 /Users/apple/Documents 测试
心虫: 找到 2 个文件包含 '测试':
      📄 /Users/apple/Documents/test.txt
      📄 /Users/apple/Documents/Projects/readme.txt

### 删除文件
用户: 删除文件 /Users/apple/Documents/hello.txt
心虫: 文件删除成功: /Users/apple/Documents/hello.txt

## 环境信息演示

### 获取环境信息
用户: 环境信息
心虫: 系统环境信息:
      平台: macOS
      Java版本: 17.0.2
      处理器数量: 8
      最大内存: 4096MB
      已分配内存: 512MB
      可用内存: 256MB
      用户目录: /Users/apple
      临时目录: /tmp

### 获取系统属性
用户: 系统属性 java.version
心虫: java.version = 17.0.2

### 执行命令
用户: 执行命令 ls -la /Users/apple
心虫: 输出:
      total 96
      drwx------@ 15 apple  staff   480 Jun 13 18:30 .
      drwxr-xr-x   6 root  admin   192 Jun 13 18:00 ..
      -rw-r--r--   1 apple  staff  1234 Jun 13 18:30 .zshrc
      drwxr-xr-x   3 apple  staff    96 Jun 13 18:30 Documents
      
      退出码: 0

## 高级用法

### 组合命令
用户: 写入文件 /tmp/script.sh #!/bin/bash\necho "Hello from HeartFlow"
心虫: 文件写入成功: /tmp/script.sh (38 字符)

用户: 执行命令 chmod +x /tmp/script.sh && /tmp/script.sh
心虫: 输出:
      Hello from HeartFlow
      
      退出码: 0

### 批量操作
用户: 列出文件 /Users/apple/Documents *.txt
心虫: 目录内容 (/Users/apple/Documents):
      📄 test.txt
      📄 notes.txt
      📄 todo.txt

## 网页操作演示

### 读取网页
用户: 读取网页 https://github.com
心虫: 网页内容 (https://github.com):
      GitHub where over 100 million developers shape the future of software...

### 单引擎搜索
用户: 搜索 Kotlin Compose
心虫: 🔍 baidu 搜索结果:
      1. Compose Multiplatform - Kotlin
      2. Getting Started with Compose Multiplatform
      ...

### 多引擎搜索
用户: 多引擎搜索 Kotlin教程
心虫: 🌐 多引擎搜索: "Kotlin教程"
      
      搜索引擎状态:
      ✅ baidu: 找到结果
      ✅ bing: 找到结果
      ✅ 360: 找到结果

### 智能搜索
用户: 智能搜索 AI助手开发
心虫: 🧠 智能搜索: "AI助手开发"
      ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
      
      📊 baidu 引擎结果:
      ...
      
      📊 bing 引擎结果:
      ...
      
      📊 360 引擎结果:
      ...
      
      💡 搜索完成，共使用3个搜索引擎

### 深度网页分析
用户: 分析网页 https://kotlinlang.org
心虫: 🌐 网页分析: https://kotlinlang.org
      ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
      
      📄 标题: Kotlin Programming Language
      📝 描述: Kotlin is a modern but also mature programming language...
      
      📖 正文摘要:
      Kotlin is a cross-platform, statically typed, general-purpose...
      
      🔗 相关链接:
        • Docs -> https://kotlinlang.org/docs/home.html
        • Playground -> https://play.kotlinlang.org

### 网页对比
用户: 对比网页 https://kotlinlang.org,https://www.scala-lang.org
心虫: 📊 多网页对比分析
      ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
      
      1. Kotlin Programming Language
         URL: https://kotlinlang.org
         字数: 15000
      
      2. The Scala Programming Language
         URL: https://www.scala-lang.org
         字数: 12000
      
      📈 统计:
        • 有效页面: 2/2
        • 平均字数: 13500

### GitHub仓库信息
用户: GitHub仓库 JetBrains/compose-multiplatform
心虫: 📦 JetBrains/compose-multiplatform
      📝 Compose Multiplatform, a modern UI toolkit for building native...
      ⭐ 15000 stars
      🔗 https://github.com/JetBrains/compose-multiplatform

### GitHub搜索
用户: GitHub搜索 AI assistant kotlin
心虫: GitHub搜索结果:
      📦 example/ai-assistant (⭐ 500)
      📝 AI assistant built with Kotlin
      🔗 https://github.com/example/ai-assistant

## Skill管理演示

### 安装Skill
用户: 安装skill https://example.com/my-skill.skill
心虫: ✅ Skill安装成功!
      名称: my-skill
      位置: /Users/apple/.heartflow/skills/my-skill.skill

用户: 安装skill owner/repo
心虫: ✅ Skill安装成功!
      名称: skill-name
      来源: owner/repo
      位置: /Users/apple/.heartflow/skills/skill-name.skill

### 列出已安装Skill
用户: 列出skill
心虫: 已安装的Skill (3个):
      
      📦 my-skill v1.0.0
      📝 我的自定义技能
      
      📦 another-skill v2.1.0
      📝 另一个有用的技能

### 卸载Skill
用户: 卸载skill my-skill
心虫: ✅ Skill已卸载: my-skill

### 创建Skill
用户: 创建skill test-skill 这是一个测试技能
心虫: ✅ Skill创建成功!
      名称: test-skill
      位置: /Users/apple/.heartflow/skills/test-skill.skill

## 快捷命令

- `帮助` - 显示所有可用命令
- `清空` - 清空对话历史
- `退出` - 关闭应用

## 注意事项

1. **安全限制**: 某些危险命令（如 `rm -rf`）会被拒绝执行
2. **权限问题**: 如果没有文件操作权限，会显示错误信息
3. **文件大小**: 大文件内容会被截断显示（最多5000字符）
4. **编码支持**: 默认使用 UTF-8 编码