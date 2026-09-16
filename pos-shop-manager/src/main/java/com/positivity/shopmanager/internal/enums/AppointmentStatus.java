package com.positivity.shopmanager.internal.enums;

import java.util.List;

public enum AppointmentStatus {
    SCHEDULED,
    CHECKED_IN,
    WORK_IN_PROGRESS,
    WAITING_FOR_PARTS,
    QUALITY_CHECK,
    READY_FOR_PICKUP,
    COMPLETED,
    CANCELLED,
    INVOICED,
    REOPENED;

    /**
     * The statuses under which an appointment still holds its bay (CAP-326): everything but the
     * three terminal ones. Mirrors the partial predicate of {@code appointment_resource_no_overlap}
     * (V8); the two lists change together. A cancelled appointment is a status flip, not a deleted
     * row, and must not hold its slot.
     */
    public static List<AppointmentStatus> holdingAResource() {
        return List.of(
                SCHEDULED, CHECKED_IN, WORK_IN_PROGRESS, WAITING_FOR_PARTS, QUALITY_CHECK, READY_FOR_PICKUP, REOPENED);
    }
}
