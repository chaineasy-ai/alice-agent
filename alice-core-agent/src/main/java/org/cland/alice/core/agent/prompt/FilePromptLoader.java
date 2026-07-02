package org.cland.alice.core.agent.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * File-based loader for prompts and rules stored under {@code ~/.alice/}.
 *
 * <p>Manages two directories:
 *
 * <ul>
 *   <li>{@code ~/.alice/prompts/} — FreeMarker templates ({@code .ftl}) for agent prompts
 *   <li>{@code ~/.alice/rules/} — Markdown rules ({@code .md}) injected into system prompts
 * </ul>
 *
 * <p>Directories are lazily scanned on first access. Results are cached until {@link #reload()} is
 * called. Missing directories are not an error — the loader simply returns empty results.
 */
public final class FilePromptLoader {

  private static final Logger logger = LoggerFactory.getLogger(FilePromptLoader.class);

  private static final Path USER_HOME = Paths.get(System.getProperty("user.home"));
  private static final Path PROMPTS_DIR = USER_HOME.resolve(".alice/prompts");
  private static final Path RULES_DIR = USER_HOME.resolve(".alice/rules");

  /** Cached prompts: name → PromptDef */
  private volatile Map<String, PromptDef> promptCache;

  /** Cached rules: name → RuleDef */
  private volatile Map<String, RuleDef> ruleCache;

  /** Guard for reload synchronization */
  private final Object reloadLock = new Object();

  // ========================================================================
  // Public API
  // ========================================================================

  /**
   * Get a prompt by name. Searches {@code ~/.alice/prompts/} first; returns {@code null} if not
   * found.
   *
   * @param name the prompt name (without {@code .ftl} extension)
   * @return the prompt definition, or {@code null}
   */
  public PromptDef getPrompt(String name) {
    var cache = getPromptCache();
    return cache.get(name);
  }

  /**
   * Get all loaded prompts.
   *
   * @return unmodifiable list of prompt definitions
   */
  public List<PromptDef> getAllPrompts() {
    return List.copyOf(getPromptCache().values());
  }

  /**
   * Get a rule by name. Searches {@code ~/.alice/rules/} first; returns {@code null} if not found.
   *
   * @param name the rule name (without {@code .md} extension)
   * @return the rule definition, or {@code null}
   */
  public RuleDef getRule(String name) {
    var cache = getRuleCache();
    return cache.get(name);
  }

  /**
   * Get all loaded rules, sorted by priority (high → medium → low).
   *
   * @return unmodifiable list of rule definitions
   */
  public List<RuleDef> getAllRules() {
    var rules = new ArrayList<>(getRuleCache().values());
    // Sort: high > medium > low
    rules.sort(
        (a, b) -> {
          int pa = priorityRank(a.priority());
          int pb = priorityRank(b.priority());
          return Integer.compare(pa, pb);
        });
    return Collections.unmodifiableList(rules);
  }

  /**
   * Reload prompts and rules from disk. Called automatically when the cache is empty; can be called
   * explicitly after file changes.
   */
  public void reload() {
    synchronized (reloadLock) {
      promptCache = null;
      ruleCache = null;
      logger.info("[FilePromptLoader] Cache cleared — will reload on next access");
    }
  }

  // ========================================================================
  // Cache access
  // ========================================================================

  private Map<String, PromptDef> getPromptCache() {
    var c = promptCache;
    if (c == null) {
      synchronized (reloadLock) {
        c = promptCache;
        if (c == null) {
          promptCache = loadPrompts();
          c = promptCache;
        }
      }
    }
    return c;
  }

  private Map<String, RuleDef> getRuleCache() {
    var c = ruleCache;
    if (c == null) {
      synchronized (reloadLock) {
        c = ruleCache;
        if (c == null) {
          ruleCache = loadRules();
          c = ruleCache;
        }
      }
    }
    return c;
  }

  // ========================================================================
  // File scanning
  // ========================================================================

  /** Scan {@code ~/.alice/prompts/} for all {@code .ftl} files. */
  private Map<String, PromptDef> loadPrompts() {
    Map<String, PromptDef> result = new LinkedHashMap<>();
    Path dir = PROMPTS_DIR;
    if (!Files.isDirectory(dir)) {
      logger.debug("[FilePromptLoader] Prompts directory does not exist: {}", dir);
      return result;
    }
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.ftl")) {
      for (Path file : stream) {
        try {
          String name = filenameWithoutExt(file.getFileName().toString());
          String template = Files.readString(file, StandardCharsets.UTF_8);
          result.put(name, new PromptDef(name, file.toAbsolutePath().toString(), template));
          logger.info("[FilePromptLoader] Loaded prompt: {} ({})", name, file);
        } catch (IOException e) {
          logger.warn("[FilePromptLoader] Failed to read prompt file: {}", file, e);
        }
      }
    } catch (IOException e) {
      logger.warn("[FilePromptLoader] Failed to scan prompts directory: {}", dir, e);
    }
    logger.info("[FilePromptLoader] Loaded {} prompt(s) from {}", result.size(), dir);
    return result;
  }

  /** Scan {@code ~/.alice/rules/} for all {@code .md} files with YAML front-matter parsing. */
  private Map<String, RuleDef> loadRules() {
    Map<String, RuleDef> result = new LinkedHashMap<>();
    Path dir = RULES_DIR;
    if (!Files.isDirectory(dir)) {
      logger.debug("[FilePromptLoader] Rules directory does not exist: {}", dir);
      return result;
    }
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.md")) {
      for (Path file : stream) {
        try {
          String name = filenameWithoutExt(file.getFileName().toString());
          String raw = Files.readString(file, StandardCharsets.UTF_8);
          RuleDef rule = parseRuleDef(name, file.toAbsolutePath().toString(), raw);
          result.put(name, rule);
          logger.info("[FilePromptLoader] Loaded rule: {} ({})", name, file);
        } catch (IOException e) {
          logger.warn("[FilePromptLoader] Failed to read rule file: {}", file, e);
        }
      }
    } catch (IOException e) {
      logger.warn("[FilePromptLoader] Failed to scan rules directory: {}", dir, e);
    }
    logger.info("[FilePromptLoader] Loaded {} rule(s) from {}", result.size(), dir);
    return result;
  }

  // ========================================================================
  // Parsing helpers
  // ========================================================================

  /** Basic YAML front-matter parser. Extracts {@code --- ... ---} block at file start. */
  static RuleDef parseRuleDef(String name, String source, String raw) {
    String content = raw;
    String title = null;
    String priority = "medium";
    List<String> appliesTo = List.of();

    if (raw.startsWith("---")) {
      int endIdx = raw.indexOf("---", 3);
      if (endIdx > 3) {
        String frontMatter = raw.substring(3, endIdx).trim();
        content = raw.substring(endIdx + 3).trim();

        for (String line : frontMatter.split("\n")) {
          line = line.trim();
          if (line.startsWith("title:")) {
            title = line.substring(6).trim().replaceAll("^\"|\"$", "");
          } else if (line.startsWith("priority:")) {
            priority = line.substring(9).trim();
          } else if (line.startsWith("applies_to:")) {
            String val = line.substring(11).trim().replaceAll("^\"|\"$", "");
            appliesTo = List.of(val.split("\\s*,\\s*"));
          }
        }
      }
    }

    return new RuleDef(name, source, title, priority, appliesTo, content);
  }

  /** Strip file extension (e.g. {@code "micro_loop.ftl"} → {@code "micro_loop"}). */
  private static String filenameWithoutExt(String filename) {
    int dot = filename.lastIndexOf('.');
    return dot > 0 ? filename.substring(0, dot) : filename;
  }

  /** Convert priority string to numeric rank (lower = higher priority). */
  private static int priorityRank(String p) {
    return switch (p.toLowerCase()) {
      case "high" -> 0;
      case "medium" -> 1;
      case "low" -> 2;
      default -> 3;
    };
  }
}
