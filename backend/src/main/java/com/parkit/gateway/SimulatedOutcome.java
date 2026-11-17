package com.parkit.gateway;

/** The outcome a simulated gateway call should produce (FR-39). */
public enum SimulatedOutcome {
    SUCCESS, FAILURE;

    /** Null (field omitted by the client) means SUCCESS. */
    public static SimulatedOutcome orDefault(SimulatedOutcome outcome) {
        return outcome == null ? SUCCESS : outcome;
    }
}
