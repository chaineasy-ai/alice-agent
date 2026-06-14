# Contract: SlashCommand (TUI)

> **Module**: `alice-facade-tui`
> **File**: `SlashCommand.java`, `CommandHandler.java`
> **Package**: `org.cland.alice.facade.tui.command`

## SlashCommand.parse() Contract

The `SlashCommand.parse()` switch expression MUST add a case for `/routine`:

```java
case "/routine" ->
    new SlashCommand(cmd, args, Type.CONFIG, "注册定时任务：注册 Cron 表达式到调度器");
```

| Input | Expected SlashCommand |
|-------|----------------------|
| `"/routine 0 */2 * * * ?"` | `SlashCommand("/routine", "0 */2 * * * ?", Type.CONFIG, ...)` |
| `"/routine"` | `SlashCommand("/routine", "", Type.CONFIG, ...)` |
| Non-slash input | `null` (unchanged) |

## CommandHandler.handleConfig() Contract

The `handleConfig()` method MUST add a case for `/routine`:

```java
if (cmd.is("/routine")) {
  if (!cmd.hasArgs()) {
    eventBridge.onChatMessage("System", "用法: /routine <cron表达式>");
    return true;
  }

  String cronExpr = cmd.args();
  eventBridge.onChatMessage("System", "注册定时任务: " + cronExpr);

  // Convert to RegisterRoutineCmd and dispatch
  AgentCommand ac = cmd.toAgentCommand(sessionId(), traceId());
  dispatchToAgent(ac);

  return true;
}
```

| Command | Expected Behavior |
|---------|------------------|
| `/routine 0 */2 * * * ?` | Dispatch `RegisterRoutineCmd("0 */2 * * * ?", ...)`, show confirmation |
| `/routine` | Show usage message "用法: /routine <cron表达式>" (no dispatch) |

## SlashCommand.helpText() Contract

The `/routine` command MUST appear in the help text output:

```
/routine <cron>  注册定时任务：注册 Cron 表达式到调度器
```

## SlashCommand.toAgentCommand() Contract

Unchanged — `toAgentCommand()` calls `AgentCommand.parse(raw, sessionId, traceId)` where `raw = "/routine " + args`. Since `AgentCommand.parse()` now handles `/routine`, this automatically returns `RegisterRoutineCmd`.

## End-to-End TUI Flow

| TUI Input | SlashCommand | AgentCommand Dispatched |
|-----------|-------------|------------------------|
| `/routine 0 */2 * * * ?` | `Type.CONFIG` | `RegisterRoutineCmd("0 */2 * * * ?", ...)` |
| `/routine` | Usage message shown | (none) |
| Natural language | `null` | `AcquireGoalCmd` (unchanged) |
