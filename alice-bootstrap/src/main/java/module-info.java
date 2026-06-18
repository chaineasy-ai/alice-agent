/**
 * alice-bootstrap — Pure Bootstrapper
 *
 * <p>JVM 入口模块，通过 SPI (ServiceLoader) 发现 facade 实现。 不再编译期强依赖具体 facade 模块。
 *
 * <p>SPI 接口定义在 {@link org.cland.alice.agent.spi.AliceFacade}。
 */
module alice.agent.app.main {
  exports org.cland.alice.agent;
  exports org.cland.alice.agent.spi;

  // SPI: 使用 ServiceLoader 加载 AliceFacade 实现
  uses org.cland.alice.agent.spi.AliceFacade;

  requires org.slf4j;
  requires ch.qos.logback.classic;
}
