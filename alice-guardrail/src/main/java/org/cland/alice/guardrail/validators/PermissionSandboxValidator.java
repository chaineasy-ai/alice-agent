package org.cland.alice.guardrail.validators;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.cland.alice.core.planner.Plan;
import org.cland.alice.guardrail.AuditResult;
import org.cland.alice.guardrail.CorrectionSuggestion;
import org.cland.alice.guardrail.PreValidator;

/**
 * 权限沙箱验证器 (Pre-Validator)。
 *
 * <p>对应设计文档中 "权限沙箱" 的具体实现。 验证 Agent 是否试图访问超出其 Scope 的资源。
 *
 * <p><b>检查规则：</b>
 *
 * <ul>
 *   <li>拒绝访问系统核心路径（如 /etc, /proc, /sys）
 *   <li>拒绝执行敏感系统命令（如 rm -rf /, dd, mkfs）
 *   <li>拒绝访问外部受限网络资源
 * </ul>
 *
 * <p><b>检查字段：</b>
 *
 * <ul>
 *   <li>{@link Plan.Step#target()} —— 保持原有语义（精确匹配 + 前缀匹配）；
 *   <li>{@link Plan.Step#parameters()} —— 递归扫描所有字符串值（String / List / Map / 数组 / 嵌套），
 *       因为装配层（GuardrailToolProxy）把 target 设为工具名，危险路径与命令都在参数里。
 * </ul>
 *
 * <p><b>参数扫描规则：</b>
 *
 * <ul>
 *   <li>路径类：字符串按 shell 分隔符切分后，任一路径样 token 命中 {@code forbiddenTargetPrefixes} （含 {@code key=value}
 *       形态的值部分）⇒ REJECT；
 *   <li>命令类：切分后的 token 等于 {@code forbiddenTargetExact} 中的命令 ⇒ REJECT； 多词短语（如 {@code rm -rf /}）按"连续
 *       token 子序列"匹配。
 * </ul>
 */
public final class PermissionSandboxValidator implements PreValidator {

  /** shell 分隔符：空白、;、&&、||、|、换行。 */
  private static final String SHELL_TOKEN_SEPARATORS = "[\\s;|&]+";

  private final Set<String> forbiddenTargetPrefixes;
  private final Set<String> forbiddenTargetExact;

  public PermissionSandboxValidator() {
    this.forbiddenTargetPrefixes = new HashSet<>();
    this.forbiddenTargetExact = new HashSet<>();
    initDefaults();
  }

  private void initDefaults() {
    // 系统文件路径
    forbiddenTargetPrefixes.add("/etc/");
    forbiddenTargetPrefixes.add("/proc/");
    forbiddenTargetPrefixes.add("/sys/");
    forbiddenTargetPrefixes.add("/dev/");
    forbiddenTargetPrefixes.add("/boot/");
    forbiddenTargetPrefixes.add("/root/");
    forbiddenTargetPrefixes.add("/var/log/");

    // 系统命令
    forbiddenTargetExact.add("rm -rf /");
    forbiddenTargetExact.add("dd");
    forbiddenTargetExact.add("mkfs");
    forbiddenTargetExact.add("shutdown");
    forbiddenTargetExact.add("reboot");
    forbiddenTargetExact.add("init");

    // Windows 敏感路径
    forbiddenTargetPrefixes.add("C:\\Windows\\System32\\");
    forbiddenTargetPrefixes.add("C:\\Windows\\System\\");
  }

  /** 添加禁止访问的前缀模式。 */
  public void addForbiddenPrefix(String prefix) {
    this.forbiddenTargetPrefixes.add(prefix);
  }

  /** 添加禁止访问的精确匹配。 */
  public void addForbiddenExact(String target) {
    this.forbiddenTargetExact.add(target);
  }

  @Override
  public AuditResult check(Plan plan) {
    for (Plan.Step step : plan.steps()) {
      String target = step.target();
      if (target != null) {
        // 精确匹配检查（原有语义，保持不变）
        if (forbiddenTargetExact.contains(target.trim())) {
          return AuditResult.reject(
              "Permission denied: target '" + target + "' is forbidden",
              CorrectionSuggestion.changeTarget("Choose a different target"));
        }

        // 前缀匹配检查（原有语义，保持不变）
        String targetLower = target.toLowerCase();
        for (String prefix : forbiddenTargetPrefixes) {
          if (targetLower.startsWith(prefix.toLowerCase())) {
            return AuditResult.reject(
                "Permission denied: target '" + target + "' accesses restricted path: " + prefix,
                CorrectionSuggestion.changeTarget("Choose a target outside restricted paths"));
          }
        }
      }

      // 参数检查（#665）：危险路径与命令藏在 Parameters 里
      for (String value : collectParameterStrings(step.parameters())) {
        AuditResult rejected = checkParameterValue(value);
        if (rejected != null) {
          return rejected;
        }
      }
    }

    return AuditResult.allow();
  }

  // ========== 参数校验 ==========

