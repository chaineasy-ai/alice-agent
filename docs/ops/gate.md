---
title: "Alice Agent 提交门禁（M3-3）"
summary: "统一门禁入口 scripts/check.sh、gate.yaml 声明项、本地 pre-commit hook 与 CI/Jenkins 用法"
read_when:
  - "提交被门禁拦截或需要调整门禁项时"
  - "了解 check 各 stage / 覆盖率阈值 / 文档 front-matter 要求时"
scope:
  - "alice-agent"
status: "active"
updated: "2026-10-16"
---
# Alice Agent 提交门禁（#173 M3-3）

> 规范：`cland-pm/docs/门禁规范.md` §1/§2/§8（统一形状，**不自造**）。

## 1. 单一入口

```bash
./scripts/check.sh --stage fast     # 提交秒级子集（pre-commit 用）
./scripts/check.sh --stage all      # 全量（CI/Jenkins 用）
./scripts/check.sh --stage slow     # 慢速门禁（test/coverage）
```

`scripts/check.sh` **只转调 `tools/gate.py`**；语言命令全部声明在 `gate.yaml`。

## 2. 门禁项（`gate.yaml`）

| 序 | 项 | stage | 命令 |
|---|---|---|---|
| 1 | `doclint` | fast | `tools/doclint.py`（front-matter：`title`/`summary`/`read_when` 2–4 条） |
| 2 | `structure` | fast | 目录契约（`alice-bootstrap/src`、`docs`、`scripts`、`tools`） |
| 3 | `format` | fast | `./gradlew -q spotlessCheck` |
| 4 | `lint` | — | N/A（Java 风格由 Spotless 覆盖） |
| 5 | `typecheck` | — | N/A（编译期保证） |
| 6 | `test` | slow | `./gradlew test` |
| 7 | `coverage` | slow | `./gradlew jacocoTestCoverageVerification`（BUNDLE 阈值） |
| 8 | `rules` | — | N/A |
| 9 | `exposure` | — | N/A（发布前启用） |

- **doclint 排除**：`.specify`/`specs`（spec 模板脚手架）、`.github`（模板）、`tests`/`e2e`（测试夹具）、`.pi`（pi 配置）——非产品文档。
- **覆盖率阈值**：`alice-core-agent` INSTRUCTION ≥ 40% / BRANCH ≥ 25%，其余模块 80% / 70%（见根 `build.gradle`）。

## 3. 本地 hook

```bash
bash scripts/install-hooks.sh     # 安装 .git/hooks/pre-commit（gate.py --stage fast --changed）
```

- pre-commit 只跑 **stage=fast 且命中暂存文件** 的检查器；违规拒绝提交。
- 升级 runner：重跑 `project-creator/scripts/install-gate-tail.sh`（`gate.py`/`doclint.py` 覆盖更新；`gate.yaml`/`check.sh` 不覆盖）。

## 4. CI / Jenkins

CI 调 `./scripts/check.sh --stage all`（与本地同入口）。本仓当前为 GitHub Actions，门禁入口一致，迁移不阻塞。
