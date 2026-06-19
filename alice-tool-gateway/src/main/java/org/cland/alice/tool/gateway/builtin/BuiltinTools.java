package org.cland.alice.tool.gateway.builtin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;
import org.cland.alice.tool.gateway.annotation.AgentTool;
import org.cland.alice.tool.gateway.annotation.RiskLevel;
import org.cland.alice.tool.gateway.annotation.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 内置工具集合 — 文件读写、搜索、Shell 执行。
 *
 * <p>用 {@link AgentTool} 注解标记，通过 {@link org.cland.alice.tool.gateway.engine.ToolDiscovery} 自动注册到
 * {@link org.cland.alice.tool.gateway.ToolRegistry}。
 *
 * <p>路径解析逻辑：优先相对于项目根目录（包含 {@code gradlew} 或 {@code settings.gradle} 的目录）解析。
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
          "Search for lines matching a regex pattern in a file. Returns matching lines with line numbers.",
      risk = RiskLevel.LOW)
  public String grep(
      @ToolParam(value = "pattern", description = "Regex pattern to search for") String pattern,
      @ToolParam(value = "path", description = "File path to search in") String path)
      throws IOException {
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("grep: path is required");
    }
    if (pattern == null || pattern.isBlank()) {
      throw new IllegalArgumentException("grep: pattern is required");
    }
    Path resolved = resolvePath(path);
    if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) {
      throw new IOException("grep: file not found: " + path);
    }
    java.util.regex.Pattern regex = java.util.regex.Pattern.compile(pattern);
    List<String> lines;
    try (Stream<String> lineStream = Files.lines(resolved, StandardCharsets.UTF_8)) {
      lines = lineStream.toList();
    }
    StringBuilder sb = new StringBuilder();
    int count = 0;
    for (int i = 0; i < lines.size(); i++) {
      if (regex.matcher(lines.get(i)).find()) {
        sb.append(i + 1).append(": ").append(lines.get(i)).append("\n");
        count++;
      }
    }
    String result = sb.toString();
    logger.debug("[BuiltinTool] grep '{}' in {} ({} matches)", pattern, path, count);
    return count == 0
        ? "No matches found for pattern '" + pattern + "' in " + path
        : "Found " + count + " match(es):\n" + result;
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
