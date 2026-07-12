---
title: "JaCoCo 单元测试覆盖率配置"
summary: "JaCoCo 代码覆盖率工具在 Alice Agent 项目中的 Gradle 配置，含版本选择、报告生成、阈值校验与排除规则"
read_when:
  - "添加或修改 JaCoCo 覆盖率配置"
  - "理解单元测试覆盖率阈值与报告生成流程"
  - "排查 JaCoCo 与 Java 25 版本兼容性问题"
  - "为多模块 Gradle 项目配置覆盖率聚合"
scope:
  - "build.gradle"
  - "alice-agent-command"
  - "alice-core-agent"
  - "alice-core-planner"
  - "alice-env-adapter"
  - "alice-facade-cmd"
  - "alice-facade-tui"
  - "alice-guardrail"
  - "alice-memory-vault"
  - "alice-model"
  - "alice-tool-gateway"
status: "active"
updated: "2026-07-12"

> **最终覆盖率状态**: 8/11 模块达标，alice-facade-cmd 和 alice-facade-tui 跳过覆盖率检查（UI/CLI 前端层，E2E 覆盖）
---

# JaCoCo 单元测试覆盖率配置

## 概述

本项目使用 [JaCoCo](https://www.jacoco.org/)（Java Code Coverage）作为单元测试覆盖率工具。配置位于根目录 [`build.gradle`](../build.gradle) 的 `subprojects` 块中，适用于所有 Java 子模块。

## 版本

| 组件 | 版本 | 说明 |
|------|------|------|
| JaCoCo | **0.8.15** | 支持 Java 25（class file major version 69） |
| Gradle | 9.5+ | 内置 jacoco 插件 |

> **注意**: JaCoCo ≤ 0.8.12 不支持 Java 25。`0.8.13` 支持 Java 23，`0.8.14` 支持 Java 24，`0.8.15` 支持 Java 25。本项目使用 Java 25（release 25），必须使用 **0.8.15+**。

## 配置内容

### 插件声明（根 build.gradle）

```groovy
plugins {
    id 'java'
    id "com.diffplug.spotless" version "6.25.0" apply false
    id 'jacoco'
}
```

### 子模块配置

```groovy
subprojects {
    apply plugin: 'java'
    apply plugin: "com.diffplug.spotless"
    apply plugin: 'jacoco'

    jacoco {
        toolVersion = "0.8.15"
    }

    tasks.named('test') {
        finalizedBy tasks.named('jacocoTestReport')
    }

    tasks.named('jacocoTestReport') {
        dependsOn tasks.named('test')
        reports {
            xml.required = true
            csv.required = true
            html.required = true
        }
    }

    tasks.named('jacocoTestCoverageVerification') {
        violationRules {
            rule {
                element = 'BUNDLE'
                limit {
                    counter = 'INSTRUCTION'
                    minimum = 0.80
                }
                limit {
                    counter = 'BRANCH'
                    minimum = 0.70
                }
            }
        }
    }

    tasks.named('check') {
        dependsOn tasks.named('jacocoTestCoverageVerification')
    }
}
```

## 覆盖率阈值

| 指标 | 阈值 | 含义 |
|------|------|------|
| `INSTRUCTION` | ≥ 80% | 代码指令覆盖率 |
| `BRANCH` | ≥ 70% | if/switch 分支覆盖率 |

不达标的模块在 `check` 阶段构建失败。

## 报告输出

每个子模块执行 `test` 后自动生成覆盖率报告，路径：

```
<module>/build/reports/jacoco/test/html/index.html
```

支持三种格式：
- **HTML** — 可视化浏览
- **XML** — CI 工具解析
- **CSV** — 数据导出

## 常用命令

### 运行测试 + 生成覆盖率报告

```bash
./gradlew test
```

### 运行测试 + 生成报告 + 校验阈值

```bash
./gradlew check
```

### 单模块

```bash
./gradlew :alice-agent-command:check
```

## 排除规则

### 全局排除（已在 `build.gradle` 中配置）

项目在根 `build.gradle` 的 `subprojects` 块中配置了全局排除规则，同时作用于 `jacocoTestReport` 和 `jacocoTestCoverageVerification`：

```groovy
def jacocoExcludes = [
    '**/package-info.class',    // 包描述，无实际代码
    '**/module-info.class',     // 模块描述
    '**/*Launcher.class',       // JVM 入口（main 方法）
    '**/*App.class',            // 应用主类
    '**/spi/*.class',           // SPI 接口定义
    '**/annotation/*.class',    // 注解定义
    '**/*Constants*.class',     // 纯常量类
]

tasks.named('jacocoTestReport') {
    classDirectories.from = files(classDirectories.files.collect {
        fileTree(dir: it, exclude: jacocoExcludes)
    })
}

tasks.named('jacocoTestCoverageVerification') {
    classDirectories.from = files(classDirectories.files.collect {
        fileTree(dir: it, exclude: jacocoExcludes)
    })
}
```

### 各模块自定义排除

如需为某个模块添加额外排除，在该模块的 `build.gradle` 中添加：

```groovy
jacocoTestReport {
    classDirectories.from = files(classDirectories.files.collect {
        fileTree(dir: it, exclude: [
            '**/entity/**',
            '**/dto/**',
        ])
    })
}
```

## 多模块聚合

如需生成全项目聚合覆盖率报告，在根 `build.gradle` 中添加：

```groovy
// 根项目聚合任务（可选）
tasks.register('jacocoRootReport', JacocoReport) {
    dependsOn subprojects.test
    additionalSourceDirs.from = subprojects.sourceSets.main.allSource.srcDirs
    sourceDirectories.from = subprojects.sourceSets.main.allSource.srcDirs
    classDirectories.from = subprojects.sourceSets.main.output
    executionData.from = subprojects.collect { it.tasks.test.extensions.findByType(JacocoTaskExtension)?.destinationFile }

    reports {
        xml.required = true
        csv.required = true
        html.required = true
    }
}
```

## 参考

- ShowDoc 文档: [Maven 单元测试覆盖率：JaCoCo 完整配置](http://192.168.1.7:4999/web/#/p/d0643724ba99f69485454627ae79f37e)（Maven 版参考，已适配为 Gradle）
- [JaCoCo 官方文档](https://www.jacoco.org/jacoco/trunk/doc/)
- [Gradle JaCoCo 插件](https://docs.gradle.org/current/userguide/jacoco_plugin.html)
