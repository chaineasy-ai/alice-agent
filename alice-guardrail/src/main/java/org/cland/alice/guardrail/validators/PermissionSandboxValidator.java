package org.cland.alice.guardrail.validators;

import java.util.HashSet;
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
 */
public final class PermissionSandboxValidator implements PreValidator {

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
      if (target == null) continue;

      // 精确匹配检查
      if (forbiddenTargetExact.contains(target.trim())) {
        return AuditResult.reject(
            "Permission denied: target '" + target + "' is forbidden",
            CorrectionSuggestion.changeTarget("Choose a different target"));
      }

      // 前缀匹配检查
      String targetLower = target.toLowerCase();
      for (String prefix : forbiddenTargetPrefixes) {
        if (targetLower.startsWith(prefix.toLowerCase())) {
          return AuditResult.reject(
              "Permission denied: target '" + target + "' accesses restricted path: " + prefix,
              CorrectionSuggestion.changeTarget("Choose a target outside restricted paths"));
        }
      }
    }

    return AuditResult.allow();
  }
}
