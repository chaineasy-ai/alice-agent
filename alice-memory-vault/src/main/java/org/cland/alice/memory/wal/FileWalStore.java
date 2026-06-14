/*
 * FileWalStore — 基于 JSONL 本地文件的 WalStore 实现
 *
 * 每条消息以 JSON 单行 (JSONL) 格式追加到 `<dataDir>/<sessionId>.wal.jsonl`。
 * Checkpoint 以单独文件 `<dataDir>/<sessionId>.checkpoint.json` 存储（同 session 覆盖）。
 *
 * 使用 Jackson 进行 JSON 序列化/反序列化。
 * 适合开发/单机部署。生产环境可替换为 PostgresWalStore。
 */
package org.cland.alice.memory.wal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
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
 * <p>使用 Jackson 进行 JSON 序列化，支持 RawMessage（含 ToolCall）和 Checkpoint 的完整 round-trip。
 */
public final class FileWalStore implements WalStore {

  private static final Logger log = LoggerFactory.getLogger(FileWalStore.class);

  private static final String WAL_SUFFIX = ".wal.jsonl";
  private static final String CHECKPOINT_SUFFIX = ".checkpoint.json";

  private final Path dataDir;
  private final ObjectMapper mapper;
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
    this.mapper = createMapper();
    init();
  }

  private static ObjectMapper createMapper() {
    ObjectMapper m = new ObjectMapper();
    m.registerModule(new JavaTimeModule());
    m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    m.disable(SerializationFeature.INDENT_OUTPUT); // JSONL 需要单行
    m.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    return m;
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
            RawMessage msg = readMessage(line);
            if (msg != null && msg.messageId() > lastId) lastId = msg.messageId();
          }
        }
        if (count > 0) {
          index.put(sessionId, new SessionIndex(lastId, count));
        }

        Path cpFile = dataDir.resolve(sessionId + CHECKPOINT_SUFFIX);
        if (Files.exists(cpFile)) {
          String content = Files.readString(cpFile).trim();
          if (!content.isEmpty()) {
            try {
              Checkpoint cp = mapper.readValue(content, Checkpoint.class);
              checkpointCache.put(sessionId, cp);
            } catch (JsonProcessingException e) {
              log.warn("Malformed checkpoint file for session {}: {}", sessionId, e.getMessage());
            }
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
      String json = mapper.writeValueAsString(message) + System.lineSeparator();
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
        RawMessage msg = readMessage(line);
        if (msg != null) result.add(msg);
      }
      return result;
    } catch (IOException e) {
      log.warn("Failed to read WAL file: {}", e.getMessage());
      return List.of();
    }
  }

  private RawMessage readMessage(String json) {
    try {
      return mapper.readValue(json, RawMessage.class);
    } catch (JsonProcessingException e) {
      log.warn("Skipping malformed WAL line: {}", e.getMessage());
      return null;
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
            remaining.stream()
                    .map(this::writeMessageToString)
                    .collect(Collectors.joining(System.lineSeparator()))
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

  private String writeMessageToString(RawMessage msg) {
    try {
      return mapper.writeValueAsString(msg);
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Failed to serialize message: " + msg.messageId(), e);
    }
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
      String json = mapper.writeValueAsString(stored);
      Files.writeString(
          file, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
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
      Checkpoint cp = mapper.readValue(content, Checkpoint.class);
      checkpointCache.put(sessionId, cp);
      return Optional.of(cp);
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
    try {
      Files.deleteIfExists(seqFile);
    } catch (IOException e) {
      // ignore
    }
    index.clear();
    checkpointCache.clear();
  }

  @Override
  public List<String> activeSessionIds() {
    return List.copyOf(index.keySet());
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
