package org.cland.alice.tool.gateway.builtin;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.cland.alice.tool.gateway.annotation.AgentTool;
import org.cland.alice.tool.gateway.annotation.RiskLevel;
import org.cland.alice.tool.gateway.annotation.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 内置工具集合 — 文件读写、搜索、目录遍历、Shell 执行。
 *
 * <p>用 {@link AgentTool} 注解标记，通过 {@link org.cland.alice.tool.gateway.engine.ToolDiscovery} 自动注册到
 * {@link org.cland.alice.tool.gateway.ToolRegistry}。
 *
 * <p>路径解析逻辑：优先相对于项目根目录（包含 {@code gradlew} 或 {@code settings.gradle} 的目录）解析。
 *
 * <p>所有工具方法返回 String，方便 Agent 直接消费。
 */
public final class BuiltinTools {

  private static final Logger logger = LoggerFactory.getLogger(BuiltinTools.class);

  /** 工具配置：默认最大文件读取大小（10MB） */
  private static final long MAX_READ_SIZE = 10L * 1024 * 1024;

  // ==================================================================
  // read_file
  // ==================================================================

  /**
   * 读取文件内容。
   *
   * @param path 文件路径（相对于项目根目录，或绝对路径）
   * @return 文件内容（UTF-8）
   */
  @AgentTool(
      name = "read_file",
      description = "Read the contents of a file. Returns the full file content as a string.",
      risk = RiskLevel.LOW)
  public String readFile(
      @ToolParam(value = "path", description = "File path (relative or absolute)") String path)
      throws IOException {
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("read_file: path is required");
    }
    Path resolved = resolvePath(path);
    if (!Files.exists(resolved)) {
      throw new IOException("read_file: file not found: " + path + " (resolved=" + resolved + ")");
    }
    if (!Files.isRegularFile(resolved)) {
      throw new IOException(
          "read_file: not a regular file: " + path + " (resolved=" + resolved + ")");
    }
    long size = Files.size(resolved);
    if (size > MAX_READ_SIZE) {
      throw new IOException(
          "read_file: file too large (" + size + " bytes, max=" + MAX_READ_SIZE + "): " + path);
    }
    String content = Files.readString(resolved, StandardCharsets.UTF_8);
    logger.debug("[BuiltinTool] read_file {} ({} chars)", path, content.length());
    return content;
  }

  // ==================================================================
  // write_file
  // ==================================================================

  /**
   * 写入文件内容。如果文件不存在则创建，包括所有父目录。
   *
   * @param path 文件路径
   * @param content 文件内容
   */
  @AgentTool(
      name = "write_file",
      description = "Write content to a file. Creates parent directories if they don't exist.",
      risk = RiskLevel.HIGH)
  public String writeFile(
      @ToolParam(value = "path", description = "File path to write") String path,
      @ToolParam(value = "content", description = "File content to write") String content)
      throws IOException {
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("write_file: path is required");
    }
    if (content == null) {
      content = "";
    }
    Path resolved = resolvePath(path);
    Files.createDirectories(resolved.getParent());
    Files.writeString(resolved, content, StandardCharsets.UTF_8);
    logger.info("[BuiltinTool] write_file {} ({} chars)", path, content.length());
    return "Wrote " + content.length() + " bytes to " + path;
  }

  // ==================================================================
  // grep
  // ==================================================================

  /**
   * 在文件中搜索匹配的模式。
   *
   * @param pattern 搜索模式（Java 正则表达式）
   * @param path 文件路径
   * @return 匹配行（带行号）
   */
  @AgentTool(
      name = "grep",
      description =
          "Search for lines matching a regex pattern in a file or directory. If path is a directory, searches all files recursively (like 'grep -r'). Returns matching lines with line numbers.",
      risk = RiskLevel.LOW)
  public String grep(
      @ToolParam(value = "pattern", description = "Regex pattern to search for") String pattern,
      @ToolParam(value = "path", description = "File or directory path to search in") String path)
      throws IOException {
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("grep: path is required");
    }
    if (pattern == null || pattern.isBlank()) {
      throw new IllegalArgumentException("grep: pattern is required");
    }
    Path resolved = resolvePath(path);
    if (!Files.exists(resolved)) {
      throw new IOException("grep: file not found: " + path);
    }
    java.util.regex.Pattern regex = java.util.regex.Pattern.compile(pattern);

    // Collect all regular files to search
    List<Path> files = new ArrayList<>();
    if (Files.isRegularFile(resolved)) {
      files.add(resolved);
    } else if (Files.isDirectory(resolved)) {
      try (Stream<Path> walk = Files.walk(resolved)) {
        walk.filter(Files::isRegularFile).forEach(files::add);
      }
    } else {
      throw new IOException("grep: not a regular file or directory: " + path);
    }

    StringBuilder sb = new StringBuilder();
    int totalMatches = 0;
    boolean multiFile = files.size() > 1;

    for (Path file : files) {
      List<String> lines;
      try (Stream<String> lineStream = Files.lines(file, StandardCharsets.UTF_8)) {
        lines = lineStream.toList();
      }

      for (int i = 0; i < lines.size(); i++) {
        if (regex.matcher(lines.get(i)).find()) {
          if (multiFile) {
            sb.append(file.getFileName()).append(":");
          }
          sb.append(i + 1).append(": ").append(lines.get(i)).append("\n");
          totalMatches++;
        }
      }
    }

    String result = sb.toString();
    logger.debug(
        "[BuiltinTool] grep '{}' in {} ({} matches across {} files)",
        pattern,
        path,
        totalMatches,
        files.size());
    return totalMatches == 0
        ? "No matches found for pattern '" + pattern + "' in " + path
        : "Found " + totalMatches + " match(es) across " + files.size() + " file(s):\n" + result;
  }

  // ==================================================================
  // run
  // ==================================================================

  /**
   * 执行 shell 命令。
   *
   * @param command shell 命令
   * @return 命令输出（stdout + stderr）
   */
  @AgentTool(
      name = "run",
      description =
          "Execute a shell command (cmd.exe on Windows, /bin/sh on Unix). "
              + "Returns stdout and stderr output.",
      risk = RiskLevel.HIGH)
  public String run(
      @ToolParam(value = "command", description = "Shell command to execute") String command)
      throws IOException, InterruptedException {
    if (command == null || command.isBlank()) {
      throw new IllegalArgumentException("run: command is required");
    }
    boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("windows");
    ProcessBuilder pb =
        isWindows
            ? new ProcessBuilder("cmd.exe", "/c", command)
            : new ProcessBuilder("/bin/sh", "-c", command);
    pb.directory(resolvePath(".").toFile());
    Process process = pb.start();
    String stdOut = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    String stdErr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    int exitCode = process.waitFor();
    logger.debug("[BuiltinTool] run '{}' exit={}", command, exitCode);
    if (exitCode == 0) {
      return stdOut;
    }
    return "exit=" + exitCode + "\n" + stdOut + "\n" + stdErr;
  }

  // ==================================================================
  // list_dir
  // ==================================================================

  /**
   * 列出目录下的文件和子目录。
   *
   * @param path 目录路径
   * @return 文件和子目录的列表（每行一个条目，目录后加 /）
   */
  @AgentTool(
      name = "list_dir",
      description =
          "List files and subdirectories in a directory. Directories are marked with a trailing '/'.",
      risk = RiskLevel.LOW)
  public String listDir(
      @ToolParam(value = "path", description = "Directory path to list") String path)
      throws IOException {
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("list_dir: path is required");
    }
    Path resolved = resolvePath(path);
    if (!Files.exists(resolved)) {
      throw new IOException("list_dir: directory not found: " + path);
    }
    if (!Files.isDirectory(resolved)) {
      throw new IOException("list_dir: not a directory: " + path);
    }
    StringBuilder sb = new StringBuilder();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(resolved)) {
      for (Path entry : stream) {
        if (sb.length() > 0) sb.append("\n");
        sb.append(entry.getFileName());
        if (Files.isDirectory(entry)) {
          sb.append("/");
        }
      }
    }
    String result = sb.toString();
    if (result.isEmpty()) {
      return "[empty directory]";
    }
    return result;
  }

  // ==================================================================
  // file_exists
  // ==================================================================

  /**
   * 检查文件或目录是否存在。
   *
   * @param path 文件或目录路径
   * @return "true" 或 "false"
   */
  @AgentTool(
      name = "file_exists",
      description =
          "Check whether a file or directory exists at the given path. Returns 'true' or 'false'.",
      risk = RiskLevel.LOW)
  public String fileExists(
      @ToolParam(value = "path", description = "File or directory path to check") String path) {
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("file_exists: path is required");
    }
    Path resolved = resolvePath(path);
    return String.valueOf(Files.exists(resolved));
  }

  // ==================================================================
  // search_file
  // ==================================================================

  /**
   * 在目录中递归搜索文件名匹配指定模式的文件。
   *
   * @param path 起始搜索目录
   * @param pattern 文件名匹配模式（glob 风格，如 *.java, build.*）
   * @param maxDepth 最大搜索深度，-1 表示不限
   * @return 匹配文件的路径列表（每行一个）
   */
  @AgentTool(
      name = "search_file",
      description =
          "Recursively search for files matching a glob pattern in a directory. Returns matching file paths, one per line.",
      risk = RiskLevel.LOW)
  public String searchFile(
      @ToolParam(value = "path", description = "Starting directory for search") String path,
      @ToolParam(
              value = "pattern",
              description = "Glob pattern to match filenames (e.g. *.java, build.*)")
          String pattern,
      @ToolParam(
              value = "maxDepth",
              description = "Maximum recursion depth, -1 for unlimited",
              required = false)
          String maxDepth)
      throws IOException {
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("search_file: path is required");
    }
    if (pattern == null || pattern.isBlank()) {
      throw new IllegalArgumentException("search_file: pattern is required");
    }
    Path resolved = resolvePath(path);
    if (!Files.exists(resolved)) {
      throw new IOException("search_file: directory not found: " + path);
    }
    if (!Files.isDirectory(resolved)) {
      throw new IOException("search_file: not a directory: " + path);
    }

    int depth = -1;
    if (maxDepth != null && !maxDepth.isBlank()) {
      try {
        depth = Integer.parseInt(maxDepth);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("search_file: maxDepth must be an integer: " + maxDepth);
      }
    }

    java.nio.file.PathMatcher matcher = resolved.getFileSystem().getPathMatcher("glob:" + pattern);
    List<String> matches = new ArrayList<>();
    int finalDepth = depth;

    try (Stream<Path> walk =
        finalDepth >= 0 ? Files.walk(resolved, finalDepth) : Files.walk(resolved)) {
      walk.filter(Files::isRegularFile)
          .filter(p -> matcher.matches(p.getFileName()))
          .forEach(p -> matches.add(resolved.relativize(p).toString().replace("\\", "/")));
    }

    if (matches.isEmpty()) {
      return "No files matching '" + pattern + "' found in " + path;
    }
    return "Found "
        + matches.size()
        + " file(s) matching '"
        + pattern
        + "':\n"
        + String.join("\n", matches);
  }

  // ==================================================================
  // remove_file
  // ==================================================================

  /**
   * 删除一个文件。
   *
   * @param path 要删除的文件路径
   * @return 确认消息
   */
  @AgentTool(
      name = "remove_file",
      description = "Delete a file. Does NOT delete directories (safety guard).",
      risk = RiskLevel.HIGH)
  public String removeFile(
      @ToolParam(value = "path", description = "File path to delete") String path)
      throws IOException {
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("remove_file: path is required");
    }
    Path resolved = resolvePath(path);
    if (!Files.exists(resolved)) {
      // 幂等：文件不存在视为成功
      return "File not found (already removed): " + path;
    }
    if (Files.isDirectory(resolved)) {
      throw new IOException(
          "remove_file: refusing to delete directory (use with caution): " + path);
    }
    Files.delete(resolved);
    logger.info("[BuiltinTool] remove_file {} deleted", path);
    return "Deleted: " + path;
  }

  // ==================================================================
  // web_search
  // ==================================================================

  /** DuckDuckQ 搜索 API（无需 API key，lited 版返回摘要） */
  private static final String DDG_API = "https://api.duckduckgo.com/";

  /** 共享 HTTP 客户端，懒加载 */
  private static volatile HttpClient httpClient;

  private static HttpClient httpClient() {
    HttpClient c = httpClient;
    if (c == null) {
      synchronized (BuiltinTools.class) {
        c = httpClient;
        if (c == null) {
          c =
              HttpClient.newBuilder()
                  .connectTimeout(Duration.ofSeconds(10))
                  .followRedirects(HttpClient.Redirect.NORMAL)
                  .build();
          httpClient = c;
        }
      }
    }
    return c;
  }

  /**
   * 执行 Web 搜索。
   *
   * <p>使用 DuckDuckGo Instant Answer API（无 API key 需求）， 返回摘要和可选链接。适用于获取实时资讯、技术文档、公共信息。
   *
   * @param query 搜索关键词
   * @param maxResults 最大结果数（1-10，默认 5）
   * @return 搜索结果文本
   */
  @AgentTool(
      name = "web_search",
      description =
          "Search the web for real-time information. Uses DuckDuckGo. Returns summaries and links.",
      risk = RiskLevel.MEDIUM)
  public String webSearch(
      @ToolParam(value = "query", description = "Search query keywords") String query,
      @ToolParam(
              value = "maxResults",
              description = "Maximum number of results (1-10, default 5)",
              required = false)
          String maxResults)
      throws IOException, InterruptedException {
    if (query == null || query.isBlank()) {
      throw new IllegalArgumentException("web_search: query is required");
    }

    int max = 5;
    if (maxResults != null && !maxResults.isBlank()) {
      try {
        max = Math.min(10, Math.max(1, Integer.parseInt(maxResults)));
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(
            "web_search: maxResults must be an integer: " + maxResults);
      }
    }

    // DuckDuckGo Instant Answer API（no key required）
    String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
    String url = DDG_API + "?q=" + encodedQuery + "&format=json&no_html=1&skip_disambig=1";

    HttpRequest req =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", "AliceAgent/1.0")
            .GET()
            .build();

    HttpResponse<String> resp = httpClient().send(req, HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() != 200) {
      return "web_search failed: HTTP " + resp.statusCode();
    }

    // 解析 JSON 响应
    String body = resp.body();
    StringBuilder sb = new StringBuilder();

    // 提取 AbstractText（摘要）
    String abstractText = extractJsonField(body, "AbstractText");
    if (abstractText != null && !abstractText.isEmpty() && !abstractText.equals("null")) {
      sb.append("Abstract: ").append(abstractText).append("\n");
    }

    // 提取 AbstractSource + AbstractURL
    String source = extractJsonField(body, "AbstractSource");
    String absUrl = extractJsonField(body, "AbstractURL");
    if (source != null && !source.equals("null")) {
      sb.append("Source: ").append(source);
      if (absUrl != null && !absUrl.equals("null")) {
        sb.append(" (").append(absUrl).append(")");
      }
      sb.append("\n");
    }

    // 提取 RelatedTopics（最多 max 条）
    // 格式: "RelatedTopics":[{"Text":"...","FirstURL":"...","Icon":{...}},...]
    sb.append(parseRelatedTopics(body, max));

    String result = sb.toString().trim();
    if (result.isEmpty()) {
      return "No results found for: " + query;
    }
    return "Search results for '" + query + "':\n" + result;
  }

  /** 从 JSON 中解析 RelatedTopics 数组（简易解析，不依赖 JSON 库以避免被单元测试中的沙箱捕获）。 */
  private static String parseRelatedTopics(String json, int max) {
    StringBuilder sb = new StringBuilder();
    String searchKey = "\"RelatedTopics\":[";
    int idx = json.indexOf(searchKey);
    if (idx < 0) return "";

    idx += searchKey.length();
    int count = 0;
    while (idx < json.length() && count < max) {
      // 跳过空白
      while (idx < json.length()
          && (json.charAt(idx) == ' ' || json.charAt(idx) == '\n' || json.charAt(idx) == '\r'))
        idx++;
      if (idx >= json.length() || json.charAt(idx) == ']') break;

      // 跳过非 object 开始（逗号等）
      if (json.charAt(idx) != '{') {
        idx++;
        continue;
      }

      // 找到 "Text" 和 "FirstURL"
      String text = extractJsonField(json.substring(idx), "Text");
      String firstUrl = extractJsonField(json.substring(idx), "FirstURL");

      // 找到对象的结束 }
      int close = idx;
      int depth = 0;
      while (close < json.length()) {
        char c = json.charAt(close);
        if (c == '{') depth++;
        else if (c == '}') {
          depth--;
          if (depth == 0) break;
        }
        close++;
      }
      idx = close + 1;

      if (text != null && !text.equals("null")) {
        count++;
        if (sb.length() > 0) sb.append("\n---\n");
        // 清理 HTML 标签
        String clean = text.replaceAll("<[^>]+>", "").trim();
        sb.append(count).append(". ").append(clean);
        if (firstUrl != null && !firstUrl.equals("null")) {
          sb.append("\n   ").append(firstUrl);
        }
      }
    }
    return sb.toString();
  }

  /** 简易 JSON 字段提取（不支持嵌套，仅用于 demo）。 */
  private static String extractJsonField(String json, String field) {
    if (json == null) return null;
    String search = "\"" + field + "\":";
    int idx = json.indexOf(search);
    if (idx < 0) return null;

    idx += search.length();
    while (idx < json.length() && json.charAt(idx) == ' ') idx++;
    if (idx >= json.length()) return null;

    char first = json.charAt(idx);
    if (first == '"') {
      StringBuilder sb = new StringBuilder();
      idx++;
      while (idx < json.length()) {
        char c = json.charAt(idx);
        if (c == '\\') {
          sb.append(json.charAt(idx + 1));
          idx += 2;
        } else if (c == '"') break;
        else {
          sb.append(c);
          idx++;
        }
      }
      return sb.toString();
    } else if (first == 'n') {
      if (idx + 4 <= json.length() && json.substring(idx, idx + 4).equals("null")) return null;
      StringBuilder sb = new StringBuilder();
      while (idx < json.length()) {
        char c = json.charAt(idx);
        if (c == ',' || c == '}' || c == ']') break;
        sb.append(c);
        idx++;
      }
      return sb.toString().trim();
    } else {
      StringBuilder sb = new StringBuilder();
      while (idx < json.length()) {
        char c = json.charAt(idx);
        if (c == ',' || c == '}' || c == ']') break;
        sb.append(c);
        idx++;
      }
      return sb.toString().trim();
    }
  }

  // ==================================================================
  // 路径解析
  // ==================================================================

  /** 解析路径：如果是相对路径，尝试相对于项目根目录解析。 项目根目录通过向上找 {@code gradlew} 或 {@code settings.gradle} 确定。 */
  private static Path resolvePath(String path) {
    Path given = Paths.get(path);
    if (given.isAbsolute()) {
      return given;
    }
    // 尝试从工作目录开始向上找项目根
    Path cwd = Paths.get(".").toAbsolutePath().normalize();
    Path root = findProjectRoot(cwd);
    if (root != null) {
      return root.resolve(given).normalize();
    }
    // fallback: 相对当前工作目录
    return cwd.resolve(given).normalize();
  }

  /** 从给定目录开始向上查找项目根目录（包含 gradlew 或 settings.gradle）。 */
  private static Path findProjectRoot(Path start) {
    Path current = start;
    while (current != null) {
      if (Files.exists(current.resolve("gradlew"))
          || Files.exists(current.resolve("settings.gradle"))
          || Files.exists(current.resolve("settings.gradle.kts"))) {
        return current;
      }
      current = current.getParent();
    }
    return null;
  }
}
