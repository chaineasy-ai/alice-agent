明白了，配置文件路径为 `~/.alice/model.json`。以下是针对该路径的完整配置说明文档。

---

## model.json 配置说明文档

### 1. 文件位置

```
~/.alice/model.json
```

即用户主目录下的 `.alice` 目录中：

| 操作系统 | 完整路径 |
|----------|----------|
| Linux / macOS | `/home/[username]/.alice/model.json` |
| macOS | `/Users/[username]/.alice/model.json` |
| Windows | `C:\Users\[username]\.alice\model.json` |

---

### 2. 创建配置文件

```bash
# 创建配置目录
mkdir -p ~/.alice

# 创建配置文件
touch ~/.alice/model.json
```

---

### 3. 完整配置模板

将以下内容写入 `~/.alice/model.json`：

```json
{
  "language_models": {
    "openai_compatible": {
      "my-provider": {
        "api_url": "https://api.myprovider.com/v1",
        "api_key": "${MY_PROVIDER_API_KEY}",
        "available_models": [
          {
            "name": "deepseek",
            "max_tokens": 200000,
            "max_output_tokens": 32000,
            "max_completion_tokens": 200000,
            "capabilities": {
              "tools": true,
              "images": true,
              "parallel_tool_calls": true,
              "prompt_cache_key": true,
              "chat_completions": true
            }
          }
        ]
      }
    }
  }
}
```

---

### 4. 字段说明表

| 层级 | 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|------|
| 根 | `language_models` | object | ✅ | 语言模型配置根节点 |
| 根 | `openai_compatible` | object | ✅ | OpenAI 兼容接口标识 |
| 2 | `[provider_name]` | object | ✅ | 提供商自定义名称（如 `my-provider`） |
| 3 | `api_url` | string | ✅ | API 基础 URL，如 `https://api.xxx.com/v1` |
| 3 | `api_key` | string | ❌ | API 密钥，支持 `${ENV_VAR}` 环境变量引用 |
| 3 | `available_models` | array | ✅ | 支持的模型列表 |
| 4 | `name` | string | ✅ | 模型名称，调用时使用的标识符 |
| 4 | `max_tokens` | integer | ✅ | 最大上下文总长度（输入 + 输出 token 数） |
| 4 | `max_output_tokens` | integer | ✅ | 单次最大输出 token 数 |
| 4 | `max_completion_tokens` | integer | ✅ | 补全接口最大 token 数 |
| 5 | `capabilities.tools` | boolean | ✅ | 是否支持工具/函数调用 |
| 5 | `capabilities.images` | boolean | ✅ | 是否支持图像输入（多模态） |
| 5 | `capabilities.parallel_tool_calls` | boolean | ✅ | 是否支持并行工具调用 |
| 5 | `capabilities.prompt_cache_key` | boolean | ✅ | 是否支持提示缓存键 |
| 5 | `capabilities.chat_completions` | boolean | ✅ | 是否支持 `/chat/completions` 端点 |

---

### 5. 多提供商配置示例

```json
{
  "language_models": {
    "openai_compatible": {
      "openai": {
        "api_url": "https://api.openai.com/v1",
        "api_key": "${OPENAI_API_KEY}",
        "available_models": [
          {
            "name": "gpt-4-turbo",
            "max_tokens": 128000,
            "max_output_tokens": 4096,
            "max_completion_tokens": 128000,
            "capabilities": {
              "tools": true,
              "images": true,
              "parallel_tool_calls": true,
              "prompt_cache_key": false,
              "chat_completions": true
            }
          },
          {
            "name": "gpt-4o",
            "max_tokens": 128000,
            "max_output_tokens": 16384,
            "max_completion_tokens": 128000,
            "capabilities": {
              "tools": true,
              "images": true,
              "parallel_tool_calls": true,
              "prompt_cache_key": false,
              "chat_completions": true
            }
          }
        ]
      },
      "deepseek": {
        "api_url": "https://api.deepseek.com/v1",
        "api_key": "${DEEPSEEK_API_KEY}",
        "available_models": [
          {
            "name": "deepseek-v4-flash",
            "max_tokens": 200000,
            "max_output_tokens": 32000,
            "max_completion_tokens": 200000,
            "capabilities": {
              "tools": true,
              "images": false,
              "parallel_tool_calls": true,
              "prompt_cache_key": true,
              "chat_completions": true
            }
          }
        ]
      },
      "local": {
        "api_url": "http://localhost:8080/v1",
        "available_models": [
          {
            "name": "llama3",
            "max_tokens": 8192,
            "max_output_tokens": 2048,
            "max_completion_tokens": 8192,
            "capabilities": {
              "tools": false,
              "images": false,
              "parallel_tool_calls": false,
              "prompt_cache_key": false,
              "chat_completions": true
            }
          }
        ]
      }
    }
  }
}
```

