---
title: "User Tools — 消费者指南"
summary: "Agent/LLM 端视角的全量工具调用参考 — Builtin 9 个 + MCP 动态，每个工具的名称、用途、参数、返回值与错误处理"
read_when:
  - "calling agent tools (built-in or MCP) from Agent or LLM context"
  - "understanding tool input schema, parameter types, and expected JSON arguments for function calling"
  - "debugging unexpected tool behavior or error responses"
scope:
  - "alice-tool-gateway"
status: "active"
updated: "2026-06-20"
---

# User Tools — 消费者指南

> **受众**：Agent 推理层 (Planner/LLM)、测试脚本、集成方。
> **用途**：在 function calling 上下文中，按正确参数结构调用全部可用工具。

---

## 1. Tool Source 分类

Alice Agent 的工具来自两个源头：

| Source | 来源 | 数量 |
|--------|------|------|
| **Builtin** | 内置工具（文件读写、搜索、Shell 执行、Web 搜索） | 9 个 (固定) |
| **MCP** | 外部 MCP 服务器动态发现的工具 | 动态 (取决于已连接的 MCP Server) |

---

## 2. 工具总览 — Builtin

| # | 工具名 | 层 | 风险 | 描述 |
|---|--------|----|------|------|
| 1 | `list_dir` | 内部感知 | LOW | 列出目录内容 |
| 2 | `file_exists` | 内部感知 | LOW | 检查文件/目录是否存在 |
| 3 | `search_file` | 内部感知 | LOW | 递归 glob 搜索文件 |
| 4 | `read_file` | 内部操作 | LOW | 读取文件内容 (UTF‑8) |
| 5 | `write_file` | 内部操作 | HIGH | 写入文件内容，自动创建父目录 |
| 6 | `remove_file` | 内部操作 | HIGH | 删除文件（禁止删除目录） |
| 7 | `grep` | 执行与检索 | LOW | 正则搜索文件中的匹配行 |
| 8 | `run` | 执行与检索 | HIGH | 执行 shell 命令 |
| 9 | `web_search` | 外部感知 | MEDIUM | 通过 DuckDuckGo 搜索互联网 |

## 3. 工具总览 — MCP (动态)

MCP 工具由外部服务器按 MCP 2.0 协议暴露。每个 MCP Client 连接后，其 `tools/list` 返回的工具集会自动加入可用工具集。

### 3.1 MCP 工具调用方式

MCP 工具通过 `ExecutionEngine.invoke()` 统一执行，接口与 Builtin 一致：

```
# 调用格式
toolName = "<serverId>:<toolName>"    # 带 serverId 前缀，避免命名冲突
params   = { key: value, ... }

# 示例
ExecutionEngine.invoke("filesystem:read", {"path": "/home/user/doc.txt"})
ExecutionEngine.invoke("github:list_repos", {"org": "cland"})
ExecutionEngine.invoke("database:query", {"sql": "SELECT * FROM users"})
```

**命名规则：** MCP 工具注册时自动加上 `{serverId}:` 前缀。

### 3.2 典型 MCP Server 工具集

以下仅作示例，实际工具集取决于连接的 MCP Server：

| 工具名 (注册后) | 用途 | 典型服务器 |
|----------------|------|-----------|
| `filesystem:read` | 读取远程文件 | MCP Filesystem Server |
| `filesystem:write` | 写入远程文件 | MCP Filesystem Server |
| `filesystem:list_dir` | 列出远程目录 | MCP Filesystem Server |
| `github:list_repos` | 列出仓库 | MCP GitHub Server |
| `github:create_issue` | 创建 Issue | MCP GitHub Server |
| `database:query` | SQL 查询 | MCP Database Server |
| `slack:send_message` | 发送 Slack 消息 | MCP Slack Server |
| `notion:search` | 搜索 Notion 内容 | MCP Notion Server |

### 3.3 MCP 工具参数格式

所有 MCP 工具的参数由外部服务器定义，调用时按实际参数名传入即可：

```json
{
  "path": "/etc/config.json"
}
```

**区别与 Builtin：**
- Builtin 参数通过 Java 强类型自动校验（字符串 → int/boolean 自动转换）
- MCP 参数直接透传给外部服务器，不经过 Java 类型转换
- MCP 返回值格式由外部服务器决定（一般为 text/json）

