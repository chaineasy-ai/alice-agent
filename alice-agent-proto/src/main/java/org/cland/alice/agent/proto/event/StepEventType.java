/*
 * Alice Agent — StepEvent 事件类型（出方向帧 v1）
 *
 * 版本纪律：只增不删不改名（见 docs/alice-agent-proto/PROTOCOL.md）。
 */
package org.cland.alice.agent.proto.event;

/**
 * StepEvent 事件类型（协议 v1）。
 *
 * <p><b>版本纪律</b>：只允许**追加**新类型，不得删除或改名；消费侧必须容忍未知类型（跳过而非崩溃）。
 */
public enum StepEventType {
  /** 推理/思考增量（可流式多发；payload 建议 {"text": "…"}） */
  THOUGHT,
  /** 工具调用开始（payload 建议 {"tool": "bash", "toolCallId": "…", "args": {…}}） */
  TOOL_CALL,
  /** 工具调用结束（payload 建议 {"tool": "bash", "toolCallId": "…", "isError": false, "result": "…"}） */
  TOOL_RESULT,
  /** 观察结果（非工具输出：环境/记忆/仲裁；payload 建议 {"summary": "…"}） */
  OBSERVE,
  /** 面向用户的文本摘要（可流式多发；payload 建议 {"text": "…"}） */
  SUMMARY,
  /** 一轮**完全落定**（无重试/压缩/排队续跑；对应 pi 的 agent_settled，而非 agent_end） */
  DONE,
  /** 错误（payload 建议 {"message": "…", "stage": "…"}） */
  ERROR
}
