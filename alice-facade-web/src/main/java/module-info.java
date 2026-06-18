/**
 * alice-facade-web — Web Facade 模块
 *
 * <p>基于 Quarkus 的响应式 Web 门面。 当前提供健康检查端点（GET /api/v1/health）。
 *
 * <p>遵循严格解耦原则，仅依赖 alice-agent-command 契约层。
 */
module alice.agent.facade.web.main {
  exports org.cland.alice.facade.web;

  requires alice.agent.command.main;

  // Quarkus & RESTEasy Reactive (automatic modules from jars)
  requires jakarta.ws.rs;
  requires jakarta.inject;

  // Jackson
  requires com.fasterxml.jackson.databind;
  requires com.fasterxml.jackson.core;

  // Logging
  requires org.slf4j;
  requires ch.qos.logback.classic;

  // Allow Quarkus reflection at runtime
  opens org.cland.alice.facade.web to
      jakarta.ws.rs,
      jakarta.inject;
}