---

## 4. 工具分类 — Builtin

### 4.1 内部感知层 (Local Perception)

只读查询文件系统，无副作用，适合多轮链式调用。

#### 4.1.1 `list_dir`

列出目录下的文件和子目录。

**JSON Schema (inputSchema):**

```json
{
  "type": "object",
  "properties": {
    "path": {
      "type": "string",
      "description": "Directory path to list"
    }
  },
  "required": ["path"]
}
```

**返回格式：**

```
File1.java
SubDir/
readme.md
[empty directory]
```

**错误示例：**

```
list_dir: directory not found: /nonexistent
list_dir: not a directory: /path/to/file.txt   ← 路径指向文件而非目录
```

---

#### 4.1.2 `file_exists`

检查文件或目录是否存在。

**JSON Schema:**

```json
{
  "type": "object",
  "properties": {
    "path": {
      "type": "string",
      "description": "File or directory path to check"
    }
  },
  "required": ["path"]
}
```

**返回值：** `"true"` 或 `"false"`（字符串）

**注意：** 对目录也返回 `"true"`，如果需区分文件和目录，可配合 `list_dir` 或 `read_file` 验证。

---

#### 4.1.3 `search_file`

在目录中递归搜索文件名匹配 glob 模式的文件。

**JSON Schema:**

```json
{
  "type": "object",
  "properties": {
    "path": {
      "type": "string",
      "description": "Starting directory for search"
    },
    "pattern": {
      "type": "string",
      "description": "Glob pattern to match filenames (e.g. *.java, build.*)"
    },
    "maxDepth": {
      "type": "string",
      "description": "Maximum recursion depth, -1 for unlimited (default: unlimited)",
      "default": "-1"
    }
  },
  "required": ["path", "pattern"]
}
```

**返回格式：**

```
Found 3 file(s) matching '*.md':
docs/README.md
docs/alice-tool-gateway/DESIGN.md
README.md
```

**无匹配时：**

```
No files matching '*.xyz' found in /tmp
```

---

### 4.2 内部操作层 (Local Operations)

对本地文件系统进行读写操作。

#### 4.2.1 `read_file`

读取文件内容（UTF-8 编码）。自动拒绝超过 10MB 的文件。

**JSON Schema:**

```json
{
  "type": "object",
  "properties": {
    "path": {
      "type": "string",
      "description": "File path (relative or absolute)"
    }
  },
  "required": ["path"]
}
```

**返回：** 文件的完整内容（纯文本）。

**错误示例：**

```
read_file: file not found: /path/to/nonexist
read_file: not a regular file: /some/dir    ← 路径指向目录
read_file: file too large (20971520 bytes, max=10485760): bigfile.bin
```

---

#### 4.2.2 `write_file`

写入内容到文件。文件不存在时自动创建，包括所有父目录。

**⚠️ 高危工具：** 会修改文件系统。

**JSON Schema:**

```json
{
  "type": "object",
  "properties": {
    "path": {
      "type": "string",
      "description": "File path to write"
    },
    "content": {
      "type": "string",
      "description": "File content to write"
    }
  },
  "required": ["path", "content"]
}
```

**返回：**

```
Wrote 1024 bytes to /path/to/output.txt
```

**注意：** `content` 为 `null` 时自动转为空字符串。

---

#### 4.2.3 `remove_file`

删除一个文件。不会删除目录（安全防护）。

**⚠️ 高危工具：** 会永久删除文件。

**JSON Schema:**

```json
{
  "type": "object",
  "properties": {
    "path": {
      "type": "string",
      "description": "File path to delete"
    }
  },
  "required": ["path"]
}
```

**返回：**

```
Deleted: /tmp/old-file.tmp
```

**幂等（文件不存在）：**

```
File not found (already removed): /tmp/old-file.tmp
```

**拒绝删除目录：**

```
remove_file: refusing to delete directory (use with caution): /some/dir
```

---

### 4.3 执行与检索层 (Execution & Retrieval)

文本搜索与系统命令执行。

#### 4.3.1 `grep`

在文件中搜索匹配正则表达式的行，返回带行号的匹配结果。

