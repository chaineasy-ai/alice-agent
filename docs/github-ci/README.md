---
title: "GitHub CI Workflows"
summary: "CI/CD pipeline design for alice-agent — build, test, native-image (Linux/macOS/Windows), and release publishing"
read_when:
  - "understanding or modifying the CI/CD pipeline"
  - "debugging GitHub Actions build failures"
  - "setting up or releasing native binaries for all platforms"
  - "reviewing the publish workflow for artifact distribution"
scope:
  - "docs"
  - "alice-bootstrap"
status: "active"
updated: "2026-07-04"
---

# GitHub CI Workflows

Alice Agent uses two GitHub Actions workflows for continuous integration and release publishing.

## Workflow Overview

| Workflow | File | Trigger | Purpose |
|----------|------|---------|---------|
| **CI** | `.github/workflows/ci.yml` | `push` (main/develop), `pull_request` (main) | Build, test, JVM distribution, native-image verification |
| **Publish** | `.github/workflows/publish.yml` | `release: [published]` | Build native binaries + JVM dist for all platforms, attach to Release |

---

## CI Workflow (ci.yml)

### Trigger Events

```yaml
on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main]
```

### Jobs

#### 1. `build` — JVM Build & Test (3 × OS matrix)

| Matrix | OS | Runner |
|--------|----|--------|
| `ubuntu-latest` | Linux (x86_64) | GitHub-hosted |
| `macos-latest` | macOS (ARM64) | GitHub-hosted |
| `windows-latest` | Windows (x86_64) | GitHub-hosted |

Steps:
1. **Checkout** — `actions/checkout@v4`
2. **Set up JDK 25 (Temurin)** — `actions/setup-java@v4` with Gradle cache
3. **`./gradlew assemble`** — Compile all modules
4. **`./gradlew test`** — Run all Spock unit tests
5. **`./gradlew :alice-bootstrap:installDist`** — Build JVM distribution (start scripts + bundled JARs)
6. **Upload artifact** — `alice-jvm-dist-<os>` (7-day retention)
7. **Verify launcher** — Check `bin/alice` (Linux/macOS) or `bin/alice.bat` (Windows) exists

**`fail-fast: false`** — a failure on one OS does not cancel the others.

#### 2. `native-image` — GraalVM Native Image (3 × OS matrix)

Depends on `build`.

Steps:
1. **Checkout**
2. **Set up GraalVM JDK 25** — `distribution: graalvm`
3. **`gu install native-image`** — Ensure native-image tooling is available
4. **`./gradlew :alice-bootstrap:nativeCompile`** — Build native executable
5. **Verify binary** — Check `alice` (Linux/macOS) or `alice.exe` (Windows) exists
6. **Upload artifact** — `alice-native-<os>` (7-day retention, excludes metadata files)

**Output paths**:

| OS | Native binary |
|----|---------------|
| Linux | `alice-bootstrap/build/native/nativeCompile/alice` (ELF) |
| macOS | `alice-bootstrap/build/native/nativeCompile/alice` (Mach-O) |
| Windows | `alice-bootstrap/build/native/nativeCompile/alice.exe` (PE) |

---

## Publish Workflow (publish.yml)

### Trigger Events

```yaml
on:
  release:
    types: [published]
```

Fires when a GitHub Release is **published** (including when a draft is promoted to published).  
Uses `${{ github.event.release.tag_name }}` to checkout the exact tagged commit.

### Jobs

#### 1. `build` — JVM Distribution (3 × OS)

Same as CI `build` job, with an extra packaging step:
- **Linux/macOS**: `tar czf alice-jvm-<os>.tar.gz alice/`
- **Windows**: `Compress-Archive` → `alice-jvm-windows-latest.zip`

Retention: **1 day** (intermediate artifact, only needed by `attach`).

#### 2. `native-image` — Native Binary (3 × OS)

Same as CI `native-image` job, with extra steps:
- **Linux**: `strip` debug symbols, then `gzip` → `alice-linux-amd64.gz`
- **macOS**: `gzip` → `alice-darwin-amd64.gz`
- **Windows**: `Compress-Archive` → `alice-windows-amd64.zip`

Retention: **1 day** (intermediate artifact).

#### 3. `attach` — Upload Assets to Release

Depends on `native-image`. Runs on `ubuntu-latest` with `permissions: contents: write`.

Steps:
1. Download all 3 native artifacts + all 3 JVM artifacts
2. Copy into `release-assets/`
3. Upload to the Release via `softprops/action-gh-release@v2`

**Release assets** (visible on the Release page):

