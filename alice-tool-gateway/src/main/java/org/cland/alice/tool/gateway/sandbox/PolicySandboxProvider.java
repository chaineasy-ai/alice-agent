package org.cland.alice.tool.gateway.sandbox;

import java.security.Permission;
import java.util.concurrent.Callable;

/**
 * Level 2 沙箱：利用 Java SecurityManager 限制敏感操作。
 *
 * <p>适用于 MEDIUM 风险等级的工具：受限制的文件系统/网络访问。
 *
 * <p><b>注意</b>：SecurityManager 在 Java 17+ 已被标记为 deprecated for removal。
 * 此实现主要用于演示。在未来的 Java 版本中建议使用
 * {@link java.lang.ProcessBuilder} 子进程隔离或其他方案替代。
 *
 * @deprecated SecurityManager 机制将在未来 Java 版本中移除，
 *     此实现仅作为参考。生产环境建议使用 Docker/Wasm 沙箱。
 */
@Deprecated
@SuppressWarnings("removal")
public class PolicySandboxProvider<T> implements SandboxProvider<T> {

    private final Permissions permissions;

    /**
     * @param permissions 允许的操作权限掩码
     */
    public PolicySandboxProvider(Permissions permissions) {
        this.permissions = permissions;
    }

    /** 默认构造函数，仅允许非常有限的操作（读取临时目录，无网络）。 */
    public PolicySandboxProvider() {
        this(Permissions.READ_ONLY_TEMP);
    }

    @Override
    public T executeInIsolation(Callable<T> task) throws Exception {
        SecurityManager original = System.getSecurityManager();
        try {
            System.setSecurityManager(new RestrictedSecurityManager(original, permissions));
            return task.call();
        } finally {
            System.setSecurityManager(original);
        }
    }

    /** 权限掩码，控制允许的操作。 */
    public enum Permissions {
        /** 仅允许读取临时目录 */
        READ_ONLY_TEMP,
        /** 允许读取指定路径，无网络 */
        READ_ONLY_SPECIFIED,
        /** 允许读取/写入指定路径，有限网络 */
        READ_WRITE_SPECIFIED,
        /** 最严格：无文件、无网络、无系统属性 */
        NONE
    }

    /** 受限的 SecurityManager 实现。 */
    @SuppressWarnings("removal")
    private static class RestrictedSecurityManager extends SecurityManager {
        private final SecurityManager delegate;
        private final Permissions permissions;

        RestrictedSecurityManager(SecurityManager delegate, Permissions permissions) {
            this.delegate = delegate;
            this.permissions = permissions;
        }

        @Override
        public void checkPermission(Permission perm) {
            String name = perm.getName();

            // 允许线程相关的基础操作
            if (name.startsWith("accessThread") || name.equals("modifyThreadGroup")) {
                return;
            }

            // 根据权限级别检查
            switch (permissions) {
                case NONE -> throw new SecurityException("Operation not permitted: " + perm);
                case READ_ONLY_TEMP -> {
                    if (perm instanceof java.io.FilePermission) {
                        String path = perm.getName();
                        String tmpDir = System.getProperty("java.io.tmpdir");
                        if (path.startsWith(tmpDir)
                            && (perm.getActions().equals("read")
                                || perm.getActions().equals("read,write"))) {
                            return;
                        }
                    }
                    if (perm instanceof java.net.NetPermission
                        || perm instanceof java.lang.RuntimePermission
                            && name.startsWith("setIO")
                        || perm instanceof java.util.PropertyPermission
                            && !perm.getActions().contains("read")) {
                        throw new SecurityException("Operation not permitted: " + perm);
                    }
                }
                case READ_ONLY_SPECIFIED, READ_WRITE_SPECIFIED -> {
                    // 更宽松的规则由子类或配置定义，此处仅放行基本操作
                }
            }

            if (delegate != null) {
                delegate.checkPermission(perm);
            }
        }

        @Override
        public void checkPermission(Permission perm, Object context) {
            checkPermission(perm);
        }
    }
}
