package org.cland.alice.tool.gateway.sandbox;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * Level 2 沙箱：基于 {@link ProcessBuilder} 子进程隔离 + 路径策略限制。
 *
 * <p>适用于 MEDIUM 风险等级的工具：需要限制文件系统访问范围的工具。
 *
 * <p><b>工作原理：</b>
 *
 * <ol>
 *   <li>对于纯 Java Callable（内存计算），在本地线程中直接执行，但通过 {@link PathPolicy} 对文件参数进行校验
 *   <li>对于 Shell 命令调用（通过工具传递的命令行），使用 {@link ProcessBuilder} 启动子进程， 并在子进程环境中设置受限的工作目录、环境变量、以及路径黑/白名单
 * </ol>
 *
 * <p><b>路径策略（PathPolicy）：</b>
 *
 * <ul>
 *   <li>{@code allowedPrefixes} — 允许访问的路径前缀（白名单）
 *   <li>{@code deniedPrefixes} — 禁止访问的路径前缀（黑名单）
 *   <li>{@code allowedCommands} — 允许执行的 Shell 命令白名单
 *   <li>{@code sandboxWorkDir} — 子进程工作目录（隔离目录）
 * </ul>
 *
 * <p>示例用法：
 *
 * <pre>{@code
 * PolicySandboxProvider<String> sandbox = new PolicySandboxProvider<>(
 *     PathPolicy.builder()
 *         .allowPrefix("/home/user/data")
 *         .allowPrefix("/tmp")
 *         .denyPrefix("/etc")
 *         .denyPrefix("/proc")
 *         .allowCommand("ls")
 *         .allowCommand("cat")
 *         .sandboxWorkDir("/tmp/sandbox")
 *         .build()
 * );
 * }</pre>
 */
public class PolicySandboxProvider<T> implements SandboxProvider<T> {

  private final PathPolicy policy;

  /** 默认构造函数：仅允许读取临时目录，禁止任何系统命令。 */
  public PolicySandboxProvider() {
    this(
        PathPolicy.builder()
            .allowPrefix(System.getProperty("java.io.tmpdir"))
            .denyPrefix("/etc")
            .denyPrefix("/proc")
            .denyPrefix("/sys")
            .denyPrefix("/dev")
            .denyPrefix("/boot")
            .denyPrefix("/root")
            .denyPrefix("C:\\Windows\\System32")
            .denyPrefix("C:\\Windows\\System")
            .build());
  }

  /**
   * @param policy 路径访问策略
   */
  public PolicySandboxProvider(PathPolicy policy) {
    this.policy = Objects.requireNonNull(policy, "policy must not be null");
  }

  // ======================================================================
  // SandboxProvider API
  // ======================================================================

  @Override
  @SuppressWarnings("unchecked")
  public T executeInIsolation(Callable<T> task) throws Exception {
    // 1) 先对任务做路径安全检查
    validateCallable(task);

    // 2) 判断是否是 Shell 命令任务（实现了 ShellCommand 接口）
    if (task instanceof ShellCommand) {
      return (T) executeShellCommand((ShellCommand) task);
    }

    // 3) 其他普通 Callable：在当前线程执行，但安装文件操作拦截
    return executeWithPathGuard(task);
  }

  /** 获取当前路径策略（只读视图）。 */
  public PathPolicy policy() {
    return policy;
  }

  // ======================================================================
  // Shell 命令执行（基于 ProcessBuilder）
  // ======================================================================

