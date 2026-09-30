package com.positivity.people.internal.exception;

/**
 * Codes of the {@code 503} a request answers when the {@code ext_*} replica row it needs has not
 * arrived from its owning domain yet (ADR-0044 §2 R3, issue #1994). Each is thrown as a
 * {@link com.positivity.web.common.ReplicationPendingException}, only on the replica-miss branch.
 */
public final class ReplicationPendingCodes {

    /** {@code ext_location}, replicated from pos-location. */
    public static final String LOCATION_REPLICATION_PENDING = "LOCATION_REPLICATION_PENDING";

    /** The user-to-person link, replicated from pos-people-contact's user-link events. */
    public static final String USER_LINK_REPLICATION_PENDING = "USER_LINK_REPLICATION_PENDING";

    private ReplicationPendingCodes() {}
}
