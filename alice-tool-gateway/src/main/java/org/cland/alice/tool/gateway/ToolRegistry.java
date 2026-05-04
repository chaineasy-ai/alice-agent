package org.cland.alice.tool.gateway;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 工具注册中心，管理所有可被 Agent 调用的外部工具。
 * <p>
 * 对应设计文档中 ToolGateway (T) 的角色，负责：
 * <ul>
 *   <li>注册工具（名称 -> 执行函数映射）</li>
 *   <li>派发 Action 到对应的工具执行器</li>
 *   <li>统一的工具调用生命周期管理</li>
 * </ul>
 */
public class ToolRegistry {

    private final Map<String, Function<Map<String, Object>, Boolean>> tools = new ConcurrentHashMap<>();

    /**
     * 注册一个工具。
     *
     * @param name     工具名称
     * @param executor 工具执行函数（接收参数 Map，返回是否成功）
     */
    public void register(String name, Function<Map<String, Object>, Boolean> executor) {
        tools.put(Objects.requireNonNull(name, "name must not be null"),
                  Objects.requireNonNull(executor, "executor must not be null"));
    }

    /**
     * 执行一个已注册的工具。
     *
     * @param name   工具名称
     * @param params 工具参数
     * @return true 表示执行成功，false 表示失败
     * @throws IllegalArgumentException 如果工具未注册
     */
    public boolean execute(String name, Map<String, Object> params) {
        Function<Map<String, Object>, Boolean> executor = tools.get(name);
        if (executor == null) {
            throw new IllegalArgumentException("Tool not registered: " + name);
        }
        return executor.apply(params);
    }

    /**
     * 检查工具是否已注册。
     */
    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    /**
     * 获取所有注册的工具名称。
     */
    public java.util.Set<String> toolNames() {
        return tools.keySet();
    }

    /**
     * 移除一个工具注册。
     */
    public void unregister(String name) {
        tools.remove(name);
    }
}
