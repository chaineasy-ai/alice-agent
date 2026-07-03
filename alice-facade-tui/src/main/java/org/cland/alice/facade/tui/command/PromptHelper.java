package org.cland.alice.facade.tui.command;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prompt 工具类 — managed prompt 的解析、读取、拷贝与列表。
 *
 * <p>供 TUI {@link CommandHandler} 和 {@link org.cland.alice.facade.tui.AliceTuiLauncher} 在 {@code
 * /prompt} 命令处理中使用。对应 {@code CapabilityCmd.UpdateRulesCmd} 的实际文件系统操作。
 */
public final class PromptHelper {

  private static final Logger log = LoggerFactory.getLogger(PromptHelper.class);

  public static final Path PROMPTS_DIR =
      Paths.get(System.getProperty("user.home", "/tmp"), ".alice", "prompts");
  public static final Path RULES_DIR =
      Paths.get(System.getProperty("user.home", "/tmp"), ".alice", "rules");

  private PromptHelper() {}

  public static ResolveResult resolve(String nameOrPath) {
    if (nameOrPath == null || nameOrPath.isBlank()) {
      return new ResolveResult(false, null, false, "参数为空");
    }
    if (nameOrPath.contains("/") || nameOrPath.contains("\\")) {
      Path path = Paths.get(nameOrPath);
      if (Files.exists(path))
        return new ResolveResult(true, path, false, "已找到: " + path.toAbsolutePath());
      return new ResolveResult(false, null, false, "文件不存在: " + path.toAbsolutePath());
    }
    Path promptsPath = PROMPTS_DIR.resolve(nameOrPath + ".ftl");
    if (Files.exists(promptsPath))
      return new ResolveResult(true, promptsPath, true, "已找到: " + promptsPath.toAbsolutePath());
    return new ResolveResult(false, null, false, "未找到 managed prompt: " + nameOrPath);
  }

  public static String readContent(Path path) throws IOException {
    return Files.readString(path);
  }

  public static Path copyPromptFile(Path source, boolean isManaged) throws IOException {
    String filename = source.getFileName().toString();
    if (isManaged) {
      log.info("Prompt already in prompts dir (managed): {}", source);
      return source;
    }
    if (filename.endsWith(".ftl")) {
      Files.createDirectories(PROMPTS_DIR);
      Path dest = PROMPTS_DIR.resolve(filename);
      Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
      log.info("Prompt copied to prompts/: {} -> {}", source, dest);
      return dest;
    }
    Files.createDirectories(RULES_DIR);
    if (!filename.endsWith(".md")) {
      int dot = filename.lastIndexOf('.');
      String baseName = dot > 0 ? filename.substring(0, dot) : filename;
      filename = baseName + ".md";
    }
    Path dest = RULES_DIR.resolve(filename);
    Files.writeString(dest, Files.readString(source));
    log.info("Prompt copied to rules/: {} -> {} ({} bytes)", source, dest, Files.size(source));
    return dest;
  }

  public static String listPrompts() {
    var sb = new StringBuilder();
    sb.append("── 可用 Managed Prompts ────────────────────\n");
    var names = scanPromptNames();
    if (names.isEmpty()) {
      sb.append("  暂无 managed prompts\n");
      sb.append("  将 .ftl 文件放入 ~/.alice/prompts/\n");
    } else {
      for (String n : names) sb.append("  /prompt:").append(n).append("\n");
    }
    sb.append("────────────────────────────────────────────\n");
    sb.append("用法: /prompt:<name> 加载 managed prompt\n");
    sb.append("       /prompt <文件路径> 加载外部文件\n");
    return sb.toString();
  }

  public static List<String> scanPromptNames() {
    var names = new ArrayList<String>();
    if (!Files.isDirectory(PROMPTS_DIR)) return names;
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(PROMPTS_DIR, "*.ftl")) {
      for (var file : stream) {
        String n = file.getFileName().toString();
        names.add(n.substring(0, n.length() - 4));
      }
    } catch (IOException e) {
      log.warn("Failed to scan prompts directory", e);
    }
    return names;
  }

  public record ResolveResult(boolean found, Path path, boolean managed, String message) {}
}
