package org.cland.alice.memory.sop;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.nio.Attribute;
import org.jgrapht.nio.AttributeType;
import org.jgrapht.nio.DefaultAttribute;
import org.jgrapht.nio.graphml.GraphMLExporter;
import org.jgrapht.nio.graphml.GraphMLImporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SOP 图的 GraphML 序列化/反序列化，默认本地存储路径 {@code ~/.alice/sops/}。
 *
 * <p>SOP DAG 始终在内存中运行（JGrapht 原生图结构），GraphML 仅用于持久化（保存/恢复）。
 *
 * <p>使用示例：
 *
 * <pre>{@code
 * // 默认路径 ~/.alice/sops/<id>.graphml
 * SopGraphPersistence.save(graph);                        // → ~/.alice/sops/weather.graphml
 * SopGraph restored = SopGraphPersistence.load("weather"); // ← ~/.alice/sops/weather.graphml
 *
 * // 自定义路径
 * SopGraphPersistence.save(graph, new File("sops/my.graphml"));
 * SopGraph restored = SopGraphPersistence.load(new File("sops/my.graphml"));
 *
 * // 管理
 * List<String> ids = SopGraphPersistence.list();
 * SopGraphPersistence.delete("weather");
 * SopGraphPersistence.setDefaultDir(Path.of("/custom/path"));
 * }</pre>
 */
public final class SopGraphPersistence {

  private static final Logger logger = LoggerFactory.getLogger(SopGraphPersistence.class);

  // ========================================================================
  // 自定义属性键
  // ========================================================================

  private static final String ATTR_ACTION_TYPE = "actionType";
  private static final String ATTR_TARGET = "target";
  private static final String ATTR_PARAMETERS = "parameters";
  private static final String ATTR_THOUGHT = "thought";
  private static final String ATTR_EDGE_LABEL = "edgeLabel";

  /**
   * SOP 元数据属性键（存储为第一个节点的属性，兼容标准 GraphML）。
   *
   * <p>格式：{@code id|description|keyword1,keyword2}
   */
  private static final String ATTR_SOP_META = "sopMeta";

  // ========================================================================
  // 默认本地存储路径
  // ========================================================================

  private static final String SOP_EXT = ".graphml";

  /** 默认 SOP 存储目录：{@code ~/.alice/sops/} */
  public static final Path DEFAULT_SOPS_DIR =
      Path.of(System.getProperty("user.home"), ".alice", "sops");

  /** 当前使用的 SOP 存储目录 */
  private static Path sopsDir = DEFAULT_SOPS_DIR;

  private SopGraphPersistence() {}

  // ========== 保存 ==========

  /** 保存到默认路径 {@code ~/.alice/sops/<id>.graphml}。 */
  public static void save(SopGraph graph) throws IOException {
    Objects.requireNonNull(graph, "graph must not be null");
    File file = sopsDir.resolve(graph.id() + SOP_EXT).toFile();
    save(graph, file);
  }

  /** 保存到指定文件。 */
  public static void save(SopGraph graph, File file) throws IOException {
    Objects.requireNonNull(graph, "graph must not be null");
    Objects.requireNonNull(file, "file must not be null");

    File parentDir = file.getParentFile();
    if (parentDir != null && !parentDir.exists()) {
      parentDir.mkdirs();
    }

    try (Writer writer =
        new OutputStreamWriter(
            new BufferedOutputStream(new FileOutputStream(file)),
            java.nio.charset.StandardCharsets.UTF_8)) {
      configureExporter(graph).exportGraph(graph.delegate(), writer);
    }

    logger.info("[SopGraphPersistence] Saved SOP '{}' to {}", graph.id(), file.getAbsolutePath());
  }

