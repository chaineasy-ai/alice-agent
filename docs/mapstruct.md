---
title: "MapStruct 对象映射"
summary: "MapStruct 1.6.3 在 Alice Agent 项目中的集成配置与使用指南"
read_when:
  - "添加或修改对象映射逻辑"
  - "为 DTO / VO / Entity 之间的转换编写 Mapper"
  - "理解 MapStruct 在模块化 Gradle 项目中的编译期注解处理配置"
scope:
  - "build.gradle"
  - "alice-model"
  - "alice-tool-gateway"
  - "alice-memory-vault"
status: "active"
updated: "2026-07-12"
---

# MapStruct 对象映射

## 概述

本项目使用 [MapStruct](https://mapstruct.org/) 1.6.3 作为对象映射框架，通过编译期注解处理生成类型安全的映射代码，替代手动的 getter/setter 赋值。

## 配置

### 全局版本管理（根 `build.gradle`）

```groovy
ext {
    mapstructVersion = '1.6.3'
}
```

### 子模块引入（自动应用于所有 Java 模块）

```groovy
subprojects {
    dependencies {
        implementation "org.mapstruct:mapstruct:${mapstructVersion}"
        annotationProcessor "org.mapstruct:mapstruct-processor:${mapstructVersion}"
        testAnnotationProcessor "org.mapstruct:mapstruct-processor:${mapstructVersion}"
    }
}
```

所有子模块（`alice-agent-command`、`alice-model`、`alice-core-agent` 等）均可直接使用 MapStruct。

## 基本用法

### 1. 定义 Mapper 接口

```java
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

@Mapper
public interface ModelMapper {
    ModelMapper INSTANCE = Mappers.getMapper(ModelMapper.class);

    @Mapping(target = "id", ignore = true)
    ModelDto toDto(Model model);

    Model toModel(ModelDto dto);
}
```

### 2. 编译期生成

注解处理器在编译时自动在 `build/generated/sources/annotationProcessor/java/main/` 下生成 `ModelMapperImpl` 实现类，零运行时反射开销。

### 3. 字段映射规则

- **同名同类型字段**：自动映射，无需额外注解
- **字段名不同**：使用 `@Mapping(target = "fieldB", source = "fieldA")`
- **忽略字段**：使用 `@Mapping(target = "field", ignore = true)`
- **表达式转换**：使用 `@Mapping(target = "field", expression = "java(source.method())")`

## 项目中的映射场景

当前项目尚未大量使用传统 Java Bean 映射（多数数据传递通过 `Map<String, Object>` 或 Builder 模式完成）。MapStruct 适用于以下未来场景：

| 场景 | 说明 |
|------|------|
| API DTO ↔ 领域模型 | 如果 `alice-facade-web` 模块需要 REST API 响应 |
| 配置 POJO ↔ 配置对象 | 从文件/环境变量加载的配置映射到强类型配置类 |
| 持久化实体 ↔ 领域对象 | 如果引入数据库持久化 |
| 工具模型转换 | `Tool` ↔ `McpTool`、`Resource` ↔ `ResourceResult` 等 |

## 注意事项

### 模块化（JPMS）兼容性

MapStruct 注解处理器在编译期工作，不影响运行时模块路径。生成的 Mapper 实现类位于目标模块的包内，遵循 `exports` 声明。

### 与 Lombook 共存

如果将来引入 Lombok，需要在 `pom.xml` 或 `build.gradle` 中确保 MapStruct 处理器在 Lombok 之后执行：

```groovy
annotationProcessor 'org.projectlombok:lombok'
annotationProcessor 'org.mapstruct:mapstruct-processor'
```

### 测试

```java
@Test
void testMapping() {
    Model model = new Model("id1", "test");
    ModelDto dto = ModelMapper.INSTANCE.toDto(model);
    assertEquals("test", dto.name());
}
```

## 参考

- [MapStruct 官方文档](https://mapstruct.org/documentation/stable/reference/html/)
- [MapStruct 示例](https://github.com/mapstruct/mapstruct-examples)
- [MapStruct + Gradle 集成](https://mapstruct.org/documentation/installation/#gradle)
