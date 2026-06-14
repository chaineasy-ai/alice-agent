/*
 * FileWalStore — 基于 JSONL 本地文件的 WalStore 实现
 *
 * 每条消息以 JSON 单行 (JSONL) 格式追加到 `<dataDir>/<sessionId>.wal.jsonl`。
 * Checkpoint 以单独文件 `<dataDir>/<sessionId>.checkpoint.json` 存储（同 session 覆盖）。
 *
 * 零外部依赖（Jackson/Gson），内建轻量 JSON 序列化。
 * 适合开发/单机部署。生产环境可替换为 PostgresWalStore。
 */
package org.cland.alice.memory.wal;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 JSONL 文件的 {@link WalStore} 实现。
 *
 * <p>数据布局:
 *
 * <pre>
 *   &lt;dataDir&gt;/
 *     &lt;sessionId&gt;.wal.jsonl       — WAL 消息（Append-Only）
 *     &lt;sessionId&gt;.checkpoint.json — 最新 Checkpoint（覆盖）
 * </pre>
 *
 * <p>零外部依赖，內建 JSON 序列化/反序列化。
 */
public final class FileWalStore implements WalStore {

  private static final Logger log = LoggerFactory.getLogger(FileWalStore.class);

  private static final String WAL_SUFFIX = ".wal.jsonl";
  private static final String CHECKPOINT_SUFFIX = ".checkpoint.json";

  private final Path dataDir;
  private final AtomicLong messageIdSeq = new AtomicLong(1);
  private final AtomicLong checkpointIdSeq = new AtomicLong(1);

  /** sessionId → 内存索引 */
  private final ConcurrentMap<String, SessionIndex> index = new ConcurrentHashMap<>();

  /** sessionId → 最新 Checkpoint（内存缓存） */
  private final ConcurrentMap<String, Checkpoint> checkpointCache = new ConcurrentHashMap<>();

  /** 序列 ID 持久化文件 */
  private final Path seqFile;

  public FileWalStore(String dataDir) {
    this(Paths.get(dataDir));
  }

  public FileWalStore(Path dataDir) {
    this.dataDir = dataDir.toAbsolutePath().normalize();
    this.seqFile = this.dataDir.resolve("_seq");
    init();
  }

  private void init() {
    try {
      Files.createDirectories(dataDir);
      if (Files.exists(seqFile)) {
        String content = Files.readString(seqFile).trim();
        if (!content.isEmpty()) {
          long seq = Long.parseLong(content);
          messageIdSeq.set(seq + 1);
          checkpointIdSeq.set(seq + 1);
        }
      }
      rebuildIndex();
    } catch (IOException e) {
      log.warn("Failed to initialize FileWalStore: {}", e.getMessage());
    }
  }