  /** 导出为 GraphML XML 字符串。 */
  public static String toXml(SopGraph graph) {
    Objects.requireNonNull(graph, "graph must not be null");
    var writer = new java.io.StringWriter();
    try {
      configureExporter(graph).exportGraph(graph.delegate(), writer);
    } catch (Exception e) {
      logger.error("[SopGraphPersistence] Failed to export SOP '{}' to XML", graph.id(), e);
      return "";
    }
    return writer.toString();
  }

  // ========== 加载 ==========

  /** 从默认路径 {@code ~/.alice/sops/<id>.graphml} 加载。 */
  public static SopGraph load(String id) throws IOException {
    Objects.requireNonNull(id, "id must not be null");
    File file = sopsDir.resolve(id + SOP_EXT).toFile();
    return load(file);
  }

  /** 从指定 GraphML 文件加载。 */
  public static SopGraph load(File file) throws IOException {
    Objects.requireNonNull(file, "file must not be null");
    if (!file.exists()) {
      throw new FileNotFoundException("GraphML file not found: " + file.getAbsolutePath());
    }

    Result result = new Result();
    try (Reader reader =
        new InputStreamReader(
            new BufferedInputStream(new FileInputStream(file)),
            java.nio.charset.StandardCharsets.UTF_8)) {
      configureImporter(result).importGraph(result.graph, reader);
    }

    SopGraph restored = result.build();
    logger.info(
        "[SopGraphPersistence] Loaded SOP '{}' from {} ({} nodes, {} edges)",
        restored.id(),
        file.getAbsolutePath(),
        restored.nodes().size(),
        restored.edges().size());
    return restored;
  }

  /** 从 GraphML XML 字符串恢复。 */
  public static SopGraph fromXml(String xml) {
    Objects.requireNonNull(xml, "xml must not be null");
    Result result = new Result();
    try (Reader reader = new StringReader(xml)) {
      configureImporter(result).importGraph(result.graph, reader);
    } catch (Exception e) {
      logger.error("[SopGraphPersistence] Failed to import SOP from XML", e);
      return SopGraph.builder("unknown", "Import failed").build();
    }
    return result.build();
  }

  // ========== 存储管理 ==========

  /** 列出默认目录下所有已存储的 SOP ID。 */
  public static List<String> list() {
    File dir = sopsDir.toFile();
    if (!dir.exists() || !dir.isDirectory()) return List.of();
    File[] files = dir.listFiles((d, name) -> name.endsWith(SOP_EXT));
    if (files == null) return List.of();
    return Arrays.stream(files).map(f -> f.getName().replace(SOP_EXT, "")).sorted().toList();
  }

  /** 从默认目录删除指定的 SOP。 */
  public static boolean delete(String id) {
    Objects.requireNonNull(id, "id must not be null");
    File file = sopsDir.resolve(id + SOP_EXT).toFile();
    if (file.exists()) {
      boolean deleted = file.delete();
      if (deleted) logger.info("[SopGraphPersistence] Deleted SOP '{}'", id);
      return deleted;
    }
    return false;
  }

  /** 设置自定义 SOP 存储目录。 */
  public static void setDefaultDir(Path dir) {
    Objects.requireNonNull(dir, "dir must not be null");
    sopsDir = dir.toAbsolutePath().normalize();
    logger.info("[SopGraphPersistence] SOP store directory set to {}", sopsDir);
  }

  /** 获取当前 SOP 存储目录。 */
  public static Path getDefaultDir() {
    return sopsDir;
  }

  // ========== 导出器配置 ==========