| File | Content | Size (approx) |
|------|---------|---------------|
| `alice-linux-amd64.gz` | Native binary (Linux x86_64) | 18-30 MB |
| `alice-darwin-amd64.gz` | Native binary (macOS ARM64) | 18-30 MB |
| `alice-windows-amd64.zip` | Native binary (Windows x86_64) | 18-30 MB |
| `alice-jvm-ubuntu-latest.tar.gz` | JVM distribution (Linux) | 50-80 MB |
| `alice-jvm-macos-latest.tar.gz` | JVM distribution (macOS) | 50-80 MB |
| `alice-jvm-windows-latest.zip` | JVM distribution (Windows) | 50-80 MB |

---

## How to Release

```bash
# 1. Tag the release
git tag -a v0.2.0 -m "Release v0.2.0"
git push origin v0.2.0

# 2. In GitHub UI, create a Release from tag v0.2.0
#    Write release notes, then click "Publish release"

# 3. Publish workflow triggers automatically:
#    build (3×OS) → native-image (3×OS) → attach → assets appear on Release page
```

---

## Native Image Build Configuration

Defined in `alice-bootstrap/build.gradle` under the `graalvmNative` block.

Key settings:

```groovy
graalvmNative {
    binaries {
        main {
            imageName = 'alice'

            buildArgs.add('-H:+UnlockExperimentalVMOptions')
            buildArgs.add('-Dfile.encoding=UTF-8')
            buildArgs.add('-Dsun.stdout.encoding=UTF-8')
            buildArgs.add('-Dsun.stderr.encoding=UTF-8')
            buildArgs.add('-H:+AddAllCharsets')

            // Runtime initialization for dynamic frameworks
            buildArgs.add('--initialize-at-run-time=io.netty.util.internal.logging')
            buildArgs.add('--initialize-at-run-time=io.netty.util.internal.PlatformDependent')
            buildArgs.add('--initialize-at-run-time=io.vertx')
            buildArgs.add('--initialize-at-run-time=com.sun.jna')
            buildArgs.add('--initialize-at-run-time=org.jline')

            buildArgs.add('--initialize-at-run-time=org.fusesource.jansi')
            buildArgs.add('--initialize-at-run-time=org.fusesource.hawtjni')
            buildArgs.add('--initialize-at-run-time=org.jline.terminal.impl.jansi')

            buildArgs.add('--enable-native-access=ALL-UNNAMED')
            buildArgs.add('--add-opens=java.base/java.lang=ALL-UNNAMED')
            buildArgs.add('--add-opens=java.base/io=ALL-UNNAMED')

            // Cross-platform JNA terminal support
            buildArgs.add('--initialize-at-run-time=org.jline.terminal.impl.jna.win.JnaWinSysTerminal')
            buildArgs.add('--initialize-at-run-time=org.jline.terminal.impl.jna.linux.LinuxNativeTerminal')
        }
    }
}
```

`--initialize-at-run-time=org.jline` covers all terminal implementations across platforms (Linux, macOS, Windows). The platform-specific entries are explicit overrides for defense-in-depth.

---

## Environment Variables

```yaml
env:
  GRADLE_OPTS: -Dorg.gradle.jvmargs=-Xmx4g
```

Allocated 4 GB heap for Gradle to handle native-image compilation memory requirements.

---

## Troubleshooting

### Workflow fails to find GraalVM JDK 25

```yaml
- uses: actions/setup-java@v4
  with:
    java-version: 25
    distribution: graalvm
```

If GraalVM for JDK 25 is not yet available on `actions/setup-java`, manually specify a known GraalVM version:

```yaml
java-version: 25
distribution: graalvm
java-package: jdk
```

Or pin to a specific GraalVM release (e.g., `25.0.0`).

### Native compilation runs out of memory

Increase `GRADLE_OPTS`:

```yaml
env:
  GRADLE_OPTS: -Dorg.gradle.jvmargs=-Xmx6g
```

### Windows native binary not found

The native-image plugin outputs `alice.exe` on Windows. Verify path in CI:

```powershell
Test-Path "alice-bootstrap/build/native/nativeCompile/alice.exe"
```

### Artifact download fails in `attach` job

Ensure artifact names match exactly between upload and download steps. The `attach` job uses explicit name references (e.g., `alice-native-linux`), not glob patterns.

---

## Adding a New Platform

1. Add the OS label to the matrix:
   ```yaml
   os: [ubuntu-latest, macos-latest, windows-latest, ...]
   ```
2. Add platform-specific steps for binary compression/verification (use `runner.os` conditionals).
3. Add a download step in the `attach` job.
4. Update this document's asset table.
