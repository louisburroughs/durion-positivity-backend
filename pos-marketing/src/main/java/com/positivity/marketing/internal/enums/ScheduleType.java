package com.positivity.marketing.internal.enums;

/** When a scheduled campaign dispatches (Story #1146). */
public enum ScheduleType {
    /** Dispatch as soon as the send command is accepted. */
    IMMEDIATE,
    /** Hold until {@code scheduledAt}. */
    SCHEDULED
}