**JSON Schema:**

```json
{
  "type": "object",
  "properties": {
    "pattern": {
      "type": "string",
      "description": "Regex pattern to search for"
    },
    "path": {
      "type": "string",
      "description": "File path to search in"
    }
  },
  "required": ["pattern", "path"]
}
```

**返回格式：**

```
Found 2 match(es):
42: private static final Logger logger = ...
157: logger.info("Tool invoked");
```

**无匹配：**

```
No matches found for pattern 'SomethingThatDoesNotExist' in /path/to/file.txt
```

---

#### 4.3.2 `run`

执行 shell 命令。在 Windows 上使用 `cmd.exe /c`，Unix 上使用 `/bin/sh -c`。

**⚠️ 高危工具：** 可执行任意系统命令。

**JSON Schema:**

```json
{
  "type": "object",
  "properties": {
    "command": {
      "type": "string",
      "description": "Shell command to execute"
    }
  },
  "required": ["command"]
}
```

**返回（成功时，exit code 0）：**

```
<stdout output>
```

**返回（失败时，exit code ≠ 0）：**

```
exit=1
<stdout>
<stderr>
```

**注意：** stdout 和 stderr 均通过 `Process.getInputStream()` / `getErrorStream()` 读取。
命令的工作目录默认为项目根目录（包含 `gradlew` 或 `settings.gradle` 的目录）。

---

### 4.4 外部感知层 (External Perception)

通过网络获取互联网实时信息。

#### 4.4.1 `web_search`

使用 DuckDuckGo Instant Answer API 检索互联网。**无需 API key**，返回摘要和链接。

**JSON Schema:**

```json
{
  "type": "object",
  "properties": {
    "query": {
      "type": "string",
      "description": "Search query keywords"
    },
    "maxResults": {
      "type": "string",
      "description": "Maximum number of results (1-10, default 5)",
      "default": "5"
    }
  },
  "required": ["query"]
}
```

**返回格式：**

```
Search results for 'Java 25 features':
Abstract: Java 25 introduces virtual threads improvements, ...
Source: Wikipedia (https://en.wikipedia.org/wiki/Java_version_history)
---
1. JEP 123: Virtual Threads Enhancement
   https://openjdk.org/jeps/123
---
2. Java 25 Release Notes
   https://www.oracle.com/java/25/release-notes
```

**无结果时：**

```
No results found for: asdfghjkl12345nonexistent
```

**网络失败时：**

```
web_search failed: HTTP 503
```

**安全限制：**
- `maxResults` 钳制在 1–10 之间
- 查询参数 URL 编码，防止注入
- 15 秒超时，避免长时间阻塞

---

## 5. LLM Function Calling 调用示例

以下是用 JSON 格式调用各工具的 `arguments` 范例：

```json
// list_dir
{ "path": "docs/alice-tool-gateway" }

// file_exists
{ "path": "README.md" }

// search_file
{ "path": "src", "pattern": "*.java", "maxDepth": "-1" }

// read_file
{ "path": "alice-tool-gateway/src/main/java/module-info.java" }

// write_file
{ "path": "output/result.txt", "content": "Hello, Agent!" }

// remove_file
{ "path": "tmp/old.txt" }

// grep
{ "pattern": "ToolMetadata", "path": "alice-tool-gateway/src/main/java/org/cland/alice/tool/gateway/ToolRegistry.java" }

// run
{ "command": "git log --oneline -5" }

// web_search
{ "query": "Alice Agent framework Java 25", "maxResults": "3" }
```

### 5.1 MCP 工具调用示例

```json
// 调用 MCP Filesystem Server 的 read 工具
// 注册后工具名: "my-fs:read"
{ "path": "/home/user/config.json" }

// 调用 MCP GitHub Server 的 create_issue
// 注册后工具名: "github:create_issue"
{ "owner": "cland", "repo": "alice-agent", "title": "Bug: ...", "body": "..." }

// 调用 MCP Database Server 的 query
// 注册后工具名: "pg-db:query"
{ "sql": "SELECT id, name FROM users WHERE active = true LIMIT 10" }
```

**注意：** MCP 工具的参数名称、类型完全由外部 Server 的 `inputSchema` 定义，与 Builtin 参数约定无关。

