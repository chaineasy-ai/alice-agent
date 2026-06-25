package org.cland.alice.facade.cmd.config;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 运行配置，封装 CLI 解析后的所有参数。
 *
 * <p>对应设计文档中 {@code RunConfig} 实体，由 {@code CommandParser} 创建， 传递给 {@code ExecutionCoordinator} 驱动
 * Agent 执行。
 *
 * <pre>
 *   RunConfig {
 *     String task;          // 任务描述（必填）
 *     String model;         // 模型 ID（可选，覆盖默认）
 *     boolean jsonOutput;   // JSON 输出模式
 *     boolean verbose;      // 详细输出
 *     long timeoutSeconds;  // 超时时间
 *     Map&lt;String,String&gt; envVars; // 额外环境变量
 *     String routineCron;   // 定时任务 Cron 表达式（可选）
 *     boolean listRoutines; // 列出已注册定时任务
 *     String subAgentSpawnGoal;    // /sub-agent spawn 目标（可选）
 *     String subAgentConnectName;  // /sub-agent connect 名称（可选）
 *     String subAgentConnectEndpoint; // ACP 端点 URL
 *     boolean subAgentList;        // /sub-agent list
 *     String subAgentCancelId;     // /sub-agent cancel ID
 *     String subAgentResultsId;    // /sub-agent results ID
 *     String subAgentSendId;       // /sub-agent send ID
 *     String subAgentSendMessage;  // 发送的消息内容
 *     String subAgentPromptAgentId;    // /sub-agent prompt 目标 ID
 *     String subAgentPromptText;       // 提示文本
 *     boolean listTools;       // 列出工具
 *     boolean toolDetail;      // 工具详情
 *     String configAction;     // config 动作 (get/set/show)
 *     String configKey;        // config 键名
 *     String configValue;      // config 值 (仅 set 时)
 *     boolean resumeMode;      // resume 模式
 *     String resumeSnapshot;   // resume 快照 ID（可选）
 *     boolean resumeList;      // 列出可恢复会话
 *   }
 * </pre>
 */
public final class RunConfig {

  /** 默认模型 */
  public static final String DEFAULT_MODEL = "gpt-4o-mini";

  /** 默认超时（180 秒） */
  public static final long DEFAULT_TIMEOUT_SECONDS = 180;

  private final String task;
  private final String model;
  private final boolean chat;
  private final boolean jsonOutput;
  private final boolean verbose;
  private final long timeoutSeconds;
  private final Map<String, String> envVars;
  private final String routineCron;
  private final boolean listRoutines;
  private final String subAgentSpawnGoal;
  private final String subAgentConnectName;
  private final String subAgentConnectEndpoint;
  private final boolean subAgentList;
  private final String subAgentCancelId;
  private final String subAgentResultsId;
  private final String subAgentSendId;
  private final String subAgentSendMessage;
  private final String subAgentPromptAgentId;
  private final String subAgentPromptText;
  private final boolean listTools;
  private final boolean toolDetail;
  private final String sessionId;
  private final String configAction;
  private final String configKey;
  private final String configValue;
  private final boolean resumeMode;
  private final String resumeSnapshot;
  private final boolean resumeList;

  private RunConfig(Builder builder) {
    this.task = Objects.requireNonNull(builder.task, "task must not be null");
    this.sessionId = builder.sessionId;
    this.model = builder.model != null ? builder.model : DEFAULT_MODEL;
    this.chat = builder.chat;
    this.jsonOutput = builder.jsonOutput;
    this.verbose = builder.verbose;
    this.timeoutSeconds =
        builder.timeoutSeconds > 0 ? builder.timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
    this.envVars = builder.envVars != null ? Map.copyOf(builder.envVars) : Map.of();
    this.routineCron = builder.routineCron;
    this.listRoutines = builder.listRoutines;
    this.subAgentSpawnGoal = builder.subAgentSpawnGoal;
    this.subAgentConnectName = builder.subAgentConnectName;
    this.subAgentConnectEndpoint = builder.subAgentConnectEndpoint;
    this.subAgentList = builder.subAgentList;
    this.subAgentCancelId = builder.subAgentCancelId;
    this.subAgentResultsId = builder.subAgentResultsId;
    this.subAgentSendId = builder.subAgentSendId;
    this.subAgentSendMessage = builder.subAgentSendMessage;
    this.subAgentPromptAgentId = builder.subAgentPromptAgentId;
    this.subAgentPromptText = builder.subAgentPromptText;
    this.listTools = builder.listTools;
    this.toolDetail = builder.toolDetail;
    this.configAction = builder.configAction;
    this.configKey = builder.configKey;
    this.configValue = builder.configValue;
    this.resumeMode = builder.resumeMode;
    this.resumeSnapshot = builder.resumeSnapshot;
    this.resumeList = builder.resumeList;
  }