  private static GraphMLExporter<SopGraph.SopNode, SopGraph.SopEdge> configureExporter(
      SopGraph graph) {

    var exporter = new GraphMLExporter<SopGraph.SopNode, SopGraph.SopEdge>();

    exporter.registerAttribute(
        ATTR_ACTION_TYPE, GraphMLExporter.AttributeCategory.NODE, AttributeType.STRING, "UNKNOWN");
    exporter.registerAttribute(
        ATTR_TARGET, GraphMLExporter.AttributeCategory.NODE, AttributeType.STRING, "");
    exporter.registerAttribute(
        ATTR_PARAMETERS, GraphMLExporter.AttributeCategory.NODE, AttributeType.STRING, "");
    exporter.registerAttribute(
        ATTR_THOUGHT, GraphMLExporter.AttributeCategory.NODE, AttributeType.STRING, "");
    exporter.registerAttribute(
        ATTR_EDGE_LABEL, GraphMLExporter.AttributeCategory.EDGE, AttributeType.STRING, "");
    exporter.registerAttribute(
        ATTR_SOP_META, GraphMLExporter.AttributeCategory.NODE, AttributeType.STRING, "");

    SopGraph.SopNode firstNode = graph.topologicalOrder().stream().findFirst().orElse(null);

    exporter.setVertexAttributeProvider(
        v -> {
          Map<String, Attribute> attrs = new LinkedHashMap<>();
          attrs.put(ATTR_ACTION_TYPE, new DefaultAttribute<>(v.actionType(), AttributeType.STRING));
          attrs.put(ATTR_TARGET, new DefaultAttribute<>(v.target(), AttributeType.STRING));
          attrs.put(
              ATTR_PARAMETERS,
              new DefaultAttribute<>(serializeParameters(v.parameters()), AttributeType.STRING));
          attrs.put(
              ATTR_THOUGHT,
              v.thought() != null
                  ? new DefaultAttribute<>(v.thought(), AttributeType.STRING)
                  : new DefaultAttribute<>("", AttributeType.STRING));
          if (firstNode != null && v.id().equals(firstNode.id())) {
            String meta =
                graph.id()
                    + "|"
                    + (graph.description() != null ? graph.description() : "")
                    + "|"
                    + String.join(",", graph.keywords());
            attrs.put(ATTR_SOP_META, new DefaultAttribute<>(meta, AttributeType.STRING));
          }
          return attrs;
        });

    exporter.setEdgeAttributeProvider(
        e -> {
          Map<String, Attribute> attrs = new LinkedHashMap<>();
          attrs.put(ATTR_EDGE_LABEL, new DefaultAttribute<>(e.label(), AttributeType.STRING));
          return attrs;
        });

    exporter.setVertexIdProvider(SopGraph.SopNode::id);
    return exporter;
  }

  // ========== 导入器配置 ==========

  private static GraphMLImporter<SopGraph.SopNode, SopGraph.SopEdge> configureImporter(
      Result result) {

    var importer = new GraphMLImporter<SopGraph.SopNode, SopGraph.SopEdge>();

    importer.setVertexFactory(
        id -> {
          SopGraph.SopNode placeholder = new SopGraph.SopNode(id, "UNKNOWN", "", Map.of(), null);
          result.registerNode(placeholder);
          return placeholder;
        });

    importer.addVertexAttributeConsumer(
        (pair, attr) -> {
          SopGraph.SopNode node = pair.getFirst();
          String attrName = pair.getSecond();
          String value = attr.getValue();
          if (value == null) return;
          if (ATTR_SOP_META.equals(attrName)) {
            result.parseMeta(value);
          } else if (ATTR_ACTION_TYPE.equals(attrName)) {
            result.captureNodeAttr(node.id(), ATTR_ACTION_TYPE, value);
          } else if (ATTR_TARGET.equals(attrName)) {
            result.captureNodeAttr(node.id(), ATTR_TARGET, value);
          } else if (ATTR_PARAMETERS.equals(attrName)) {
            result.captureNodeAttr(node.id(), ATTR_PARAMETERS, value);
          } else if (ATTR_THOUGHT.equals(attrName)) {
            result.captureNodeAttr(node.id(), ATTR_THOUGHT, value);
          }
        });

    importer.addEdgeAttributeConsumer(
        (pair, attr) -> {
          SopGraph.SopEdge edge = pair.getFirst();
          if (ATTR_EDGE_LABEL.equals(pair.getSecond())) {
            edge.setLabel(attr.getValue());
          }
        });

    return importer;
  }

