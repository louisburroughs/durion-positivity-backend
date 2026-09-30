package com.positivity.inventory.internal.exception;

/**
 * Codes of the {@code 503} a request answers when the {@code ext_*} replica row it needs has not
 * arrived from its owning domain yet (ADR-0044 §2 R3, issue #1994). Each is thrown as a
 * {@link com.positivity.web.common.ReplicationPendingException}, only on the replica-miss branch:
 * a row that is present but in the wrong state keeps the status that describes that state.
 */
public final class ReplicationPendingCodes {

    /** {@code ext_purchase_order} / {@code ext_purchase_order_line}, replicated from pos-order. */
    public static final String PURCHASE_ORDER_REPLICATION_PENDING = "PURCHASE_ORDER_REPLICATION_PENDING";

    /** {@code ext_workorder} / {@code ext_workorder_part}, replicated from pos-workorder. */
    public static final String WORKORDER_REPLICATION_PENDING = "WORKORDER_REPLICATION_PENDING";

    /** {@code ext_storage_location}, replicated from pos-location. */
    public static final String STORAGE_LOCATION_REPLICATION_PENDING = "STORAGE_LOCATION_REPLICATION_PENDING";

    private ReplicationPendingCodes() {}
}