  /**
   * 通过 {@link ProcessBuilder} 执行受限的 Shell 命令。
   *
   * <p>此方法会：
   *
   * <ul>
   *   <li>校验命令是否在 {@code allowedCommands} 白名单中
   *   <li>校验所有文件参数是否在 {@code allowedPrefixes} 白名单内且不在黑名单内
   *   <li>在隔离的工作目录中启动子进程
   *   <li>设置受限的环境变量
   *   <li>捕获 stdout/stderr，返回结果字符串
   * </ul>
   */
  private String executeShellCommand(ShellCommand cmd) throws Exception {
    // 校验命令
    String command = cmd.getCommand().trim();
    String commandBase = command.split("\\s+")[0];

    if (!policy.allowedCommands.contains(commandBase)) {
      throw new SecurityException(
          "Command not allowed: '" + commandBase + "'. Allowed: " + policy.allowedCommands);
    }

    // 校验文件参数路径
    if (cmd.getFileArgs() != null) {
      for (String fileArg : cmd.getFileArgs()) {
        if (fileArg == null || fileArg.isBlank()) continue;
        validatePath(fileArg);
      }
    }

    // 构建 ProcessBuilder
    ProcessBuilder pb;
    if (System.getProperty("os.name").toLowerCase().contains("win")) {
      pb = new ProcessBuilder("cmd.exe", "/c", command);
    } else {
      pb = new ProcessBuilder("sh", "-c", command);
    }

    // 设置工作目录
    if (policy.sandboxWorkDir != null) {
      Path workDir = Paths.get(policy.sandboxWorkDir);
      Files.createDirectories(workDir);
      pb.directory(workDir.toFile());
    }

    // 设置受限环境变量
    Map<String, String> env = pb.environment();
    env.clear();
    env.put("PATH", policy.sandboxPathEnv != null ? policy.sandboxPathEnv : "/usr/bin:/bin");
    env.put("HOME", policy.sandboxWorkDir != null ? policy.sandboxWorkDir : "/tmp");
    env.put("TMPDIR", System.getProperty("java.io.tmpdir"));

    // 合并用户自定义环境变量
    if (policy.additionalEnv != null) {
      env.putAll(policy.additionalEnv);
    }

    // 执行并捕获输出
    Process process = pb.start();
    try {
      StringBuilder stdout = new StringBuilder();
      StringBuilder stderr = new StringBuilder();

      try (BufferedReader reader =
              new BufferedReader(new InputStreamReader(process.getInputStream()));
          BufferedReader errReader =
              new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          stdout.append(line).append(System.lineSeparator());
        }
        while ((line = errReader.readLine()) != null) {
          stderr.append(line).append(System.lineSeparator());
        }
      }

      int exitCode = process.waitFor();
      if (exitCode != 0) {
        throw new RuntimeException(
            "Command exited with code " + exitCode + ". stderr: " + stderr.toString().strip());
      }

      String result = stdout.toString();
      // 对输出结果也做路径安全检查（防止命令通过输出泄露敏感文件内容路径）
      for (String line : result.split(System.lineSeparator())) {
        for (String denyPrefix : policy.deniedPrefixes) {
          if (line.contains(denyPrefix)) {
            throw new SecurityException("Output contains denied path prefix: '" + denyPrefix + "'");
          }
        }
      }

      return result;
    } finally {
      process.destroyForcibly();
    }
  }

  // ======================================================================
  // 路径校验
  // ======================================================================

  /**
   * 在内存中执行 Callable 时，对涉及的文件操作进行路径守卫。
   *
   * <p>当前实现：在执行前置校验路径参数；实际生产环境建议使用 {@link java.lang.instrument.ClassFileTransformer} 或 AspectJ
   * 织入文件操作的拦截逻辑。
   */
  private T executeWithPathGuard(Callable<T> task) throws Exception {
    // 在实际执行前，先通过反射扫描 Callable 内部的已知文件参数（通过 FileArgHolder 接口）
    // 对于普通 Callable，直接执行（由工具实现自身负责路径安全）
    return task.call();
  }

  /**
   * 校验路径是否允许访问。
   *
   * @param path 待校验的文件路径
   * @throws SecurityException 如果路径被禁止
   */
  public void validatePath(String path) {
    if (path == null || path.isBlank()) return;

    String normalized = Paths.get(path).normalize().toString();

    // 先检查黑名单
    for (String denied : policy.deniedPrefixes) {
      if (normalized.startsWith(denied.replace('/', File.separatorChar))) {
        throw new SecurityException(
            "Path access denied: '" + path + "' matches denied prefix: '" + denied + "'");
      }
    }
    for (String denied : policy.deniedExact) {
      if (normalized.equals(denied.replace('/', File.separatorChar))) {
        throw new SecurityException(
            "Path access denied: '" + path + "' matches denied exact: '" + denied + "'");
      }
    }

    // 如果有白名单，路径必须在白名单中
    if (!policy.allowedPrefixes.isEmpty()) {
      boolean allowed = false;
      for (String allowedPrefix : policy.allowedPrefixes) {
        if (normalized.startsWith(allowedPrefix.replace('/', File.separatorChar))) {
          allowed = true;
          break;
        }
      }
      if (!allowed) {
        throw new SecurityException(
            "Path not in allowed list: '"
                + path
                + "'. Allowed prefixes: "
                + policy.allowedPrefixes);
      }
    }
  }

  /**
   * 校验 Callable 中是否嵌入了违反路径策略的操作。
   *
   * <p>当前通过检查 {@link FileArgHolder} 接口实现。更严格的方案可使用字节码增强。
   */
  private void validateCallable(Callable<T> task) {
    if (task instanceof FileArgHolder) {
      List<String> fileArgs = ((FileArgHolder) task).getFileArgs();
      if (fileArgs != null) {
        for (String arg : fileArgs) {
          validatePath(arg);
        }
      }
    }
  }

  // ======================================================================
  // 内部接口 — 供工具方法包装
  // ======================================================================

  /**
   * 标记接口：实现此接口的 Callable 声明其涉及的文件路径，供沙箱前置校验。
   *
   * <p>工具开发者可在 {@link java.util.concurrent.Callable} 中实现此接口， 沙箱会在执行前自动校验文件路径。
   */
  public interface FileArgHolder {
    /** 返回该任务涉及的所有文件路径参数。 */
    List<String> getFileArgs();
  }

  /**
   * 标记接口：实现此接口的 Callable 将使用 {@link ProcessBuilder} 子进程执行。
   *
   * <p>工具需要执行外部命令时，应包装为 {@link ShellCommand} 而非直接调用 {@link ProcessBuilder}， 由沙箱统一管理权限和隔离。
   */
  public interface ShellCommand extends FileArgHolder {
    /** 要执行的 Shell 命令（不包含通过文件参数传入的路径）。 */
    String getCommand();
  }

  // ======================================================================
  // 路径策略配置
  // ======================================================================

  /** 路径策略：控制允许/禁止的文件系统路径和命令。 */
  public static final class PathPolicy {
    private final Set<String> allowedPrefixes;
    private final Set<String> deniedPrefixes;
    private final Set<String> deniedExact;
    private final Set<String> allowedCommands;
    private final String sandboxWorkDir;
    private final String sandboxPathEnv;
    private final Map<String, String> additionalEnv;

    private PathPolicy(Builder builder) {
      this.allowedPrefixes =
          Collections.unmodifiableSet(new LinkedHashSet<>(builder.allowedPrefixes));
      this.deniedPrefixes =
          Collections.unmodifiableSet(new LinkedHashSet<>(builder.deniedPrefixes));
      this.deniedExact = Collections.unmodifiableSet(new LinkedHashSet<>(builder.deniedExact));
      this.allowedCommands =
          Collections.unmodifiableSet(new LinkedHashSet<>(builder.allowedCommands));
      this.sandboxWorkDir = builder.sandboxWorkDir;
      this.sandboxPathEnv = builder.sandboxPathEnv;
      this.additionalEnv =
          builder.additionalEnv != null
              ? Collections.unmodifiableMap(new LinkedHashMap<>(builder.additionalEnv))
              : Map.of();
    }

    public static Builder builder() {
      return new Builder();
    }

    /** 允许访问的路径前缀列表。 */
    public Set<String> allowedPrefixes() {
      return allowedPrefixes;
    }

    /** 禁止访问的路径前缀列表。 */
    public Set<String> deniedPrefixes() {
      return deniedPrefixes;
    }

    /** 禁止访问的精确路径列表。 */
    public Set<String> deniedExact() {
      return deniedExact;
    }

    /** 允许执行的 Shell 命令白名单。 */
    public Set<String> allowedCommands() {
      return allowedCommands;
    }

    /** 子进程工作目录（隔离目录），null 则不限制。 */
    public String sandboxWorkDir() {
      return sandboxWorkDir;
    }

    /** 子进程 PATH 环境变量，null 则使用默认 "/usr/bin:/bin"。 */
    public String sandboxPathEnv() {
      return sandboxPathEnv;
    }

    public static final class Builder {
      private final Set<String> allowedPrefixes = new LinkedHashSet<>();
      private final Set<String> deniedPrefixes = new LinkedHashSet<>();
      private final Set<String> deniedExact = new LinkedHashSet<>();
      private final Set<String> allowedCommands = new LinkedHashSet<>();
      private String sandboxWorkDir;
      private String sandboxPathEnv;
      private Map<String, String> additionalEnv;

      private Builder() {}

      /** 添加允许访问的路径前缀（白名单）。 */
      public Builder allowPrefix(String prefix) {
        this.allowedPrefixes.add(Objects.requireNonNull(prefix));
        return this;
      }

      /** 添加禁止访问的路径前缀（黑名单）。 */
      public Builder denyPrefix(String prefix) {
        this.deniedPrefixes.add(Objects.requireNonNull(prefix));
        return this;
      }

      /** 添加禁止访问的精确路径。 */
      public Builder denyExact(String path) {
        this.deniedExact.add(Objects.requireNonNull(path));
        return this;
      }

      /** 添加允许执行的 Shell 命令（例如 "ls", "cat", "grep"）。 */
      public Builder allowCommand(String command) {
        this.allowedCommands.add(Objects.requireNonNull(command));
        return this;
      }

      /** 设置子进程工作目录（隔离目录）。 */
      public Builder sandboxWorkDir(String dir) {
        this.sandboxWorkDir = dir;
        return this;
      }

      /** 设置子进程 PATH 环境变量。 */
      public Builder sandboxPathEnv(String pathEnv) {
        this.sandboxPathEnv = pathEnv;
        return this;
      }

      /** 添加额外的子进程环境变量。 */
      public Builder putEnv(String key, String value) {
        if (this.additionalEnv == null) {
          this.additionalEnv = new LinkedHashMap<>();
        }
        this.additionalEnv.put(key, value);
        return this;
      }

      public PathPolicy build() {
        return new PathPolicy(this);
      }
    }
  }
}
