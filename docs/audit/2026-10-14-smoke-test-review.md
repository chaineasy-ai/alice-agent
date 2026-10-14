# alice-agent 冒烟测试用例 · 独立评审与实跑报告

- 单号：base/cland-crawler#515（承 #173）
- 评审/执行：probe（独立验证）
- 日期：2026-10-14
- 被测基线：`alice-agent@121cb21`（HEAD 与工作树一致）
- 对象：`docs/Agent 冒烟测试用例规范文档.md` + `e2e/smoke/` 3 例实现
- 运行方式：**独立实跑**（非采信他人自述）：使用自建脚本直接驱动已构建的 JVM 分发包 `alice/bin/alice`，并注入 `--graph-kernel=on|off`；为隔离共享工作树，在独立 git worktree（`/tmp/probe515/wt`）内运行，日志隔离到私有 `user.home`。

---

## 0. 结论速览

| 项 | 结论 |
|---|---|
| AC-1 逐例判据可判性 | **可判 pass/fail**，但存在「退出码判据不可靠」「自省断言缺失」「检索/执行证据靠共享日志正则」等弱点（见 §2） |
| AC-2 on/off 实跑 | **已亲跑**：off = 10/10 PASS；on = 7/10（3 失败）。随附原始回显 + 日志 `graphKernel` 生效值 |
| AC-3 判定与断言是否对得上 | 能对上；但发现 **exit code 与任务是否完成双向背离**（on：改对了却 exit=1；off/早期 run：未改却 exit=0） |
| AC-4 可否作门禁 | **暂不可作硬性合并门禁**；宜作「手动/夜间冒烟 + 本地 mock 模型」回归基线，待 5 项前置条件满足（见 §5） |

---

## 1. 实跑证据（原始回显）

### 1.1 命令（自建隔离驱动）
```
# 在独立 worktree 中，使用分发包直接运行（不走 Gradle run，规避并发构建锁）
PROBE_ROOT=/tmp/probe515/wt
PROBE_BIN=/tmp/probe515/wt/alice-bootstrap/build/install/alice/bin/alice
HOME=/tmp/probe515/home  ALICE_OPTS="-Duser.home=/tmp/probe515/home"
python3 /tmp/probe515/run_smoke_isolated.py <on|off>
```
> 说明：驱动在测试方法调用 `run_agent()` 前，把 `build_agent_command` 注入 `--graph-kernel=<on|off>`，其余复用 `e2e/smoke/*` 原用例断言。

### 1.2 OFF 路径（`--graph-kernel=off`）
```
### probe isolated smoke: root=/tmp/probe515/wt --graph-kernel=off ###
  ✅ Agent exit=0
  ✅ Agent exit=0
  ✅ Agent exit=0
Ran 10 tests in 217.656s
OK
### probe summary kernel=off: ran=10 failures=0 errors=0 ###
```

### 1.3 ON 路径（`--graph-kernel=on`）
```
### probe isolated smoke: root=/tmp/probe515/wt --graph-kernel=on ###
  ⚠️  Agent exit=1     # case1：任务实际已改对，却因 “Task timed out after 96s” 退出 1
  ✅ Agent exit=0      # case2：全过
  ✅ Agent exit=0      # case3：exit=0，但 parser.py 未修复
FAIL: test_agent_exits_cleanly (test_smoke_case_1)          # exit=1（假失败）
FAIL: test_parser_handles_invalid_json (test_smoke_case_3)  # try/except 缺失
FAIL: test_pytest_all_pass_after_fix (test_smoke_case_3)    # pytest 1 failed, 1 passed
FAILED (failures=3)
### probe summary kernel=on: ran=10 failures=3 errors=0 ###
```

### 1.4 内核开关生效值（私有 `~/.alice/logs/alice-agent.log` 增量）
```
21:40:46 [graphKernel] enabled=false (source=cli)   # off case1
21:41:31 [graphKernel] enabled=false (source=cli)   # off case2
21:43:26 [graphKernel] enabled=false (source=cli)   # off case3
21:45:36 [graphKernel] enabled=true  (source=cli)   # on  case1
21:47:15 [graphKernel] enabled=true  (source=cli)   # on  case2
21:50:12 [graphKernel] enabled=true  (source=cli)   # on  case3
```

