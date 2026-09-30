package com.positivity.workorder.internal.exception;

/**
 * Codes of the {@code 503} a request answers when the {@code ext_*} replica row it needs has not
 * arrived from its owning domain yet (ADR-0044 §2 R3, issue #1994). Each is thrown as a
 * {@link com.positivity.web.common.ReplicationPendingException}, only on the replica-miss branch.
 */
public final class ReplicationPendingCodes {

    /** {@code ext_people_contact_person}, replicated from pos-people-contact. */
    public static final String TECHNICIAN_REPLICATION_PENDING = "TECHNICIAN_REPLICATION_PENDING";

    /** {@code ext_bay} / {@code ext_mobile_unit}, replicated from pos-location. */
    public static final String LOCATION_REPLICATION_PENDING = "LOCATION_REPLICATION_PENDING";

    /** {@code ext_pick_list} / {@code ext_pick_task}, replicated from pos-inventory's pick events. */
    public static final String PICK_LIST_REPLICATION_PENDING = "PICK_LIST_REPLICATION_PENDING";

    private ReplicationPendingCodes() {}
}