  // ========== Getters ==========

  /** 会话 ID（客户端传入，可为 null） */
  public String sessionId() {
    return sessionId;
  }

  /** 任务描述 */
  public String task() {
    return task;
  }

  /** 模型 ID */
  public String model() {
    return model;
  }

  /** 是否进入交互式 chat 模式 */
  public boolean chat() {
    return chat;
  }

  /** 是否启用 JSON 输出 */
  public boolean jsonOutput() {
    return jsonOutput;
  }

  /** 是否打印详细信息 */
  public boolean verbose() {
    return verbose;
  }

  /** 任务超时（秒） */
  public long timeoutSeconds() {
    return timeoutSeconds;
  }

  /** 额外环境变量 */
  public Map<String, String> envVars() {
    return envVars;
  }

  /** 定时任务 Cron 表达式（CLI {@code alice routine} 子命令设置） */
  public String routineCron() {
    return routineCron;
  }

  /** 是否列出已注册定时任务 */
  public boolean listRoutines() {
    return listRoutines;
  }

  /** /sub-agent spawn 目标 */
  public String subAgentSpawnGoal() {
    return subAgentSpawnGoal;
  }

  /** /sub-agent connect 名称 */
  public String subAgentConnectName() {
    return subAgentConnectName;
  }

  /** ACP 端点 URL */
  public String subAgentConnectEndpoint() {
    return subAgentConnectEndpoint;
  }

  /** 是否列出子 Agent */
  public boolean subAgentList() {
    return subAgentList;
  }

  /** /sub-agent cancel ID */
  public String subAgentCancelId() {
    return subAgentCancelId;
  }

  /** /sub-agent results ID */
  public String subAgentResultsId() {
    return subAgentResultsId;
  }

  /** /sub-agent send ID */
  public String subAgentSendId() {
    return subAgentSendId;
  }

  /** /sub-agent send 消息内容 */
  public String subAgentSendMessage() {
    return subAgentSendMessage;
  }

  /** /sub-agent prompt 目标 ID */
  public String subAgentPromptAgentId() {
    return subAgentPromptAgentId;
  }

  /** /sub-agent prompt 文本 */
  public String subAgentPromptText() {
    return subAgentPromptText;
  }

  /** 是否列出已注册工具 */
  public boolean listTools() {
    return listTools;
  }

  /** 是否显示工具详情 */
  public boolean toolDetail() {
    return toolDetail;
  }

  /** Config 动作 (get/set/show) */
  public String configAction() {
    return configAction;
  }

  /** Config 键名 */
  public String configKey() {
    return configKey;
  }

  /** Config 值 (仅 set 时) */
  public String configValue() {
    return configValue;
  }

  /** 是否启用 resume 模式 */
  public boolean resumeMode() {
    return resumeMode;
  }

  /** resume 快照 ID（可选） */
  public String resumeSnapshot() {
    return resumeSnapshot;
  }

  /** 是否列出可恢复会话 */
  public boolean resumeList() {
    return resumeList;
  }

  // ========== Builder ==========

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String task;
    private String model;
    private boolean chat;
    private boolean jsonOutput;
    private boolean verbose;
    private long timeoutSeconds;
    private Map<String, String> envVars;
    private String routineCron;
    private boolean listRoutines;
    private String subAgentSpawnGoal;
    private String subAgentConnectName;
    private String subAgentConnectEndpoint;
    private boolean subAgentList;
    private String subAgentCancelId;
    private String subAgentResultsId;
    private String subAgentSendId;
    private String subAgentSendMessage;
    private String subAgentPromptAgentId;
    private String subAgentPromptText;
    private boolean listTools;
    private boolean toolDetail;
    private String sessionId;
    private String configAction;
    private String configKey;
    private String configValue;
    private boolean resumeMode;
    private String resumeSnapshot;
    private boolean resumeList;

    private Builder() {}

    public Builder task(String task) {
      this.task = task;
      return this;
    }

    public Builder model(String model) {
      this.model = model;
      return this;
    }

    public Builder chat(boolean chat) {
      this.chat = chat;
      return this;
    }

    public Builder jsonOutput(boolean jsonOutput) {
      this.jsonOutput = jsonOutput;
      return this;
    }

    public Builder verbose(boolean verbose) {
      this.verbose = verbose;
      return this;
    }

    public Builder timeoutSeconds(long timeoutSeconds) {
      this.timeoutSeconds = timeoutSeconds;
      return this;
    }

    public Builder envVars(Map<String, String> envVars) {
      this.envVars = envVars;
      return this;
    }

    public Builder envVar(String key, String value) {
      if (this.envVars == null) {
        this.envVars = new HashMap<>();
      }
      this.envVars.put(key, value);
      return this;
    }

    public Builder routineCron(String routineCron) {
      this.routineCron = routineCron;
      return this;
    }

