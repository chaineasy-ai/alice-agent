package org.cland.alice.tool.gateway.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记工具方法参数的元信息。
 *
 * <p>用于生成 JSON Schema 时提供参数名、描述和默认值， 使 LLM Planner 能生成正确的 {@code arguments}。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ToolParam {

  /** 参数名称（在 JSON Schema 中的 key） */
  String value();

  /** 参数描述（告诉 LLM 该参数的语义） */
  String description() default "";

  /** 是否必需，默认 true */
  boolean required() default true;
}
