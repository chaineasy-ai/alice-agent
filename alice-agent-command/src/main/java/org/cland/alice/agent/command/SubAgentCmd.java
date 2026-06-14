/*
 * Alice Agent — Sub-Agent Commands（多 Agent 子命令）
 *
 * 第六个密封分支，代表子 Agent 相关的指令。
 * 支持 Alice Agent 内创建子 Agent、连接外部 ACP 协议 Agent、以及管理子 Agent 生命周期。
 *
 * 包含 7 个具体记录类型：
 *   SpawnSubAgentCmd      — /sub-agent spawn       （创建子 Agent）
 *   ConnectSubAgentCmd    — /sub-agent connect     （连接外部 ACP Agent）
 *   ListSubAgentsCmd      — /sub-agent list        （列出子 Agent）
 *   CancelSubAgentCmd     — /sub-agent cancel      （取消子 Agent）
 *   GetSubAgentResultsCmd — /sub-agent results     （获取子 Agent 结果）
 *   SendToSubAgentCmd     — /sub-agent send        （向子 Agent 发送消息）
 *   PromptSubAgentCmd     — /sub-agent prompt      （向外部 ACP Agent 发送提示）
 */
package org.cland.alice.agent.command;

/**
 * 子 Agent 指令 — 多 Agent 管理与通信。
 *
 * <p>继承自 {@link AgentCommand}，密封许可给 7 个具体指令类型，涵盖子 Agent 的创建、连接、管理、通信全生命周期。
 */
public sealed interface SubAgentCmd extends AgentCommand
    permits SpawnSubAgentCmd,
        ConnectSubAgentCmd,
        ListSubAgentsCmd,
        CancelSubAgentCmd,
        GetSubAgentResultsCmd,
        SendToSubAgentCmd,
        PromptSubAgentCmd {

  /**
   * 子 Agent 标识符或名称。
   *
   * <p>对于 spawn/connect 操作，此值为目标/描述；对于管理操作，此值为子 Agent ID。
   */
  String target();
}
