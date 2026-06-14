# Contract: CommandParser (CLI)

> **Module**: `alice-facade-cmd`
> **File**: `CommandParser.java`
> **Package**: `org.cland.alice.facade.cmd.config`

## Subcommand Contract

The CLI parser MUST add a new `routine` subcommand via `cmdLine.addSubcommand("routine", new RoutineCommand())`:

```java
@Command(name = "routine", description = "Register or manage scheduled routine tasks")
private static class RoutineCommand implements Callable<Integer> {

  @Parameters(index = "0", description = "Cron expression or routine definition")
  private String cronExpression;

  @Option(names = {"--list", "-l"}, description = "List registered routines")
  private boolean listRoutines;

  @Option(names = {"--remove", "-r"}, description = "Remove a routine by ID")
  private String removeRoutineId;

  @Override
  public Integer call() { return 0; }

  RunConfig toRunConfig() {
    RunConfig.Builder builder = RunConfig.builder();
    if (cronExpression != null && !cronExpression.isBlank()) {
      builder.routineCron(cronExpression);
    }
    if (listRoutines) {
      builder.listRoutines(true);
    }
    return builder.build();
  }

  AgentCommand toAgentCommand(String sessionId) {
    // For CLI single-shot: convert to RegisterRoutineCmd
    return AgentCommand.parse("/routine " + cronExpression, sessionId, traceId());
  }
}
```

## New RunConfig Fields

`RunConfig` MUST gain two optional fields:

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `routineCron` | `String` | `null` | Cron expression for routine registration |
| `listRoutines` | `boolean` | `false` | Flag to list all registered routines |

The `Builder` class MUST gain corresponding setter methods:
- `builder.routineCron(String)` → sets the cron expression
- `builder.listRoutines(boolean)` → sets the list flag

## CommandParser.parseToAgentCommand Contract

```java
// Existing parsing delegates to AgentCommand.parse() — no change needed:
public AgentCommand parseToAgentCommand(String input) {
  return AgentCommand.parse(input, sessionId, traceId());
}
```

Since `AgentCommand.parse()` now handles `/routine`, this method automatically supports it — no custom logic required.

## End-to-End CLI Flow

| CLI Input | Parser Output | AgentCommand Generated |
|-----------|---------------|----------------------|
| `alice routine "0 */2 * * * ?"` | `RunConfig{routineCron="0 */2 * * * ?"}` | `RegisterRoutineCmd("0 */2 * * * ?", ...)` |
| `alice routine --list` | `RunConfig{listRoutines=true}` | (deferred to agent core) |
| `alice routine --remove "job-1"` | `RunConfig{removeRoutineId="job-1"}` | (deferred to agent core) |
| `alice routine` (no args) | `RunConfig{routineCron=""}` | `RegisterRoutineCmd("", ...)` |
