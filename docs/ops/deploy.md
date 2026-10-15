---
title: "Alice Agent 部署手册（M3-1）"
summary: "alice-agent 的单命令启动、运行形态（CLI/TUI/RPC）、打包分发、配置目录与健康检查"
read_when:
  - "需要部署/启动 alice-agent（单机）"
  - "需要打包分发或排查启动/端口/配置问题"
scope:
  - "alice-agent"
status: "active"
updated: "2026-10-16"
---
# Alice Agent 部署手册（#173 M3-1）

> 目标：**单命令起服务 + 可分发制品 + 配置可外置**。
> 环境要求：**JDK ≥ 25**（Gradle Wrapper 自带，无需本机装 Gradle）。

## 1. 快速开始（单命令）

```bash
./bootstrap.sh setup      # 环境自检（校验 JDK 25 / Gradle Wrapper）
./bootstrap.sh run        # 运行 alice（默认 CLI facade）
./bootstrap.sh run-rpc    # 运行 RPC 服务（HTTP，默认 :8080）
```

`bootstrap.sh` 子命令总览：

| 子命令 | 作用 |
|---|---|
| `setup` | 环境自检（JDK/Gradle） |
| `build` | 全量构建（含测试） |
| `run [args]` | 运行（默认 CLI facade，参数透传） |
| `run-tui [args]` | 运行 TUI facade |
| `run-rpc [args]` | 运行 RPC facade（HTTP） |
| `test` | 单元测试 |
| `check` | `check`（含 JaCoCo 覆盖率门禁） |
| `package` | 打包（installDist + distTar/distZip） |
| `shell` | 进入项目 shell（已导出 `JAVA_HOME`） |
| `clean` | 清理构建产物 |

## 2. 运行形态

alice 使用 **SPI 动态发现 facade**（`--facade <name>` 选择，默认 `cli`）：

| 形态 | 命令 | 说明 |
|---|---|---|
| CLI | `./bootstrap.sh run` | 交互命令行前端 |
| TUI | `./bootstrap.sh run-tui` | 终端全屏前端 |
| **RPC（服务）** | `./bootstrap.sh run-rpc` | **常驻 HTTP 服务**（协议面 `/api/v1`） |
| RPC stdio | `./gradlew :alice-bootstrap:run --args="--facade rpc --stdio"` | stdio JSONL（被外部调度器驱动） |

RPC 常用参数（透传）：`--port <n>`（默认 `8080`，`0`=随机）、`--session <id>`、`--model <id>`、`--no-wal`。
端口亦可用环境变量：`ALICE_RPC_PORT=9090 ./bootstrap.sh run-rpc`。

## 3. 打包与分发

```bash
./bootstrap.sh package
```

产物（`alice-bootstrap/build/`）：

| 产物 | 路径 | 用途 |
|---|---|---|
| 解包即用目录 | `install/alice/bin/alice` | 直接运行（含依赖 jar） |
| tar 分发包 | `distributions/alice-<version>.tar` | 分发/部署 |
| zip 分发包 | `distributions/alice-<version>.zip` | 分发/部署 |

分发后在目标机运行：

```bash
tar -xzf alice-0.1.0.tar -C /opt
/opt/alice-0.1.0/bin/alice --facade rpc --port 8080
```

## 4. 配置与目录约定

所有用户级数据落在 **`~/.alice/`**：

| 路径 | 内容 |
|---|---|
| `~/.alice/config.json` | 持久化配置（`AliceConfigStore`） |
| `~/.alice/model.json` | 默认模型（`default_model`） |
| `~/.alice/wal/<session>/` | WAL / checkpoint（`--no-wal` 可关） |
| `~/.alice/sops/` | SOP 脚本目录 |
| `~/.alice/logs/` | 日志（M3-2：`info.log` / `error.log`，JSON 行） |

> 配置默认无需改动；首次运行缺失项会自动创建/使用默认值。

## 5. 健康检查

RPC 形态提供健康端点（`HostHealth` JSON）：

```bash
curl -s http://127.0.0.1:8080/api/v1/health
```

## 6. 退出码约定

| 码 | 含义 |
|---|---|
| `0` | 正常退出 |
| `1` | 运行时错误 |
| `2` | 参数/配置错误 |
| `70` | RPC 装配失败 |
| `74` | RPC 启动失败（如端口占用） |
| `130` | 用户中断（Ctrl+C） |

## 7. 升级 / 回滚

1. 停旧进程（`Ctrl+C` / `kill`，有 shutdown hook 优雅退出）；
2. 解包新版本到新目录（`/opt/alice-<version>`）；
3. 软链切换：`ln -sfn /opt/alice-<version> /opt/alice`；
4. 回滚 = 软链指回旧版本目录（`~/.alice/` 用户数据不受影响）。

## 8. 关联

- **M3-2 可观测**：结构化日志（JSON Lines，`info.log`/`error.log`）+ 基础指标（见后续文档）。
- **M3-3 门禁**：`./bootstrap.sh check`（JaCoCo 覆盖率门禁）+ gate 尾巴。
- **M3-4 运维对接**：部署脚本 / cron / CMDB 登记（归 @steward）。
