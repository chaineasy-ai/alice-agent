package org.cland.alice.core.agent.prompt;

import java.util.List;
import java.util.Objects;

/**
 * A single rule — a Markdown document stored as {@code .md} under {@code ~/.alice/rules/}.
 *
 * <p>Rules are a form of <b>procedural memory</b>: they encode "how to do things" as human-readable
 * Markdown instructions that are injected into the agent's system prompt. Unlike {@code PromptDef}
 * (a full FreeMarker template), rules are simple text that gets concatenated into a {@code <rules>}
 * section.
 *
 * <p>A rule file contains:
 *
 * <ul>
 *   <li><b>Front matter</b> (YAML) — optional: {@code title}, {@code priority}, {@code applies_to},
 *       {@code status}
 *   <li><b>Body</b> (Markdown) — the actual rule content
 * </ul>
 *
 * <p>Example rule file {@code ~/.alice/rules/coding.md}:
 *
 * <pre>{@code
 * ---
 * title: Coding Standards
 * priority: high
 * applies_to: java,python
 * status: enabled
 * ---
 * ## Java
 * - Use Google Java Format for all Java files.
 * - Prefer records for immutable data carriers.
 *
 * ## Python
 * - Follow PEP 8.
 * }</pre>
 *
 * @param name The rule filename without extension (e.g. {@code "coding"})
 * @param source The file path this rule was loaded from
 * @param title Optional title from front matter
 * @param priority Optional priority (high/medium/low)
 * @param appliesTo Optional comma-separated list of contexts this rule applies to
 * @param status Rule enablement: {@code "enabled"} or {@code "disabled"} (default: {@code
 *     "enabled"})
 * @param content The full rule content (front matter stripped, Markdown body)
 */
public record RuleDef(
    String name,
    String source,
    String title,
    String priority,
    List<String> appliesTo,
    String status,
    String content) {

  public RuleDef {
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(source, "source must not be null");
    Objects.requireNonNull(content, "content must not be null");
    title = title != null ? title : name;
    priority = priority != null ? priority : "medium";
    appliesTo = appliesTo != null ? List.copyOf(appliesTo) : List.of();
    status = status != null ? status : "enabled";
  }

  public RuleDef(String name, String source, String content) {
    this(name, source, name, "medium", List.of(), "enabled", content);
  }
}