  private void rebuildIndex() throws IOException {
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dataDir, "*" + WAL_SUFFIX)) {
      for (Path path : stream) {
        String fileName = path.getFileName().toString();
        String sessionId = fileName.substring(0, fileName.length() - WAL_SUFFIX.length());
        long lastId = 0;
        int count = 0;
        List<String> lines = Files.readAllLines(path);
        count = lines.size();
        for (String line : lines) {
          if (!line.isBlank()) {
            RawMessage msg = parseMessage(line);
            if (msg != null && msg.messageId() > lastId) lastId = msg.messageId();
          }
        }
        index.put(sessionId, new SessionIndex(lastId, count));

        Path cpFile = dataDir.resolve(sessionId + CHECKPOINT_SUFFIX);
        if (Files.exists(cpFile)) {
          String content = Files.readString(cpFile).trim();
          if (!content.isEmpty()) {
            Checkpoint cp = parseCheckpoint(content);
            if (cp != null) checkpointCache.put(sessionId, cp);
          }
        }
      }
    }
  }

  // ========== RawMessage 操作 ==========

  @Override
  public long appendMessage(RawMessage message) {
    long id = message.messageId() > 0 ? message.messageId() : messageIdSeq.getAndIncrement();
    RawMessage stored =
        new RawMessage(
            id,
            message.sessionId(),
            message.role(),
            message.content(),
            message.toolCalls(),
            message.toolCallId(),
            message.name(),
            message.timestamp(),
            message.metadata());
    writeMessage(stored);
    updateSeq(id);
    return id;
  }

  @Override
  public long appendMessages(List<RawMessage> messages) {
    long lastId = 0;
    for (RawMessage msg : messages) {
      lastId = appendMessage(msg);
    }
    return lastId;
  }

  private void writeMessage(RawMessage message) {
    Path file = dataDir.resolve(message.sessionId() + WAL_SUFFIX);
    try {
      String json = toJson(message) + System.lineSeparator();
      Files.writeString(file, json, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      index.compute(
          message.sessionId(),
          (k, v) -> {
            if (v == null) return new SessionIndex(message.messageId(), 1);
            return new SessionIndex(message.messageId(), v.count() + 1);
          });
    } catch (IOException e) {
      throw new RuntimeException("Failed to write WAL message", e);
    }
  }

  @Override
  public Optional<RawMessage> getMessage(long messageId) {
    for (var entry : index.entrySet()) {
      List<RawMessage> msgs = loadMessages(entry.getKey());
      for (RawMessage msg : msgs) {
        if (msg.messageId() == messageId) return Optional.of(msg);
      }
    }
    return Optional.empty();
  }

  @Override
  public List<RawMessage> getMessagesAfter(String sessionId, long afterId, int limit) {
    return getAllMessages(sessionId).stream()
        .filter(m -> m.messageId() > afterId)
        .limit(limit)
        .collect(Collectors.toList());
  }

  @Override
  public List<RawMessage> getAllMessages(String sessionId) {
    return loadMessages(sessionId);
  }

  private List<RawMessage> loadMessages(String sessionId) {
    Path file = dataDir.resolve(sessionId + WAL_SUFFIX);
    if (!Files.exists(file)) return List.of();
    try {
      List<String> lines = Files.readAllLines(file);
      List<RawMessage> result = new ArrayList<>(lines.size());
      for (String line : lines) {
        if (line.isBlank()) continue;
        RawMessage msg = parseMessage(line);
        if (msg != null) result.add(msg);
      }
      return result;
    } catch (IOException e) {
      log.warn("Failed to read WAL file: {}", e.getMessage());
      return List.of();
    }
  }

  @Override
  public int deleteMessagesUpTo(String sessionId, long upToId) {
    List<RawMessage> all = loadMessages(sessionId);
    List<RawMessage> remaining =
        all.stream().filter(m -> m.messageId() > upToId).collect(Collectors.toList());
    Path file = dataDir.resolve(sessionId + WAL_SUFFIX);
    try {
      if (remaining.isEmpty()) {
        Files.deleteIfExists(file);
        index.remove(sessionId);
      } else {
        String content =
            remaining.stream().map(this::toJson).collect(Collectors.joining(System.lineSeparator()))
                + System.lineSeparator();
        Files.writeString(
            file, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        long lastId = remaining.get(remaining.size() - 1).messageId();
        index.put(sessionId, new SessionIndex(lastId, remaining.size()));
      }
    } catch (IOException e) {
      throw new RuntimeException("Failed to compact WAL file", e);
    }
    return all.size() - remaining.size();
  }

  @Override
  public int messageCount(String sessionId) {
    SessionIndex idx = index.get(sessionId);
    return idx != null ? idx.count() : 0;
  }

  // ========== Checkpoint 操作 ==========

  @Override
  public long saveCheckpoint(Checkpoint checkpoint) {
    long id =
        checkpoint.checkpointId() > 0
            ? checkpoint.checkpointId()
            : checkpointIdSeq.getAndIncrement();
    Checkpoint stored =
        new Checkpoint(
            id,
            checkpoint.sessionId(),
            checkpoint.lastAppliedMessageId(),
            checkpoint.stateNode(),
            checkpoint.variableSnapshot(),
            checkpoint.planSnapshot(),
            checkpoint.createdAt());
    Path file = dataDir.resolve(checkpoint.sessionId() + CHECKPOINT_SUFFIX);
    try {
      Files.writeString(
          file, toJson(stored), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
      checkpointCache.put(stored.sessionId(), stored);
      updateSeq(id);
      return id;
    } catch (IOException e) {
      throw new RuntimeException("Failed to write checkpoint", e);
    }
  }

  @Override
  public Optional<Checkpoint> getLatestCheckpoint(String sessionId) {
    Checkpoint cached = checkpointCache.get(sessionId);
    if (cached != null) return Optional.of(cached);

    Path file = dataDir.resolve(sessionId + CHECKPOINT_SUFFIX);
    if (!Files.exists(file)) return Optional.empty();
    try {
      String content = Files.readString(file).trim();
      if (content.isEmpty()) return Optional.empty();
      Checkpoint cp = parseCheckpoint(content);
      if (cp != null) {
        checkpointCache.put(sessionId, cp);
        return Optional.of(cp);
      }
      return Optional.empty();
    } catch (IOException e) {
      log.warn("Failed to read checkpoint file: {}", e.getMessage());
      return Optional.empty();
    }
  }

  @Override
  public List<Checkpoint> getCheckpointHistory(String sessionId, int limit) {
    return getLatestCheckpoint(sessionId).map(cp -> List.of(cp)).orElseGet(List::of);
  }

  @Override
  public int deleteCheckpointsUpTo(String sessionId, long upToCheckpointId) {
    Optional<Checkpoint> current = getLatestCheckpoint(sessionId);
    if (current.isPresent() && current.get().checkpointId() <= upToCheckpointId) {
      Path file = dataDir.resolve(sessionId + CHECKPOINT_SUFFIX);
      try {
        Files.deleteIfExists(file);
        checkpointCache.remove(sessionId);
        return 1;
      } catch (IOException e) {
        log.warn("Failed to delete checkpoint file: {}", e.getMessage());
      }
    }
    return 0;
  }

  @Override
  public int checkpointCount(String sessionId) {
    return getLatestCheckpoint(sessionId).isPresent() ? 1 : 0;
  }

  // ========== Session 管理 ==========

  @Override
  public void clearSession(String sessionId) {
    try {
      Files.deleteIfExists(dataDir.resolve(sessionId + WAL_SUFFIX));
      Files.deleteIfExists(dataDir.resolve(sessionId + CHECKPOINT_SUFFIX));
    } catch (IOException e) {
      log.warn("Failed to clear session {}: {}", sessionId, e.getMessage());
    }
    index.remove(sessionId);
    checkpointCache.remove(sessionId);
  }

  @Override
  public void clearAll() {
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dataDir, "*.wal.jsonl")) {
      for (Path path : stream) Files.deleteIfExists(path);
    } catch (IOException e) {
      log.warn("Failed to clear WAL files: {}", e.getMessage());
    }
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dataDir, "*.checkpoint.json")) {
      for (Path path : stream) Files.deleteIfExists(path);
    } catch (IOException e) {
      log.warn("Failed to clear checkpoint files: {}", e.getMessage());
    }
    index.clear();
    checkpointCache.clear();
  }

  @Override
  public List<String> activeSessionIds() {
    return List.copyOf(index.keySet());
  }

  // ====================================================================
  // 轻量 JSON 序列化（零外部依赖）
  // ====================================================================

  /** 将 RawMessage 序列化为 JSON 行。 */
  String toJson(RawMessage msg) {
    StringBuilder sb = new StringBuilder();
    sb.append("{\"messageId\":").append(msg.messageId());
    sb.append(",\"sessionId\":").append(quote(msg.sessionId()));
    sb.append(",\"role\":").append(quote(msg.role()));
    sb.append(",\"content\":").append(msg.content() != null ? quote(msg.content()) : "null");
    sb.append(",\"toolCalls\":").append(toolCallsToJson(msg.toolCalls()));
    sb.append(",\"toolCallId\":")
        .append(msg.toolCallId() != null ? quote(msg.toolCallId()) : "null");
    sb.append(",\"name\":").append(msg.name() != null ? quote(msg.name()) : "null");
    sb.append(",\"timestamp\":").append(msg.timestamp());
    sb.append(",\"metadata\":").append(mapToJson(msg.metadata()));
    sb.append("}");
    return sb.toString();
  }

  /** 将 Checkpoint 序列化为 JSON。 */
  String toJson(Checkpoint cp) {
    StringBuilder sb = new StringBuilder();
    sb.append("{\"checkpointId\":").append(cp.checkpointId());
    sb.append(",\"sessionId\":").append(quote(cp.sessionId()));
    sb.append(",\"lastAppliedMessageId\":").append(cp.lastAppliedMessageId());
    sb.append(",\"stateNode\":").append(quote(cp.stateNode()));
    sb.append(",\"variableSnapshot\":").append(mapToJson(cp.variableSnapshot()));
    sb.append(",\"planSnapshot\":")
        .append(cp.planSnapshot() != null ? quote(cp.planSnapshot()) : "null");
    sb.append(",\"createdAt\":").append(cp.createdAt());
    sb.append("}");
    return sb.toString();
  }

  private String toolCallsToJson(List<ToolCall> toolCalls) {
    if (toolCalls == null || toolCalls.isEmpty()) return "null";
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < toolCalls.size(); i++) {
      if (i > 0) sb.append(",");
      ToolCall tc = toolCalls.get(i);
      sb.append("{\"id\":").append(quote(tc.id()));
      sb.append(",\"type\":").append(quote(tc.type()));
      sb.append(",\"function\":{\"name\":").append(quote(tc.function().name()));
      sb.append(",\"arguments\":").append(quote(tc.function().arguments())).append("}}");
    }
    sb.append("]");
    return sb.toString();
  }

  private String mapToJson(Map<String, Object> map) {
    if (map == null || map.isEmpty()) return "{}";
    StringBuilder sb = new StringBuilder("{");
    boolean first = true;
    for (var entry : map.entrySet()) {
      if (!first) sb.append(",");
      first = false;
      sb.append(quote(entry.getKey())).append(":");
      Object val = entry.getValue();
      if (val == null) {
        sb.append("null");
      } else if (val instanceof String s) {
        sb.append(quote(s));
      } else if (val instanceof Number || val instanceof Boolean) {
        sb.append(val);
      } else {
        sb.append(quote(val.toString()));
      }
    }
    sb.append("}");
    return sb.toString();
  }

  // ====================================================================
  // 轻量 JSON 反序列化
  // ====================================================================

  RawMessage parseMessage(String json) {
    try {
      Map<String, Object> map = parseJsonObject(json);
      long messageId = longVal(map.get("messageId"));
      String sessionId = strVal(map.get("sessionId"));
      String role = strVal(map.get("role"));
      String content = strVal(map.get("content"));
      Object tcRaw = map.get("toolCalls");
      List<ToolCall> toolCalls = tcRaw instanceof List ? parseToolCalls((List<?>) tcRaw) : null;
      String toolCallId = strVal(map.get("toolCallId"));
      String name = strVal(map.get("name"));
      long timestamp = longVal(map.get("timestamp"));
      Object metaRaw = map.get("metadata");
      @SuppressWarnings("unchecked")
      Map<String, Object> metadata =
          metaRaw instanceof Map ? (Map<String, Object>) metaRaw : Map.of();
      return new RawMessage(
          messageId, sessionId, role, content, toolCalls, toolCallId, name, timestamp, metadata);
    } catch (Exception e) {
      log.warn("Failed to parse WAL message: {}", e.getMessage());
      return null;
    }
  }

  Checkpoint parseCheckpoint(String json) {
    try {
      Map<String, Object> map = parseJsonObject(json);
      long checkpointId = longVal(map.get("checkpointId"));
      String sessionId = strVal(map.get("sessionId"));
      long lastAppliedMessageId = longVal(map.get("lastAppliedMessageId"));
      String stateNode = strVal(map.get("stateNode"));
      Object varRaw = map.get("variableSnapshot");
      @SuppressWarnings("unchecked")
      Map<String, Object> variableSnapshot =
          varRaw instanceof Map ? (Map<String, Object>) varRaw : Map.of();
      String planSnapshot = strVal(map.get("planSnapshot"));
      long createdAt = longVal(map.get("createdAt"));
      return new Checkpoint(
          checkpointId,
          sessionId,
          lastAppliedMessageId,
          stateNode,
          variableSnapshot,
          planSnapshot,
          createdAt);
    } catch (Exception e) {
      log.warn("Failed to parse checkpoint: {}", e.getMessage());
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private List<ToolCall> parseToolCalls(List<?> list) {
    List<ToolCall> result = new ArrayList<>();
    for (Object item : list) {
      if (item instanceof Map) {
        Map<String, Object> m = (Map<String, Object>) item;
        String id = strVal(m.get("id"));
        String type = strVal(m.get("type"));
        Object fnRaw = m.get("function");
        if (fnRaw instanceof Map) {
          Map<String, Object> fn = (Map<String, Object>) fnRaw;
          String name = strVal(fn.get("name"));
          String args = strVal(fn.get("arguments"));
          result.add(new ToolCall(id, type, new ToolCall.Function(name, args)));
        }
      }
    }
    return result;
  }

  // ====================================================================
  // 简易 JSON 解析器（仅支持平层 + 嵌套 Map/List，无数组内嵌对象）
  // ====================================================================

  /** 解析顶层 JSON 对象。 */
  static Map<String, Object> parseJsonObject(String json) {
    if (json == null || json.isBlank()) return Map.of();
    String trimmed = json.trim();
    if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
      throw new IllegalArgumentException("Not a JSON object: " + json);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    // 去掉首尾 { }
    String inner = trimmed.substring(1, trimmed.length() - 1).trim();
    if (inner.isEmpty()) return result;

    List<String> tokens = tokenizeJson(inner);
    for (int i = 0; i < tokens.size(); i += 2) {
      if (i + 1 >= tokens.size()) break;
      String key = unquote(tokens.get(i));
      String val = tokens.get(i + 1);
      result.put(key, parseJsonValue(val));
    }
    return result;
  }

  /** 将 JSON 对象的键值对序列分割为扁平 token 列表。 */
  static List<String> tokenizeJson(String inner) {
    List<String> tokens = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    int depth = 0;
    boolean inString = false;
    boolean escaped = false;

    for (int i = 0; i < inner.length(); i++) {
      char c = inner.charAt(i);
      if (escaped) {
        current.append(c);
        escaped = false;
        continue;
      }
      if (c == '\\') {
        current.append(c);
        escaped = true;
        continue;
      }
      if (c == '"') {
        inString = !inString;
        current.append(c);
        continue;
      }
      if (!inString) {
        if (c == '{' || c == '[') {
          depth++;
          current.append(c);
          continue;
        }
        if (c == '}' || c == ']') {
          depth--;
          current.append(c);
          continue;
        }
        if (c == ':' && depth == 0) {
          // key-value separator
          tokens.add(current.toString().trim());
          current.setLength(0);
          continue;
        }
        if (c == ',' && depth == 0) {
          tokens.add(current.toString().trim());
          current.setLength(0);
          continue;
        }
      }
      current.append(c);
    }
    if (!current.isEmpty()) {
      tokens.add(current.toString().trim());
    }
    return tokens;
  }

  static Object parseJsonValue(String token) {
    if (token == null) return null;
    String t = token.trim();
    if ("null".equals(t)) return null;
    if ("true".equals(t)) return Boolean.TRUE;
    if ("false".equals(t)) return Boolean.FALSE;
    if (t.startsWith("\"")) return unquote(t);
    if (t.startsWith("{")) return parseJsonObject(t);
    if (t.startsWith("[")) {
      List<Object> list = new ArrayList<>();
      String inner = t.substring(1, t.length() - 1).trim();
      if (!inner.isEmpty()) {
        // 简单数组：无嵌套
        for (String item : inner.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")) {
          list.add(parseJsonValue(item.trim()));
        }
      }
      return list;
    }
    // 数字
    try {
      if (t.contains(".")) return Double.parseDouble(t);
      return Long.parseLong(t);
    } catch (NumberFormatException e) {
      return t;
    }
  }

  private static String quote(String s) {
    if (s == null) return "null";
    StringBuilder sb = new StringBuilder("\"");
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append("\"");
    return sb.toString();
  }

  static String unquote(String s) {
    if (s == null || s.length() < 2) return s;
    if (s.startsWith("\"") && s.endsWith("\"")) {
      String inner = s.substring(1, s.length() - 1);
      StringBuilder sb = new StringBuilder(inner.length());
      for (int i = 0; i < inner.length(); i++) {
        char c = inner.charAt(i);
        if (c == '\\' && i + 1 < inner.length()) {
          char next = inner.charAt(i + 1);
          switch (next) {
            case '"' -> sb.append('"');
            case '\\' -> sb.append('\\');
            case '/' -> sb.append('/');
            case 'b' -> sb.append('\b');
            case 'f' -> sb.append('\f');
            case 'n' -> sb.append('\n');
            case 'r' -> sb.append('\r');
            case 't' -> sb.append('\t');
            case 'u' -> {
              if (i + 5 < inner.length()) {
                String hex = inner.substring(i + 2, i + 6);
                sb.append((char) Integer.parseInt(hex, 16));
                i += 4;
              }
            }
            default -> sb.append(next);
          }
          i++;
        } else {
          sb.append(c);
        }
      }
      return sb.toString();
    }
    return s;
  }

  private static String strVal(Object val) {
    if (val == null) return null;
    return val instanceof String ? (String) val : String.valueOf(val);
  }

  private static long longVal(Object val) {
    if (val == null) return 0;
    if (val instanceof Number n) return n.longValue();
    try {
      return Long.parseLong(val.toString());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  // ========== 辅助 ==========

  private void updateSeq(long id) {
    try {
      Files.writeString(
          seqFile,
          String.valueOf(id),
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING);
    } catch (IOException e) {
      log.warn("Failed to persist sequence id: {}", e.getMessage());
    }
  }

  /** 会话内存索引 */
  record SessionIndex(long lastMessageId, int count) {}

  @Override
  public String toString() {
    return "FileWalStore{dataDir=%s, sessions=%d}".formatted(dataDir, index.size());
  }
}