### 1.5 参考：官方 runner（Gradle 路径）与并发锁
- 单独跑 `python3 -m e2e.smoke.runner smoke__case-1 --no-build` → **2/2 PASS**（Agent exit=0，17.4s）。
- 批量跑 `python3 -m e2e.smoke.runner --no-build` 在同一共享工作树撞到并发 Gradle 构建：
  ```
  > 检测到另一个 Gradle 构建正在运行：并发构建会互删 build/test-results，导致 EOF/NoSuchFile 假失败。
  BUILD FAILED in 1s
  Ran 10 tests ... FAILED (failures=10)
  ```
  即在共享工作树下，一个并发构建会把冒烟套件判成 **10 个测试全失败**（而非明确报「环境忙」）。

### 1.6 环境事实
- 基线模型：`deepseek-v4-flash`（`e2e/smoke/config.py`），走 `https://api.deepseek.com`（外部付费 API）。
- 卡片提到的本地 `Gemma4 @ http://192.168.1.14:10303/v1` 在本机 **不可达**（`http=000`），故本次全链路走 DeepSeek。
- `pytest` 未声明为依赖（无 `e2e/requirements.txt`，`run_alice_e2e.sh` 引用的 requirements.txt 不存在）；需手工 `pip --target` 装载后 `PYTHONPATH` 注入才能跑 Case 3。

---

## 2. T1 逐例判据可判性（AC-1）

### Case 1 基础工具与文件编辑闭环
- `test_agent_exits_cleanly`：判据 `exit==0`。**可判，但不可靠**（见 §3）。
- `test_divide_zero_raises_value_error`：文件含 `raise ValueError` **且真 import 运行 `divide(1,0)` 抛 `ValueError`** → 真校验，可判。
- 缺口：未断言规范要求的**精确消息** `Division by zero is not allowed`；规范中的 `predictions.jsonl/model_patch + git diff 格式` 断言未实现（实现改为校验文件终态，回避了补丁产物格式）。

### Case 2 多工具协同与跨文件依赖
- `test_timeout_renamed_across_files`：两文件 `TIMEOUT_SEC` 存在 + 旧名 `TIMEOUT_MS` 消失 → 可判。
- `test_unit_conversion_correct`：正则 `^\s*TIMEOUT_SEC\s*[:=]\s*5(\.0+)?$` → 可判，但对写法较脆（如写 `5000/1000` 会误判）。
- `test_agent_searched_for_old_field`：日志正则 `grep|search_file` → 可判但**弱**（依赖共享 `~/.alice/logs` 增量，并发时会被他人日志污染）。
- 规范要求的「model_patch 同时含两文件」被文件内容检查替代，方向合理。

### Case 3 沙箱执行 + TDD 自省
- `test_pytest_all_pass_after_fix`：外部真跑 pytest 断言 `2 passed` → **最强判据**，可判。
- `test_parser_handles_invalid_json`：`try`/`except` 子串 + 运行时 `parse_payload('not valid json') == {"error":"invalid"}` → 真校验，可判（子串检查较弱，运行时检查兜底）。
- `test_agent_ran_the_test_suite`：日志正则 `pytest|unittest` → 可判但弱（同 §Case2 污染风险）。
- **规范—实现缺口**：规范断言 #2「首次修复未通过应触发自省（Reflection）/二次迭代」在实现里**完全未断言**。

---

## 3. T1 关键缺陷：exit code 与任务完成双向背离（AC-3）

同一套用例、同一次跑的实测：
- **on case1**：文件已正确改为 `raise ValueError('Division by zero is not allowed')`，`test_divide_zero_raises_value_error` = ok，但 `test_agent_exits_cleanly` = FAIL（`Task timed out after 96s`）。
- **早期 off run（主树）case2**：`Agent exit=0`，但 `config.py` 未改（`TIMEOUT_SEC` 缺失）→ 内容断言 FAIL。
- **on case3**：`Agent exit=0`，但 `parser.py` 未修、pytest 仍 1 failed。

⇒ `test_agent_exits_cleanly` **既非必要也非充分**：不能以退出码代表任务完成，也不能以任务完成反推退出码。这是「判据与断言对不上」的最直接证据。