## 6. 路径解析规则

所有工具的 `path` 参数遵循相同的解析逻辑：

1. **绝对路径** — 直接使用（如 `C:/Users/me/file.txt` 或 `/home/user/file.txt`）
2. **相对路径** — 相对于项目根目录解析。项目根目录通过向上查找 `gradlew` / `settings.gradle` / `settings.gradle.kts` 确定
3. **路径分隔符** — Unix 风格 `/` 和 Windows 风格 `\` 均支持，返回路径统一使用 `/`

## 7. 错误处理契约

所有工具在异常情况下抛出明确的错误消息（非 Java StackTrace），方便 LLM 理解并重试：

| 错误类型 | 示例消息 | 建议的 Agent 行为 |
|----------|---------|------------------|
| 参数缺失 | `<tool_name>: <param> is required` | 补充缺失参数后重试 |
| 文件不存在 | `<tool_name>: file not found: <path>` | 检查路径拼写，使用 `list_dir` 确认目录 |
| 路径误用 | `<tool_name>: not a directory / not a regular file` | 确认 path 指向的正确类型 |
| 权限/大小限制 | `<tool_name>: file too large (<size> bytes, max=<max>)` | 换用其他工具（如 `grep`）或缩小范围 |
| 网络异常 | `web_search failed: HTTP <code>` 或连接超时 | 稍后重试，或使用本地知识库替代 |
| 命令执行失败 | `exit=<code>\n<stdout>\n<stderr>` | 检查命令语法、环境依赖 |
| MCP 连接未就绪 | `Client not ready, state: DISCONNECTED` | 确认 MCP Server 已连接 |
| MCP 工具未注册 | `Tool not registered: my-server:my-tool` | 检查 serverId 和 toolName 拼写 |
| MCP Server 错误 | 服务端返回错误消息 | 查看返回的错误详情 |

## 8. 工具链典型流程示例

### 8.1 Builtin 纯本地流程

```
用户: "搜索 Java 25 最新特性，并保存到本地文件"

1. web_search(query="Java 25 new features")    → 获取摘要与链接
2. write_file(path="notes/java25-features.md", content=...)  → 持久化
3. read_file(path="notes/java25-features.md")  → 校验保存内容
4. run(command="cat notes/java25-features.md | wc -l")  → 验证行数
```

### 8.2 MCP + Builtin 混合流程

```
用户: "从 GitHub 拉取最新 Issue，分析后保存到本地报告"

1. github:list_issues(owner="cland", repo="alice-agent", state="open")  → MCP 获取 Issue
2. list_dir(path="reports/")                                             → Builtin 检查报告目录
3. write_file(path="reports/issues-2026-06-20.md", content=...)          → Builtin 写入报告
4. grep(pattern="bug", path="reports/issues-2026-06-20.md")              → Builtin 搜索关键字
```

### 8.3 多 MCP 服务器协同

```
用户: "将 Notion 页面内容同步到本地数据库"

1. notion:search(query="Alice Agent Design Docs")       → MCP Notion 搜索
2. notion:read_page(pageId="abc123")                    → MCP Notion 读取
3. database:query(sql="INSERT INTO docs (...) VALUES")  → MCP Database 写入
4. database:query(sql="SELECT * FROM docs")             → MCP Database 校验
```

## 9. 限制说明

- `read_file` 上限 10MB — 超大文件应使用 `grep` 或分段读取（如需要，后续版本会加 `head_file` / `tail_file`）
- `run` 无超时默认值 — 请在调用侧自行控制超时（`ExecutionEngine.invoke()` 有超时参数）
- `web_search` 使用 DuckDuckGo — 返回结果量有限，且摘要为主；如需深度搜索请考虑专用搜索 API
- 所有工具在当前 JVM 进程执行 — 未实现远程沙箱，`HIGH` 风险工具通过 `PolicySandboxProvider` 做路径限制
- MCP 工具依赖外部服务器可用性 — 网络中断/Server 宕机时调用失败
- MCP 工具注册延迟 — 连接建立后需等待 `tools/list` 返回才可用
- MCP 工具参数无本地校验 — 错误的参数直接透传给外部 Server，由其返回错误
