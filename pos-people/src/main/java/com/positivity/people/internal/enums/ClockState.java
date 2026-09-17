package com.positivity.people.internal.enums;

/**
 * A person's current work-session (clock) state, derived on read from the open {@code
 * WorkSession} and its open {@code WorkSessionBreak} (issue #2061, BR3). Never stored.
 */
public enum ClockState {

    /** An open work session and no open break: dispatchable. */
    CLOCKED_IN,

    /**
     * An open work session with an open break: still clocked in, not dispatchable. A distinct
     * state rather than a flag, because the dispatch board renders a break lane.
     */
    ON_BREAK,

    /** No open work session. "Not clocked in" is an answer, not an error. */
    CLOCKED_OUT
}
