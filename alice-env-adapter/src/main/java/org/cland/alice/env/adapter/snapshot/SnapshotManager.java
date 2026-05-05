package org.cland.alice.env.adapter.snapshot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * Manages environment snapshots with history and rollback capabilities.
 * <p>
 * Corresponds to the {@code SnapshotManager} class in the design document.
 * Uses a deque to store the history of snapshots, supporting {@link #save(EnvSnapshot)}
 * and {@link #rollback()} operations.
 * <p>
 * The rollback strategy follows the "虚实结合" (virtual + physical) approach:
 * <ul>
 *   <li><b>Lightweight attributes</b> — file listings, env vars, resource URIs
 *       are captured and restored logically</li>
 *   <li><b>Physical rollback (Sandbox only)</b> — Docker/Wasm container
 *       checkpoint/restore (not implemented in this class, coordinated externally)</li>
 *   <li><b>Logical compensation</b> — for irreversible side-effects, the snapshot
 *       records compensation suggestions rather than attempting physical rollback</li>
 * </ul>
 */
public final class SnapshotManager {

    private static final Logger logger = LoggerFactory.getLogger(SnapshotManager.class);

    /** Maximum number of snapshots to retain in history */
    private final int maxHistorySize;

    /** The snapshot history deque (most recent at end) */
    private final Deque<EnvSnapshot> history = new ArrayDeque<>();

    /** The most recent committed snapshot (after verification passed) */
    private EnvSnapshot committedSnapshot;

    /**
     * Create a SnapshotManager with default history size (50).
     */
    public SnapshotManager() {
        this(50);
    }

    /**
     * Create a SnapshotManager with a custom maximum history size.
     *
     * @param maxHistorySize maximum number of snapshots to keep
     */
    public SnapshotManager(int maxHistorySize) {
        if (maxHistorySize < 1) {
            throw new IllegalArgumentException("maxHistorySize must be >= 1, got " + maxHistorySize);
        }
        this.maxHistorySize = maxHistorySize;
    }

    /**
     * Save a snapshot to the history.
     * <p>
     * If the history exceeds {@link #maxHistorySize}, the oldest snapshot is evicted.
     *
     * @param snapshot the snapshot to save
     */
    public void save(EnvSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        history.addLast(snapshot);
        if (history.size() > maxHistorySize) {
            EnvSnapshot evicted = history.removeFirst();
                logger.debug("Evicted oldest snapshot: {}", evicted.snapshotId());
        }
            logger.debug("Snapshot saved: {} (history size: {})", snapshot.snapshotId(), history.size());
    }

    /**
     * Rollback to the previous snapshot (LIFO).
     * <p>
     * Restores the last saved snapshot from history. The snapshot is NOT removed
     * from history on rollback, allowing multiple rollback attempts.
     *
     * @return the snapshot to roll back to, or {@link Optional#empty()} if no snapshots available
     */
    public Optional<EnvSnapshot> rollback() {
        if (history.isEmpty()) {
            logger.warn("Rollback requested but no snapshots available");
            return Optional.empty();
        }

        EnvSnapshot target = history.peekLast();
            logger.info("Rolling back to snapshot: {} (timestamp: {})", target.snapshotId(), target.timestamp());

        if (target.hasIrreversibleEffects()) {
            logger.warn("Snapshot {} has irreversible side-effects: {}. "
                + "Physical rollback is not possible. "
                + "Consider logical compensation actions.",
                target.snapshotId(), target.irreversibleEffects());
        }

        return Optional.of(target);
    }

    /**
     * Rollback to a specific snapshot by ID.
     *
     * @param snapshotId the snapshot ID to roll back to
     * @return the matching snapshot, or empty if not found
     */
    public Optional<EnvSnapshot> rollbackTo(String snapshotId) {
        if (snapshotId == null) {
            return Optional.empty();
        }

        return history.stream()
            .filter(s -> snapshotId.equals(s.snapshotId()))
            .findFirst();
    }

    /**
     * Commit the most recent snapshot as the "good" state.
     * <p>
     * Called when verification passes. The committed snapshot is preserved
     * even if older snapshots are evicted from history.
     */
    public void commit() {
        if (history.isEmpty()) {
            logger.warn("Commit requested but no snapshots available");
            return;
        }
        this.committedSnapshot = history.peekLast();
            logger.debug("Committed snapshot: {}", committedSnapshot.snapshotId());
    }

    /**
     * Get the most recently committed snapshot.
     *
     * @return the committed snapshot, or empty if none
     */
    public Optional<EnvSnapshot> committedSnapshot() {
        return Optional.ofNullable(committedSnapshot);
    }

    /**
     * Get the most recent snapshot from history (without rolling back).
     *
     * @return the latest snapshot, or empty if history is empty
     */
    public Optional<EnvSnapshot> latestSnapshot() {
        if (history.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(history.peekLast());
    }

    /**
     * Get the number of snapshots currently in history.
     */
    public int historySize() {
        return history.size();
    }

    /**
     * Clear all snapshot history and the committed snapshot.
     */
    public void clear() {
        history.clear();
        committedSnapshot = null;
        logger.debug("Snapshot history cleared");
    }

    /**
     * Compare two snapshots and return a summary of differences.
     * <p>
     * Useful for post-action auditing.
     *
     * @param before the snapshot taken before the action
     * @param after  the snapshot taken after the action
     * @return a human-readable diff report
     */
    public static DiffReport diff(EnvSnapshot before, EnvSnapshot after) {
        if (before == null || after == null) {
            return new DiffReport("Cannot diff: one or both snapshots are null");
        }

        var builder = new DiffReport.Builder();

        // Compare resource versions
        for (var entry : after.resourceVersions().entrySet()) {
            String uri = entry.getKey();
            String afterVer = entry.getValue();
            String beforeVer = before.resourceVersions().get(uri);
            if (beforeVer == null) {
                builder.addChange("resource_added", uri, null, afterVer);
            } else if (!beforeVer.equals(afterVer)) {
                builder.addChange("resource_changed", uri, beforeVer, afterVer);
            }
        }
        for (var entry : before.resourceVersions().entrySet()) {
            if (!after.resourceVersions().containsKey(entry.getKey())) {
                builder.addChange("resource_removed", entry.getKey(), entry.getValue(), null);
            }
        }

        // Compare working directory state keys
        for (var entry : after.workingDirectoryState().entrySet()) {
            if (!before.workingDirectoryState().containsKey(entry.getKey())) {
                builder.addChange("file_added", entry.getKey(), null, entry.getValue());
            }
        }
        for (var entry : before.workingDirectoryState().entrySet()) {
            if (!after.workingDirectoryState().containsKey(entry.getKey())) {
                builder.addChange("file_removed", entry.getKey(), entry.getValue(), null);
            }
        }

        // Check new irreversible effects
        for (var effect : after.irreversibleEffects()) {
            if (!before.irreversibleEffects().contains(effect)) {
                builder.addChange("irreversible_effect",
                    effect.action(), null, effect.description());
            }
        }

        return builder.build();
    }

    // ========== Diff Report ==========

    /**
     * A human-readable diff report between two snapshots.
     */
    public static final class DiffReport {
        private final String summary;
        private final java.util.List<DiffEntry> entries;

        DiffReport(String summary) {
            this.summary = summary;
            this.entries = java.util.List.of();
        }

        DiffReport(Builder builder) {
            this.summary = builder.entries.isEmpty()
                ? "No changes detected."
                : builder.entries.size() + " change(s) detected.";
            this.entries = java.util.List.copyOf(builder.entries);
        }

        public String summary()                        { return summary; }
        public java.util.List<DiffEntry> entries()     { return entries; }
        public boolean hasChanges()                    { return !entries.isEmpty(); }

        @Override
        public String toString() {
            var sb = new StringBuilder(summary);
            for (var e : entries) {
                sb.append("\n  [").append(e.type()).append("] ")
                    .append(e.key()).append(": ")
                    .append(e.oldValue()).append(" -> ")
                    .append(e.newValue());
            }
            return sb.toString();
        }

        public record DiffEntry(
            String type, String key, Object oldValue, Object newValue
        ) {}

        static final class Builder {
            private final java.util.List<DiffEntry> entries = new java.util.ArrayList<>();

            Builder addChange(String type, String key, Object oldVal, Object newVal) {
                entries.add(new DiffEntry(type, key, oldVal, newVal));
                return this;
            }

            DiffReport build() {
                return new DiffReport(this);
            }
        }
    }
}
