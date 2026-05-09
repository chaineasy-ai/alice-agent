package org.cland.alice.model;

/** 物理适配器，负责将标准请求转换为特定厂商的 API 协议。 对应设计文档中的 ModelSupplier 接口。 */
@FunctionalInterface
public interface ModelSupplier {

  /** 供应商名称。 */
  default String name() {
    return getClass().getSimpleName();
  }

  /**
   * 执行一次模型调用。
   *
   * @param call 已创建的 Call 对象，包含请求负载及上下文
   * @return 标准化的响应结果
   * @throws Exception 当网络 / API 错误发生时抛出
   */
  Call.Response request(Call call) throws Exception;
}
