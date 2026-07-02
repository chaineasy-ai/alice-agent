package org.cland.alice.core.agent.prompt;

import java.util.Map;
import java.util.Objects;

/**
 * A prompt definition — a named FreeMarker template stored as {@code .ftl} under {@code
 * ~/.alice/prompts/}.
 *
 * <p>Prompts are a form of <b>procedural memory</b>: they encode how the agent should behave,
 * think, and respond in different contexts. Unlike {@code RuleDef} which holds simple Markdown
 * instructions, a {@code PromptDef} is a full FreeMarker template that may contain variables,
 * conditionals, and loops rendered at runtime.
 *
 * <p>Default prompts are bundled as classpath resources. Users can override any prompt by placing a
 * file with the same name under {@code ~/.alice/prompts/}.
 *
 * @param name The prompt name (e.g. {@code "micro_loop"}, {@code "core_loop"}, {@code "planner"})
 * @param source The path this prompt was loaded from (classpath or filesystem)
 * @param template The raw FreeMarker template content
 * @param metadata Optional metadata (version, description, author, etc.)
 */
public record PromptDef(String name, String source, String template, Map<String, String> metadata) {

  public PromptDef {
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(source, "source must not be null");
    Objects.requireNonNull(template, "template must not be null");
    metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
  }

  public PromptDef(String name, String source, String template) {
    this(name, source, template, Map.of());
  }
}