  // ========== 导入积累器 ==========

  private static final class Result {
    final DefaultDirectedGraph<SopGraph.SopNode, SopGraph.SopEdge> graph;
    final Map<String, Map<String, String>> nodeAttrs;
    String sopId = "unknown";
    String sopDescription = "";
    String sopKeywords = "";

    Result() {
      this.graph = new DefaultDirectedGraph<>(SopGraph.SopEdge.class);
      this.nodeAttrs = new LinkedHashMap<>();
    }

    void registerNode(SopGraph.SopNode node) {
      graph.addVertex(node);
      nodeAttrs.put(node.id(), new LinkedHashMap<>());
    }

    void captureNodeAttr(String nodeId, String attrName, String value) {
      nodeAttrs.computeIfAbsent(nodeId, k -> new LinkedHashMap<>()).put(attrName, value);
    }

    void parseMeta(String meta) {
      if (meta == null || meta.isBlank()) return;
      String[] parts = meta.split("\\|", 3);
      if (parts.length > 0) sopId = parts[0].trim();
      if (parts.length > 1) sopDescription = parts[1].trim();
      if (parts.length > 2) sopKeywords = parts[2].trim();
    }

    SopGraph build() {
      var builder = SopGraph.builder(sopId, sopDescription);
      for (var entry : nodeAttrs.entrySet()) {
        String nodeId = entry.getKey();
        Map<String, String> attrs = entry.getValue();
        String actionType = attrs.getOrDefault(ATTR_ACTION_TYPE, "UNKNOWN");
        String target = attrs.getOrDefault(ATTR_TARGET, "");
        String paramsStr = attrs.getOrDefault(ATTR_PARAMETERS, "");
        String thought = attrs.get(ATTR_THOUGHT);
        Map<String, Object> params = deserializeParameters(paramsStr);
        if (thought == null || thought.isBlank() || "null".equals(thought)) thought = null;
        builder.addNode(nodeId, actionType, target, params, thought);
      }
      for (SopGraph.SopEdge edge : graph.edgeSet()) {
        String sourceId = graph.getEdgeSource(edge).id();
        String targetId = graph.getEdgeTarget(edge).id();
        builder.addEdge(sourceId, targetId, edge.label());
      }
      if (!sopKeywords.isBlank()) {
        for (String kw : sopKeywords.split(",")) {
          String t = kw.trim();
          if (!t.isEmpty()) builder.addKeyword(t);
        }
      }
      return builder.build();
    }
  }

  // ========== 辅助方法 ==========

  private static String getAttr(Map<String, Attribute> attrs, String key, String defaultValue) {
    if (attrs == null) return defaultValue;
    Attribute attr = attrs.get(key);
    if (attr == null) return defaultValue;
    String value = attr.getValue();
    return value != null ? value : defaultValue;
  }

  static String serializeParameters(Map<String, Object> params) {
    if (params == null || params.isEmpty()) return "";
    StringBuilder sb = new StringBuilder();
    for (var entry : params.entrySet()) {
      if (sb.length() > 0) sb.append(";");
      sb.append(entry.getKey()).append("=").append(entry.getValue());
    }
    return sb.toString();
  }

  static Map<String, Object> deserializeParameters(String str) {
    if (str == null || str.isBlank() || str.equals("{}")) return Map.of();
    Map<String, Object> result = new LinkedHashMap<>();
    String cleaned = str.trim();
    if (cleaned.startsWith("{") && cleaned.endsWith("}")) {
      cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
    }
    if (cleaned.isEmpty()) return Map.of();
    for (String pair : cleaned.split(";")) {
      int eq = pair.indexOf('=');
      if (eq > 0) {
        String key = pair.substring(0, eq).trim();
        String value = pair.substring(eq + 1).trim();
        if (!key.isEmpty()) result.put(key, value);
      }
    }
    return Map.copyOf(result);
  }
}
