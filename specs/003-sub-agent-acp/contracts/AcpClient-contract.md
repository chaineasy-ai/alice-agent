# Contract: AcpClient — ACP Protocol Client Wrapper

## Overview

A thin wrapper around the ACP Java SDK's `AcpSyncClient` for connecting to and communicating with external ACP-compliant agents.

## Interface

```java
public interface AcpClient extends AutoCloseable {
    void initialize();
    AcpSession newSession(AcpSessionConfig config);
    AcpResponse prompt(String sessionId, String prompt);
    void close();
}
```

## Session Config

```java
public record AcpSessionConfig(
    String workspacePath,
    List<String> initialContent
) {}
```

## Error Handling

- Connection failures: throw `AcpConnectionException` with cause and endpoint info
- Timeout: throw `AcpTimeoutException` after configurable timeout (default: 30s)
- Protocol errors: wrap SDK exceptions in `AcpProtocolException`
- Graceful degradation: `prompt()` returns a failure result instead of crashing the parent session

## ACP Protocol Flow

1. `initialize()` → Handshake with ACP agent
2. `newSession(config)` → Create workspace session
3. `prompt(sessionId, text)` → Send prompt, receive response
4. `close()` → Terminate connection

Refer to `docs/acp/README.md` for the SDK's full three-phase lifecycle documentation.