---

### 6. 环境变量配置

在 `~/.bashrc`、`~/.zshrc` 或 `.env` 文件中设置：

```bash
# ~/.bashrc 或 ~/.zshrc
export MY_PROVIDER_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxx
export OPENAI_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxx
export DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxx
```

或创建 `~/.alice/.env`：

```bash
# ~/.alice/.env
MY_PROVIDER_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxx
OPENAI_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxx
DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxx
```

---

### 7. 代码加载示例

```python
import json
import os
from pathlib import Path

def load_model_config():
    config_path = Path.home() / ".alice" / "model.json"
    
    if not config_path.exists():
        raise FileNotFoundError(f"Config file not found: {config_path}")
    
    with open(config_path, 'r') as f:
        config = json.load(f)
    
    # 展开环境变量
    def expand_env(value):
        if isinstance(value, str) and value.startswith("${") and value.endswith("}"):
            env_var = value[2:-1]
            return os.environ.get(env_var, value)
        return value
    
    # 递归展开所有 api_key
    providers = config.get("language_models", {}).get("openai_compatible", {})
    for provider in providers.values():
        if "api_key" in provider:
            provider["api_key"] = expand_env(provider["api_key"])
    
    return config

# 使用
config = load_model_config()
print(config["language_models"]["openai_compatible"]["my-provider"]["api_url"])
```

```javascript
// Node.js
const fs = require('fs');
const path = require('path');
const os = require('os');

function loadModelConfig() {
  const configPath = path.join(os.homedir(), '.alice', 'model.json');
  
  if (!fs.existsSync(configPath)) {
    throw new Error(`Config file not found: ${configPath}`);
  }
  
  const config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
  
  // 展开环境变量
  const expandEnv = (value) => {
    if (typeof value === 'string' && value.startsWith('${') && value.endsWith('}')) {
      const envVar = value.slice(2, -1);
      return process.env[envVar] || value;
    }
    return value;
  };
  
  const providers = config.language_models?.openai_compatible || {};
  for (const key of Object.keys(providers)) {
    if (providers[key].api_key) {
      providers[key].api_key = expandEnv(providers[key].api_key);
    }
  }
  
  return config;
}

// 使用
const config = loadModelConfig();
console.log(config.language_models.openai_compatible['my-provider'].api_url);
```

---

### 8. 配置校验规则

| 校验项 | 规则 | 错误示例 |
|--------|------|----------|
| `max_tokens` | ≥ `max_output_tokens` | `max_tokens: 1000, max_output_tokens: 2000` ❌ |
| `max_completion_tokens` | 建议等于 `max_tokens` | 相差较大时可能产生歧义 |
| `api_url` | 必须以 `http://` 或 `https://` 开头 | `api.example.com/v1` ❌ |
| `name` | 非空字符串 | `""` ❌ |
| `capabilities` | 所有布尔字段必须显式设置 | 缺失字段可能导致运行时错误 |

---

### 9. 配置文件权限建议

```bash
# 设置目录权限（仅用户可读写）
chmod 700 ~/.alice

# 设置文件权限（仅用户可读写）
chmod 600 ~/.alice/model.json
```

---

### 10. 常见问题

| 问题 | 解决方案 |
|------|----------|
| 配置文件找不到 | 确认 `~/.alice/model.json` 是否存在，目录名是否正确（注意点号） |
| API Key 未生效 | 检查环境变量名称是否与配置中 `${VAR_NAME}` 一致，确认已 `source ~/.bashrc` |
| 权限错误 | 执行 `chmod 600 ~/.alice/model.json` 修复权限 |
| JSON 格式错误 | 使用 `jq . ~/.alice/model.json` 验证 JSON 格式 |

---

如需进一步调整（如添加自定义 headers、代理配置、超时设置等），请告知具体需求，我可以帮您扩展配置结构。
