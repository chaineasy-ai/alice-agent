package org.cland.alice.env.adapter.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * MCP 2.0 Tool definition, representing a callable tool exposed by an MCP server.
 * <p>
 * Corresponds to the {@code Tool} type in the MCP 2.0 specification.
 * Each tool has a name, description, and an input schema describing expected parameters.
 */
public final class Tool {

    private final String name;
    private final String description;
    private final Map<String, Object> inputSchema;

    private Tool(Builder builder) {
        this.name = Objects.requireNonNull(builder.name, "name must not be null");
        this.description = builder.description;
        this.inputSchema = builder.inputSchema != null
            ? Map.copyOf(builder.inputSchema)
            : Map.of();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Tool of(String name, String description) {
        return builder().name(name).description(description).build();
    }

    // ========== Getters ==========

    public String name()                    { return name; }
    public String description()             { return description; }
    public Map<String, Object> inputSchema(){ return inputSchema; }

    @Override
    public String toString() {
        return "Tool{name='" + name + "'}";
    }

    // ========== Builder ==========

    public static final class Builder {
        private String name;
        private String description;
        private Map<String, Object> inputSchema;

        private Builder() {}

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder inputSchema(Map<String, Object> inputSchema) {
            this.inputSchema = inputSchema;
            return this;
        }

        public Tool build() {
            return new Tool(this);
        }
    }
}
