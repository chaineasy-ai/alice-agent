/*
 * Alice Agent — Web Facade: HealthController
 *
 * Minimal health check endpoint for the web facade.
 */
package org.cland.alice.facade.web;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * HealthController — 健康检查端点。
 *
 * <p>供 Kubernetes / Docker 健康探针使用。
 */
@Path("/api/v1")
public class HealthController {

  @GET
  @Path("/health")
  @Produces(MediaType.APPLICATION_JSON)
  public Response health() {
    return Response.ok("{\"status\":\"UP\"}").build();
  }
}
