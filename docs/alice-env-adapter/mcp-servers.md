---
title: "MCP Servers Configuration"
summary: "MCP 2.0 server configuration for Alice Agent — manage external MCP servers (stdio/SSE), tool discovery, and connection lifecycle"
read_when:
  - "configuring MCP server connections (stdio or SSE)"
  - "adding new MCP tools to the agent toolbox"
  - "understanding MCP transport types and connection parameters"
  - "troubleshooting MCP server connectivity or tool registration"
scope:
  - "alice-env-adapter"
status: "active"
updated: "2026-06-20"
---

# MCP Servers Configuration

> **MCP (Model Context Protocol)** is an open protocol that standardizes how applications
> provide context and tools to LLMs. Alice Agent implements MCP 2.0 as an **MCP Client**,
> connecting to external MCP Servers to discover and invoke tools.

## 1. Configuration File

MCP servers are configured in a separate config file at:

```
~/.alice/mcp.json
```

This file is **independent** from `~/.alice/config.json`. The `alice-env-adapter` module
loads it at startup to discover which MCP servers to connect.

### 1.1 Template

See [`~/.alice/mcp.json.example`](https://github.com/chaineasy-ai/alice-agent/tree/main/alice-env-adapter/src/main/resources/mcp.json.example)
for a template with common server setups (filesystem, github, sqlite, playwright, slack).

<details>
<summary>Click to expand example</summary>

```json
{
  "mcp_servers": {
    "filesystem": {
      "type": "stdio",
      "command": "npx",
      "args": [
        "-y",
        "@modelcontextprotocol/server-filesystem",
        "/workspace"
      ],
      "auto_connect": true
    },
    "github": {
      "type": "stdio",
      "command": "npx",
      "args": [
        "-y",
        "@modelcontextprotocol/server-github"
      ],
      "env": {
        "GITHUB_TOKEN": "${env:GITHUB_TOKEN}"
      }
    }
  }
}
```
</details>

### 1.2 Field Reference

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `type` | string | ✅ | Transport type: `"stdio"` or `"sse"` |
| `command` | string | for `stdio` | Executable command (path or name in PATH) |
| `args` | string[] | for `stdio` | Command arguments |
| `url` | string | for `sse` | SSE endpoint URL |
| `env` | object | optional | Extra environment variables for the subprocess |
| `disabled` | boolean | optional | Set `true` to disable a server without removing it |
| `auto_connect` | boolean | optional | Connect on startup (default `true`) |

### 1.3 Stdio Transport

Connects to a local MCP server subprocess via its stdio streams.
Used for Filesystem, Sqlite, Git, and other local tools.

```json
{
  "mcp_servers": {
    "filesystem": {
      "type": "stdio",
      "command": "npx",
      "args": [
        "-y",
        "@modelcontextprotocol/server-filesystem",
        "/workspace"
      ]
    }
  }
}
```

### 1.4 SSE Transport

Connects to a remote MCP server over HTTP Server-Sent Events.
Used for cloud services, databases, and remote APIs.

```json
{
  "mcp_servers": {
    "playwright": {
      "type": "sse",
      "url": "https://mcp.playwright.company.com/sse"
    }
  }
}
```

## 2. Supported MCP Server Types

### 2.1 Stdio Servers

| Server | Command | Notes |
|--------|---------|-------|
| **Filesystem** | `npx -y @modelcontextprotocol/server-filesystem <dirs...>` | Provides file read/write/search/search_dir |
| **GitHub** | `npx -y @modelcontextprotocol/server-github` | Requires `GITHUB_TOKEN` env var |
| **Sqlite** | `npx -y @modelcontextprotocol/server-sqlite <db_path>` | Read-only SQL queries on a local DB |
| **Git** | `npx -y @modelcontextprotocol/server-git <repo_path>` | Git operations on local repos |
| **Playwright (local)** | `npx -y @playwright/mcp` | Browser automation |
| **Python** | `uvx server-name` | Any Python MCP server via uvx |

### 2.2 SSE Servers

| Server | URL Pattern | Notes |
|--------|-------------|-------|
| **Playwright (remote)** | `https://<host>/sse` | Remote browser automation |
| **Custom HTTP** | `http://<host>:<port>/mcp/sse` | Custom MCP servers |
| **Cloud Gateway** | `https://<host>/mcp/sse` | Cloud-hosted MCP proxies |

## 3. Environment Variable Injection

Use the `${env:VAR_NAME}` syntax in `command`, `args`, or `env` values
to reference environment variables. Alice Agent will resolve them at runtime.

```json
{
  "mcp_servers": {
    "github": {
      "type": "stdio",
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-github"],
      "env": {
        "GITHUB_TOKEN": "${env:GITHUB_TOKEN}"
      }
    }
  }
}
```

Known environment variables for common servers:

| Server | Variable | Purpose |
|--------|----------|---------|
| GitHub | `GITHUB_TOKEN` | GitHub personal access token |
| Slack | `SLACK_BOT_TOKEN` | Slack bot token |
| Postgres | `DATABASE_URL` | Database connection string |
| Custom | `HOME` | User home directory (built-in) |

## 4. Lifecycle & Connection Flow

On Alice Agent startup (or via `/mcp connect` command):

```
1. Read ~/.alice/mcp.json → parse mcp_servers entries
2. For each non-disabled server with auto_connect=true:
   a. Resolve ${env:*} variables
   b. Create MCP transport and client
   c. Connect → MCP 2.0 handshake → tools/list
   d. Discovered tools are registered into ToolRegistry
3. Tools become available for Agent function calling
```

Disconnect (on shutdown or via `/mcp disconnect <serverId>`):

```
1. Disconnect client
2. Unregister all tools from that server
3. Release resources
```

## 5. Config Commands

Create or edit `~/.alice/mcp.json` directly:

```bash
# Create the file with an editor
vim ~/.alice/mcp.json

# Or use a one-liner to add a server
cat > ~/.alice/mcp.json << 'EOF'
{
  "mcp_servers": {
    "filesystem": {
      "type": "stdio",
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/workspace"]
    }
  }
}
EOF
```

## 6. Troubleshooting

### Common Issues

| Symptom | Likely Cause | Fix |
|---------|-------------|-----|
| `Client not ready, state: DISCONNECTED` | Server not connected or failed to start | Check command/args, verify MCP server runs standalone |
| `Tool not registered: filesystem:read` | Server's `tools/list` returned empty or failed | Check server logs (visible in verbose mode) |
| Connection timeout | SSE endpoint unreachable | Verify URL, firewall, server status |
| `Failed to start MCP subprocess` | Command not found or path invalid | Use absolute paths or verify PATH |
| Duplicate serverId warning | Two servers with same key in config | Use unique keys per server |

### Debug Mode

Run with verbose logging to see MCP handshake and tool discovery:

```bash
alice --verbose run "list files in /workspace"
# or
alice --verbose chat
```

Verbose output includes:
- MCP transport connection and handshake
- Tool discovery process
- Tool invocation details

## 7. Related Documents

- [USER_TOOLS.md](../alice-tool-gateway/USER_TOOLS.md) — Consumer guide for calling MCP tools
- [DESIGN.md](./DESIGN.md) — MCP client architecture
- [inbound.md](../alice-tool-gateway/inbound.md) — Tool registry inbound ports
