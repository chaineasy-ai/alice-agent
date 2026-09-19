/*
 * Alice Agent — 命令编解码（协议 v1）
 *
 * 覆盖范围（只增不改）：prompt/exec/steer/abort/new/resume/clear/context/compact/feedback；
 * 其余命令（Capability/Alignment/RoutineTime/SubAgent 家族）暂未覆盖，按版本纪律后续追加。
 * 详见 docs/alice-agent-proto/PROTOCOL.md。
 */
package org.cland.alice.agent.proto.codec;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.cland.alice.agent.proto.AgentCommand;
import org.cland.alice.agent.proto.ControlCmd;
import org.cland.alice.agent.proto.ExecutionCmd;
import org.cland.alice.agent.proto.port.CommandValidationException;

/**
 * 命令编解码 — AgentCommand ↔ 协议 v1 线格式（JSON）。
 *
 * <p>用法：transport/门面统一调本类，**禁止各自定义 JSON 结构**（契约唯一真源）。
 *
 * <p>错误：不可编码（未覆盖的命令类型）或不可解码（非法 JSON/缺字段/版本不支持）时抛 {@link CommandValidationException}（协议面 → 400）。
 */
public final class CommandCodec {

  private CommandCodec() {}

  /**
   * 命令 → JSON 帧。
   *
   * @param cmd 命令（不得为 null）
   * @return 单行 JSON（信封形态）
   * @throws CommandValidationException 该命令类型 v1 未覆盖 / 序列化失败
   */
  public static String encode(AgentCommand cmd) {
    Objects.requireNonNull(cmd, "cmd must not be null");
    CommandEnvelope env = toEnvelope(cmd);
    try {
      return Json.MAPPER.writeValueAsString(env);
    } catch (JsonProcessingException e) {
      throw new CommandValidationException("命令序列化失败：" + e.getOriginalMessage(), e);
    }
  }

  /**
   * JSON 帧 → 命令。
   *
   * @param json 单行 JSON（信封形态；未知字段忽略）
   * @return 命令
   * @throws CommandValidationException 非法 JSON / 版本不支持 / 未知类型 / 缺必填字段
   */
  public static AgentCommand decode(String json) {
    CommandEnvelope env;
    try {
      env = Json.MAPPER.readValue(json == null ? "" : json, CommandEnvelope.class);
    } catch (Exception e) {
      throw new CommandValidationException("命令帧不是合法 JSON：" + e.getMessage(), e);
    }
    if (env.v() != CommandEnvelope.V1) {
      throw new CommandValidationException("不支持的协议版本 v=" + env.v() + "（当前支持 v=1）");
    }
    String sid = env.sessionId();
    String tid = env.traceId();
    Instant ts = env.ts();
    return switch (env.type()) {
      case "prompt" -> new ExecutionCmd.AcquireGoalCmd(required(env, "message"), sid, tid, ts);
      case "exec" -> new ExecutionCmd.ExecuteRawCmd(required(env, "command"), sid, tid, ts);
      case "steer" -> new ControlCmd.SteerCmd(required(env, "message"), sid, tid, ts);
      case "abort" -> new ControlCmd.AbortCmd(sid, tid, ts);
      case "new" -> new ControlCmd.ResetSessionCmd(sid, tid, ts);
      case "resume" -> new ControlCmd.ResumeSessionCmd(sid, tid, optional(env, "snapshotId"), ts);
      case "clear" -> new ControlCmd.ClearContextCmd(sid, tid, ts);
      case "context" -> new ControlCmd.ViewContextCmd(sid, tid, ts);
      case "compact" -> new ControlCmd.CompactContextCmd(sid, tid, ts);
      case "feedback" -> new ControlCmd.FeedbackCmd(required(env, "message"), sid, tid, ts);
      default -> throw new CommandValidationException("v1 未知命令类型：" + env.type());
    };
  }

  // ──────────────────────────────────────────────────────────────────────────
  // 内部：命令 → 信封
  // ──────────────────────────────────────────────────────────────────────────

  private static CommandEnvelope toEnvelope(AgentCommand cmd) {
    return switch (cmd) {
      case ExecutionCmd.AcquireGoalCmd c -> env(c, "prompt", payload("message", c.goal()));
      case ExecutionCmd.ExecuteRawCmd c -> env(c, "exec", payload("command", c.command()));
      case ControlCmd.SteerCmd c -> env(c, "steer", payload("message", c.message()));
      case ControlCmd.AbortCmd c -> env(c, "abort", Map.of());
      case ControlCmd.ResetSessionCmd c -> env(c, "new", Map.of());
      case ControlCmd.ResumeSessionCmd c -> env(c, "resume", payload("snapshotId", c.snapshotId()));
      case ControlCmd.ClearContextCmd c -> env(c, "clear", Map.of());
      case ControlCmd.ViewContextCmd c -> env(c, "context", Map.of());
      case ControlCmd.CompactContextCmd c -> env(c, "compact", Map.of());
      case ControlCmd.FeedbackCmd c -> env(c, "feedback", payload("message", c.message()));
      default ->
          throw new CommandValidationException(
              "v1 暂不支持编解码：" + cmd.getClass().getSimpleName() + "（按版本纪律可追加覆盖）");
    };
  }

  private static CommandEnvelope env(AgentCommand cmd, String type, Map<String, Object> payload) {
    return new CommandEnvelope(
        CommandEnvelope.V1, type, cmd.sessionId(), cmd.traceId(), cmd.timestamp(), payload);
  }

  private static Map<String, Object> payload(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i + 1 < kv.length; i += 2) {
      if (kv[i + 1] != null) {
        m.put(String.valueOf(kv[i]), kv[i + 1]);
      }
    }
    return m;
  }

  private static String required(CommandEnvelope env, String key) {
    String v = optional(env, key);
    if (v == null || v.isBlank()) {
      throw new CommandValidationException("命令 " + env.type() + " 缺少必填字段：" + key);
    }
    return v;
  }

  private static String optional(CommandEnvelope env, String key) {
    Object v = env.payload().get(key);
    return v == null ? null : String.valueOf(v);
  }
}
