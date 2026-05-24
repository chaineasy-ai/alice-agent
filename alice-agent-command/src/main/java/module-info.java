/**
 * alice-agent-command — Agent 指令抽象层
 *
 * <p>定义 {@link org.cland.alice.agent.command.AgentCommand} 密封接口体系， 供 facade-cmd 与 facade-tui 共同依赖。
 *
 * <p>指令分类（对应 docs/app/AgentCommand.md）：
 *
 * <ul>
 *   <li>{@code ExecutionCmd} — 任务驱动（/run, /exec）
 *   <li>{@code CapabilityCmd} — 能力装载（/skill, /rules, /reload）
 *   <li>{@code AlignmentCmd} — 运行配置（/model）
 *   <li>{@code ControlCmd} — 控制与反馈（/new, /feedback, /exit）
 * </ul>
 */
module alice.agent.command.main {
  exports org.cland.alice.agent.command;

  requires org.slf4j;
}