---

## 4. T3 冒烟未覆盖的核心链路（缺口清单）

1. **内核 on/off 未参数化**：`e2e/smoke/agent_run.py:build_agent_command` 从不传 `--graph-kernel`，官方 runner 只会跑默认 **on**，无法覆盖 off 回退路径（本次需自建注入才能测 off）。→ 与 #513 AC-3 口径相关。
2. **无非 happy-path**：缺 文件不存在/无权限/非法路径、工具报错恢复、沙箱异常、超时/中断（Ctrl-C）后清理。
3. **无持久化/会话链路**：WAL 落盘、`resume` 历史会话、多轮 chat 上下文未验。
4. **无护栏/内存**：guardrail、memory-vault 检索未验。
5. **无并发/隔离**：多 Agent 共享工作树、并发 Gradle、工作树污染（#417）未纳入冒烟。
6. **无 native-image 路径**：CI 构建三平台 native 二进制，但冒烟只测 JVM 分发包。
7. **无成本/时延基线**：单例依赖外部 LLM，无 token/耗时上限断言，无法防劣化。
8. **依赖未声明**：pytest、API key、网络都靠外层环境，未在套件内 bootstrap（无 requirements）。
9. **自省/二次迭代断言缺失**（规范 Case3 #2）。

---

## 5. T4 结论：可否作合并门禁 / 回归基线（AC-4）

**结论：暂不可作硬性合并门禁；可作「手动/夜间冒烟 + 回归基线」，但需先补齐前置条件。**

### 不建议直接当门禁的理由（均有实测支撑）
1. **未接入 CI**：`.github/workflows/ci.yml` 只有 assemble/test/installDist/nativeCompile，**0 处引用 smoke**。
2. **不稳定**：同一套件不同轮次得到 0 / 2 / 3 个失败（本轮 off=10/10、on=7/10；主树早期 off=8/10）。LLM 驱动的用例天然抖动。
3. **依赖外部付费 API + key + 网络**：本地 Gemma4 不可达时全部走 DeepSeek，成本与可用性不可控。
4. **退出码契约不可靠**（§3）。
5. **共享工作树下会被并发 Gradle 构建判成整体失败**（§1.5）。
6. **依赖未声明、fixture 恢复不完整**：恢复仅覆盖快照内的已跟踪文件，Agent 新建的未跟踪文件（实测出现过 `math_utils/test_math_utils.py`）不被清理，仅由 #417 守卫「发现并判红」，不负责清场。

### 改进建议（优先级）
- **P0 退出码契约**：明确「任务完成信号」与进程退出码的映射；图内核 on 路径需修掉「任务已完成却 `Task timed out`」的假超时。
- **P0 内核参数化**：runner 增 `--graph-kernel on|off`（或复用配置键），让 3 例在 on/off 各跑一遍成为套件内置能力。
- **P0 确定性**：引入本地 mock/录制模型（或固定 seed + 本地 Gemma4），把冒烟从「外部 LLM 抖动」中解耦；否则只做告警、不做阻断。
- **P1 自省断言**：补规范 Case3 #2 的「首修未过 → 二次迭代」证据（可从日志中解析工具调用序列）。
- **P1 环境自举**：补 `e2e/requirements.txt` 并让 runner 自动校验 pytest/API key；修 `run_alice_e2e.sh` 的幽灵 requirements 引用。
- **P1 隔离**：默认在独立 git worktree + 私有 `user.home` 运行（日志/WAL/config 不共享），从根上消除并发与污染。
- **P2 覆盖扩展**：补 §4 的负路径/持久化/护栏/native 路径。

---

## 6. 证据与可复现
- 实跑原始日志：`/tmp/probe515/run_off_1.log`、`/tmp/probe515/run_on_1.log`
- 内核生效值日志：`/tmp/probe515/agent-iso-final.log`
- 驱动脚本：`/tmp/probe515/run_smoke_isolated.py`、`/tmp/probe515/launch.sh`
- 并发锁失败回显：`/tmp/probe515/all_on2.out`
- 独立运行 worktree：`/tmp/probe515/wt`（HEAD `121cb21`）
