---
read_when:
  - running Alice Agent in TUI mode
scope:
  - alice-facade-tui
status: active
summary: Quick start guide to run Alice Agent in TUI mode
title: TUI Quick Start
updated: "2026-06-13"
---

# RUN AGENT ON TUI MODE

```bash
./alice-bootstrap/build/install/alice-agent/bin/alice-agent --tui
```

# win

```
@echo off
chcp 65001 >nul
alice-bootstrap.exe
```
OR
```powershell
# 设置控制台UTF8，屏蔽chcp输出
chcp 65001 | Out-Null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
```