    public Builder listRoutines(boolean listRoutines) {
      this.listRoutines = listRoutines;
      return this;
    }

    public Builder subAgentSpawnGoal(String goal) {
      this.subAgentSpawnGoal = goal;
      return this;
    }

    public Builder subAgentConnectName(String name) {
      this.subAgentConnectName = name;
      return this;
    }

    public Builder subAgentConnectEndpoint(String endpoint) {
      this.subAgentConnectEndpoint = endpoint;
      return this;
    }

    public Builder subAgentList(boolean list) {
      this.subAgentList = list;
      return this;
    }

    public Builder subAgentCancelId(String id) {
      this.subAgentCancelId = id;
      return this;
    }

    public Builder subAgentResultsId(String id) {
      this.subAgentResultsId = id;
      return this;
    }

    public Builder subAgentSendId(String id) {
      this.subAgentSendId = id;
      return this;
    }

    public Builder subAgentSendMessage(String msg) {
      this.subAgentSendMessage = msg;
      return this;
    }

    public Builder subAgentPromptAgentId(String id) {
      this.subAgentPromptAgentId = id;
      return this;
    }

    public Builder subAgentPromptText(String text) {
      this.subAgentPromptText = text;
      return this;
    }

    public Builder listTools(boolean listTools) {
      this.listTools = listTools;
      return this;
    }

    public Builder toolDetail(boolean toolDetail) {
      this.toolDetail = toolDetail;
      return this;
    }

    public Builder sessionId(String sessionId) {
      this.sessionId = sessionId;
      return this;
    }

    public Builder configAction(String configAction) {
      this.configAction = configAction;
      return this;
    }

    public Builder configKey(String configKey) {
      this.configKey = configKey;
      return this;
    }

    public Builder configValue(String configValue) {
      this.configValue = configValue;
      return this;
    }

    public Builder resumeMode(boolean resumeMode) {
      this.resumeMode = resumeMode;
      return this;
    }

    public Builder resumeSnapshot(String resumeSnapshot) {
      this.resumeSnapshot = resumeSnapshot;
      return this;
    }

    public Builder resumeList(boolean resumeList) {
      this.resumeList = resumeList;
      return this;
    }

    public RunConfig build() {
      return new RunConfig(this);
    }
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("RunConfig{task='")
        .append(task)
        .append("', model='")
        .append(model)
        .append("', jsonOutput=")
        .append(jsonOutput)
        .append(", verbose=")
        .append(verbose)
        .append(", sessionId='")
        .append(sessionId)
        .append("', timeout=")
        .append(timeoutSeconds)
        .append("s");
    if (routineCron != null) {
      sb.append(", routineCron='").append(routineCron).append("'");
    }
    if (listRoutines) {
      sb.append(", listRoutines=true");
    }
    if (subAgentSpawnGoal != null) {
      sb.append(", subAgentSpawnGoal='").append(subAgentSpawnGoal).append("'");
    }
    if (subAgentConnectName != null) {
      sb.append(", subAgentConnectName='").append(subAgentConnectName).append("'");
    }
    if (subAgentConnectEndpoint != null) {
      sb.append(", subAgentConnectEndpoint='").append(subAgentConnectEndpoint).append("'");
    }
    if (subAgentList) {
      sb.append(", subAgentList=true");
    }
    if (subAgentCancelId != null) {
      sb.append(", subAgentCancelId='").append(subAgentCancelId).append("'");
    }
    if (subAgentResultsId != null) {
      sb.append(", subAgentResultsId='").append(subAgentResultsId).append("'");
    }
    if (subAgentSendId != null) {
      sb.append(", subAgentSendId='").append(subAgentSendId).append("'");
    }
    if (subAgentSendMessage != null) {
      sb.append(", subAgentSendMessage='").append(subAgentSendMessage).append("'");
    }
    if (subAgentPromptAgentId != null) {
      sb.append(", subAgentPromptAgentId='").append(subAgentPromptAgentId).append("'");
    }
    if (subAgentPromptText != null) {
      sb.append(", subAgentPromptText='").append(subAgentPromptText).append("'");
    }
    if (listTools) {
      sb.append(", listTools=true");
    }
    if (toolDetail) {
      sb.append(", toolDetail=true");
    }
    if (configAction != null) {
      sb.append(", configAction='").append(configAction).append("'");
    }
    if (configKey != null) {
      sb.append(", configKey='").append(configKey).append("'");
    }
    if (configValue != null) {
      sb.append(", configValue='").append(configValue).append("'");
    }
    if (resumeMode) {
      sb.append(", resumeMode=true");
    }
    if (resumeSnapshot != null) {
      sb.append(", resumeSnapshot='").append(resumeSnapshot).append("'");
    }
    if (resumeList) {
      sb.append(", resumeList=true");
    }
    sb.append("}");
    return sb.toString();
  }
}
