package org.cland.alice.model;

import org.cland.alice.model.common.ModelEnum;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 模型提供者核心入口，负责模型注册、策略路由及 Supplier 调度。
 * 对应设计文档中的 ModelProvider 类。
 */
public final class ModelProvider {

    private static volatile ModelProvider INSTANCE;

    /** 已注册的 Supplier 表：supplierName -> ModelSupplier */
    private final Map<String, ModelSupplier> suppliers = new ConcurrentHashMap<>();

    /** 已注册的 Model 表：modelId -> Model */
    private final Map<String, Model> models = new ConcurrentHashMap<>();

    /** 自定义路由策略：接收 modelId，返回 supplierName */
    private volatile Function<String, String> router;

    // ========== 单例 ==========

    private ModelProvider() {
        // 默认路由：按 modelId 查找 supplierName
        this.router = modelId -> {
            Model m = models.get(modelId);
            return m != null ? m.supplierName() : null;
        };
    }

    public static ModelProvider getInstance() {
        if (INSTANCE == null) {
            synchronized (ModelProvider.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ModelProvider();
                }
            }
        }
        return INSTANCE;
    }

    /**
     * 重置单例（仅用于测试）。
     */
    public static synchronized void reset() {
        INSTANCE = null;
    }

    // ========== 注册 ==========

    /**
     * 注册一个 ModelSupplier。
     */
    public ModelProvider registerSupplier(ModelSupplier supplier) {
        Objects.requireNonNull(supplier, "supplier must not be null");
        suppliers.put(supplier.name(), supplier);
        return this;
    }

    /**
     * 注册一个 Model 元数据。
     */
    public ModelProvider registerModel(Model model) {
        Objects.requireNonNull(model, "model must not be null");
        models.put(model.modelId(), model);
        return this;
    }

    /**
     * 从内置枚举批量注册 Model。
     */
    public ModelProvider registerBuiltinModels() {
        for (ModelEnum me : ModelEnum.values()) {
            int mcMask = me.capability().toModelCapabilityMask();
            Model.Capability cap = Model.Capability.fromMask(mcMask);
            Model model = Model.builder()
                .modelId(me.modelId())
                .supplierName(me.supplierName())
                .capability(cap)
                .pricing(new Model.Pricing(me.inputPricePer1K(), me.outputPricePer1K()))
                .build();
            models.put(model.modelId(), model);
        }
        return this;
    }

    /**
     * 设置自定义路由策略。
     */
    public ModelProvider setRouter(Function<String, String> router) {
        this.router = Objects.requireNonNull(router, "router must not be null");
        return this;
    }

    // ========== 查询 ==========

    /**
     * 获取指定 modelId 对应的 Model 元数据。
     */
    public Model getModel(String modelId) {
        return models.get(modelId);
    }

    /**
     * 获取指定 ModelEnum 对应的 ModelSupplier。
     */
    public ModelSupplier get(ModelEnum model) {
        if (model == null) return null;
        String supplierName = models.get(model.modelId()) != null
            ? models.get(model.modelId()).supplierName()
            : model.supplierName();
        return suppliers.get(supplierName);
    }

    /**
     * 获取指定 modelId 对应的 ModelSupplier。
     */
    public ModelSupplier getSupplier(String modelId) {
        String supplierName = router.apply(modelId);
        return supplierName != null ? suppliers.get(supplierName) : null;
    }

    // ========== 调度 ==========

    /**
     * 执行一次模型调用：路由 -> 创建 Call -> 执行 -> 返回。
     *
     * @param modelId 目标模型 ID
     * @param prompt  请求提示词
     * @return 已完成（或失败）的 Call 对象
     */
    public Call dispatch(String modelId, String prompt) {
        return dispatch(modelId, prompt, Map.of());
    }

    /**
     * 执行一次模型调用，附加参数。
     */
    public Call dispatch(String modelId, String prompt, Map<String, Object> parameters) {
        // 1. 查找 ModelSupplier
        ModelSupplier supplier = getSupplier(modelId);
        if (supplier == null) {
            throw new IllegalStateException("No supplier found for modelId: " + modelId);
        }

        // 2. 创建 Call
        Call.Payload payload = new Call.Payload(modelId, prompt, parameters);
        Call call = Call.builder()
            .payload(payload)
            .build();

        // 3. 执行
        call.transitionTo(CallStatus.PENDING);
        call.transitionTo(CallStatus.RUNNING);
        call.metrics().start();

        try {
            Call.Response response = supplier.request(call);
            call.updateResult(response);
            call.metrics().stop();
            call.transitionTo(CallStatus.FINISHED);
        } catch (Exception e) {
            call.metrics().stop();
            call.transitionTo(CallStatus.FAILED);
            throw new RuntimeException("Model call failed: " + modelId, e);
        }

        return call;
    }
}