  /** 检查单个参数值；命中则返回 REJECT，否则返回 null。 */
  private AuditResult checkParameterValue(String value) {
    List<String> tokens = tokenize(value);

    // 1) 路径类
    for (String token : tokens) {
      String restrictedPrefix = findRestrictedPrefix(token);
      if (restrictedPrefix != null) {
        return AuditResult.reject(
            "Permission denied: parameter '"
                + value
                + "' accesses restricted path: "
                + restrictedPrefix,
            CorrectionSuggestion.changeTarget(
                "Use a path outside restricted system directory (matched prefix: "
                    + restrictedPrefix
                    + ")"));
      }
    }

    // 2) 命令类（单 token 精确匹配；多词短语按连续 token 子序列匹配）
    String restrictedCommand = findRestrictedCommand(tokens);
    if (restrictedCommand != null) {
      return AuditResult.reject(
          "Permission denied: parameter '"
              + value
              + "' invokes forbidden command: "
              + restrictedCommand,
          CorrectionSuggestion.changeTarget(
              "Avoid executing system-destructive command '"
                  + restrictedCommand
                  + "'; choose a different command"));
    }

    return null;
  }

  /**
   * 将字符串切成 shell 样 token，并展开 {@code key=value} 形态的值部分。
   *
   * <p>例如 {@code "dd if=/dev/zero of=/tmp/x"} ⇒ {@code [dd, if=/dev/zero, /dev/zero, of=/tmp/x,
   * /tmp/x]}。
   */
  private static List<String> tokenize(String value) {
    List<String> tokens = new ArrayList<>();
    for (String raw : value.split(SHELL_TOKEN_SEPARATORS)) {
      String token = stripQuotes(raw);
      if (token.isEmpty()) continue;
      tokens.add(token);
      int eq = token.indexOf('=');
      if (eq >= 0 && eq < token.length() - 1) {
        String afterEquals = stripQuotes(token.substring(eq + 1));
        if (!afterEquals.isEmpty()) {
          tokens.add(afterEquals);
        }
      }
    }
    return tokens;
  }

  private static String stripQuotes(String token) {
    String trimmed = token.trim();
    while (trimmed.length() >= 2) {
      char first = trimmed.charAt(0);
      char last = trimmed.charAt(trimmed.length() - 1);
      if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
        trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
      } else {
        break;
      }
    }
    return trimmed;
  }

  /** 返回命中的受限前缀；未命中返回 null。 */
  private String findRestrictedPrefix(String candidate) {
    String candidateLower = candidate.toLowerCase();
    for (String prefix : forbiddenTargetPrefixes) {
      if (candidateLower.startsWith(prefix.toLowerCase())) {
        return prefix;
      }
    }
    return null;
  }

  /** 返回命中的受限命令；未命中返回 null。 */
  private String findRestrictedCommand(List<String> tokens) {
    for (String forbidden : forbiddenTargetExact) {
      List<String> phrase = splitPhrase(forbidden);
      if (phrase.isEmpty()) continue;
      if (phrase.size() == 1) {
        if (tokens.contains(phrase.get(0))) {
          return forbidden;
        }
      } else if (containsContiguousSequence(tokens, phrase)) {
        return forbidden;
      }
    }
    return null;
  }

  private static List<String> splitPhrase(String forbidden) {
    List<String> phrase = new ArrayList<>();
    for (String word : forbidden.trim().split("\\s+")) {
      if (!word.isEmpty()) phrase.add(word);
    }
    return phrase;
  }

  private static boolean containsContiguousSequence(List<String> tokens, List<String> phrase) {
    int lastStart = tokens.size() - phrase.size();
    for (int start = 0; start <= lastStart; start++) {
      boolean matched = true;
      for (int offset = 0; offset < phrase.size(); offset++) {
        if (!tokens.get(start + offset).equals(phrase.get(offset))) {
          matched = false;
          break;
        }
      }
      if (matched) return true;
    }
    return false;
  }

  // ========== 参数递归收集 ==========

  /** 递归收集参数中的字符串值（String / Collection / Map / 数组；非字符串跳过）。 */
  private static List<String> collectParameterStrings(Map<String, Object> parameters) {
    List<String> values = new ArrayList<>();
    if (parameters == null || parameters.isEmpty()) {
      return values;
    }
    for (Object value : parameters.values()) {
      collectStrings(value, values);
    }
    return values;
  }

  private static void collectStrings(Object value, List<String> out) {
    if (value == null) {
      return;
    }
    if (value instanceof String str) {
      out.add(str);
      return;
    }
    if (value instanceof Map<?, ?> map) {
      for (Object nested : map.values()) {
        collectStrings(nested, out);
      }
      return;
    }
    if (value instanceof Collection<?> collection) {
      for (Object nested : collection) {
        collectStrings(nested, out);
      }
      return;
    }
    if (value.getClass().isArray()) {
      int length = Array.getLength(value);
      for (int i = 0; i < length; i++) {
        collectStrings(Array.get(value, i), out);
      }
    }
    // 其它类型（数字 / 布尔 / 对象）跳过
  }
}
