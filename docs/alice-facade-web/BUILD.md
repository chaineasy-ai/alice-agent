---
title: "Web Facade Build"
summary: "Build and run notes for the web facade (alice-facade-web)"
read_when:
  - "running Web mode"
  - "building Web facade"
  - "debugging Quarkus startup"
scope:
  - "alice-facade-web"
status: "active"
updated: "2026-06-19"
---

# Web Facade — Build & Run

## Run Web Mode

```bash
# Quarkus dev mode (hot reload)
./gradlew :alice-facade-web:quarkusDev

# Or run the built JAR directly
./gradlew :alice-facade-web:jar
java -jar alice-facade-web/build/libs/alice-facade-web-0.1.0.jar
```

## Available Endpoints

| Method | Route            | Description    |
|--------|------------------|----------------|
| GET    | `/api/v1/health` | Health check   |

## Dependencies

The web facade follows strict dependency inversion:

- **`alice-agent-command`** — Only command contracts (compiled dependency)
- **`alice-bootstrap`** — SPI interface for facade discovery
- **Quarkus 3.21.3** — Reactive web container (RESTEasy Reactive + Jackson + Arc CDI)
- **No dependency on `alice-core-agent`** — core implementation is injected via Quarkus CDI at runtime

## SPI Integration

When a `AliceWebFacade` SPI implementation is added (subclassing `AliceFacade`),
the web facade can be launched via:

```bash
./gradlew :alice-bootstrap:run --args="--facade web"
```

Until then, the web facade runs independently as a Quarkus application.
