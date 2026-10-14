# alice-agent 冒烟「合并门禁」判据口径（#521 · P-1 off 择一：b）

- 单号：base/cland-crawler#521（承 #515 · P-1）
- 决策：mint（实现）· atlas（裁定）
- 日期：2026-10-14
- 被测基线：`alice-agent@1d23564`（P-1 合入 main 后）
- 关联：`#515`（评审报告 `docs/audit/2026-10-14-smoke-test-review.md`）· `#520`（图内核 on 修复）· `#521`（升门禁 5 前置）

---

## 1. 背景

`#515` 实测发现 **exit code 与任务完成双向背离**：

- on：任务改对却 `exit=1`（假超时）—— 已由 `#520` A+B + `#521` P-1 修复；
- off（legacy）：任务未改却 `exit=0` —— legacy 无“任务完成”可判信号，无法可靠判定。

atlas 裁定：off known-issue 必须在 **AC-3（挂门禁）前二选一**，且 **不允许 off 退出码“既作判据又不可靠”**。

## 2. 决策（择 **(b)**）

> **off（legacy）退出码不作门禁判据**（仅信息/对照）。门禁判据只断言 **on 路径**。

理由：off 现有 `AgentContext` 不含“任务完成”可靠信号（`FINISH` 只代表循环结束），无法区分“改对/未改”。与其把不可靠信号混入门禁，不如显式豁免。

## 3. 落地

| 层 | 实现 |
|---|---|
| 会话终态 | `GraphSessionKernel` / `ExecutionCoordinator` 统一打印 `[SESSION_END] graphKernel=… outcome=… success=… gate=…` |
| on | `gate=on`；`exit=0 ⇔ settled && outcome=FINISHED`；`FAILED/CANCELLED/未收敛/timeout → 非 0` |
| off | `gate=exempt(legacy:exit-not-a-gate-criterion)`；退出码不参与门禁判定（信息/对照用） |

日志示例：

```
[SESSION_END] graphKernel=true  outcome=FINISHED success=true  gate=on
[SESSION_END] graphKernel=false outcome=<unknown> success=null gate=exempt(legacy:exit-not-a-gate-criterion)
```

## 4. 门禁判据（#521 P-1..P-5 全绿后挂载，AC-3）

1. **on 路径**：`[SESSION_END] outcome=FINISHED && success=true` 且 smoke 内容断言全过（exit 必须 0）；
2. **off 路径**：仅作对照/信息，**其退出码不作判据**（本档显式声明）；
3. 弱断言（如 `test_agent_searched_for_old_field`）须改 outcome-based —— 归 `#521` P-4。

## 5. 复现

```bash
export JAVA_HOME=~/.sdkman/candidates/java/current
cd ~/work/chaineasy/alice-agent
# on：应 exit=0 且日志 [SESSION_END] gate=on outcome=FINISHED success=true
./gradlew :alice-facade-cli:run --console=plain \
  --args="run '列出本项目根目录下 .md 文件的数量，只回答数字' --graph-kernel=on --verbose"
# off：日志 [SESSION_END] gate=exempt(...)；退出码不作门禁判据
./gradlew :alice-facade-cli:run --console=plain \
  --args="run '列出本项目根目录下 .md 文件的数量，只回答数字' --graph-kernel=off --verbose"
```
