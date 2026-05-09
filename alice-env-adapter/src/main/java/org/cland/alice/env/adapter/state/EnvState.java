package org.cland.alice.env.adapter.state;

/**
 * Environment state machine — models the lifecycle of the environment adapter as defined in the
 * design document.
 *
 * <p>
 *
 * <pre>
 *        [ DISCONNECTED ]
 *               |
 *               v (Connect / Handshake)
 *        [ INITIALIZING ]
 *               |
 *               v (Capability Discovery)
 *    +----[ READY / IDLE ] <----------------+
 *    |          |                           |
 *    |          v (Action Triggered)        |
 *    |   [ CAPTURING SNAPSHOT ]             |
 *    |          |                           |
 *    |          v                           |
 *    |   [ EXECUTING (MCP) ]                |
 *    |          |                           |
 *    |          +---- (Success) ----> [ AUDITING ]
 *    |          |                        |
 *    |          +---- (Error/Abort) -+   | (Pass)
 *    |                               |   v
 *    +---- [ ROLLING BACK ] <--- (Fail) -+---- [ COMMITTED ]
 * </pre>
 */
public enum EnvState {
  /** Not connected to any environment */
  DISCONNECTED,
  /** Connecting / performing MCP handshake */
  INITIALIZING,
  /** Connected and idle, ready to accept actions */
  READY,
  /** Taking a snapshot before executing an action */
  CAPTURING_SNAPSHOT,
  /** Executing an action via MCP protocol */
  EXECUTING,
  /** Auditing the result after execution */
  AUDITING,
  /** Verdict: committed — state is good */
  COMMITTED,
  /** Rolling back to a previous snapshot */
  ROLLING_BACK;

  /** Check if this state allows executing actions. */
  public boolean canExecute() {
    return this == READY;
  }

  /** Check if this state is a terminal (non-transient) state. */
  public boolean isTerminal() {
    return this == DISCONNECTED || this == COMMITTED;
  }

  /** Check if this state represents a transitional (in-progress) state. */
  public boolean isTransitional() {
    return this == INITIALIZING
        || this == CAPTURING_SNAPSHOT
        || this == EXECUTING
        || this == AUDITING
        || this == ROLLING_BACK;
  }
}
