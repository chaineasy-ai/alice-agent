package org.cland.alice.env.adapter.snapshot;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable snapshot of the environment state at a point in time.
 *
 * <p>Corresponds to the {@code EnvSnapshot} class in the design document. Contains:
 *
 * <ul>
 *   <li>Timestamp of when the snapshot was taken
 *   <li>Resource versions (URI → version string) tracked via MCP subscriptions
 *   <li>Working directory state (file list, metadata)
 *   <li>Environment variables
 *   <li>List of irreversible side-effects that occurred
 * </ul>
 */
public final class EnvSnapshot {

  private final String snapshotId;
  private final Instant timestamp;

  /** Map of resource URI → version identifier */
  private final Map<String, String> resourceVersions;

  /** Working directory state: filename → metadata map */
  private final Map<String, Object> workingDirectoryState;

  /** Environment variables captured at snapshot time */
  private final Map<String, String> environmentVariables;

  /** List of irreversible side-effects that cannot be physically rolled back */
  private final List<IrreversibleSideEffect> irreversibleEffects;

  private EnvSnapshot(Builder builder) {
    this.snapshotId = Objects.requireNonNull(builder.snapshotId, "snapshotId must not be null");
    this.timestamp = builder.timestamp != null ? builder.timestamp : Instant.now();
    this.resourceVersions =
        builder.resourceVersions != null ? Map.copyOf(builder.resourceVersions) : Map.of();
    this.workingDirectoryState =
        builder.workingDirectoryState != null
            ? Map.copyOf(builder.workingDirectoryState)
            : Map.of();
    this.environmentVariables =
        builder.environmentVariables != null ? Map.copyOf(builder.environmentVariables) : Map.of();
    this.irreversibleEffects =
        builder.irreversibleEffects != null ? List.copyOf(builder.irreversibleEffects) : List.of();
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Create an empty snapshot with a generated ID. */
  public static EnvSnapshot empty() {
    return builder().snapshotId(java.util.UUID.randomUUID().toString().substring(0, 8)).build();
  }

  // ========== Getters ==========

  public String snapshotId() {
    return snapshotId;
  }

  public Instant timestamp() {
    return timestamp;
  }

  public Map<String, String> resourceVersions() {
    return resourceVersions;
  }

  public Map<String, Object> workingDirectoryState() {
    return workingDirectoryState;
  }

  public Map<String, String> environmentVariables() {
    return environmentVariables;
  }

  public List<IrreversibleSideEffect> irreversibleEffects() {
    return irreversibleEffects;
  }

  /** Check whether this snapshot recorded any irreversible side-effects. */
  public boolean hasIrreversibleEffects() {
    return !irreversibleEffects.isEmpty();
  }

  @Override
  public String toString() {
    return "EnvSnapshot{id='"
        + snapshotId
        + "', timestamp="
        + timestamp
        + ", resources="
        + resourceVersions.size()
        + ", files="
        + workingDirectoryState.size()
        + ", irreversible="
        + irreversibleEffects.size()
        + "}";
  }

  // ========== Irreversible Side Effect ==========

  /**
   * Records a side-effect that cannot be physically rolled back (e.g., sending an email, posting to
   * social media, executing a financial transaction).
   *
   * <p>When such effects are present, rollback can only be achieved through logical compensation
   * actions (e.g., sending a retraction email).
   */
  public record IrreversibleSideEffect(
      String action, String description, String compensationSuggestion, Instant occurredAt) {
    public IrreversibleSideEffect {
      Objects.requireNonNull(action, "action must not be null");
      occurredAt = occurredAt != null ? occurredAt : Instant.now();
    }

    public IrreversibleSideEffect(String action, String description) {
      this(action, description, null, Instant.now());
    }
  }

  // ========== Builder ==========

  public static final class Builder {
    private String snapshotId;
    private Instant timestamp;
    private Map<String, String> resourceVersions;
    private Map<String, Object> workingDirectoryState;
    private Map<String, String> environmentVariables;
    private List<IrreversibleSideEffect> irreversibleEffects;

    private Builder() {}

    public Builder snapshotId(String snapshotId) {
      this.snapshotId = snapshotId;
      return this;
    }

    public Builder timestamp(Instant timestamp) {
      this.timestamp = timestamp;
      return this;
    }

    public Builder resourceVersions(Map<String, String> resourceVersions) {
      this.resourceVersions = resourceVersions;
      return this;
    }

    public Builder workingDirectoryState(Map<String, Object> workingDirectoryState) {
      this.workingDirectoryState = workingDirectoryState;
      return this;
    }

    public Builder environmentVariables(Map<String, String> environmentVariables) {
      this.environmentVariables = environmentVariables;
      return this;
    }

    public Builder irreversibleEffects(List<IrreversibleSideEffect> irreversibleEffects) {
      this.irreversibleEffects = irreversibleEffects;
      return this;
    }

    public Builder addIrreversibleEffect(IrreversibleSideEffect effect) {
      if (this.irreversibleEffects == null) {
        this.irreversibleEffects = new java.util.ArrayList<>();
      }
      this.irreversibleEffects.add(effect);
      return this;
    }

    public EnvSnapshot build() {
      return new EnvSnapshot(this);
    }
  }
}
