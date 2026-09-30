package com.positivity.shopmanager.internal.exception;

/**
 * Codes of the {@code 503} a request answers when the {@code ext_*} replica row it needs has not
 * arrived from its owning domain yet (ADR-0044 §2 R3, issue #1994). Each is thrown as a
 * {@link com.positivity.web.common.ReplicationPendingException}, only on the replica-miss branch.
 */
public final class ReplicationPendingCodes {

    /** {@code ext_customer_party} / {@code ext_vehicle}, replicated from pos-customer. */
    public static final String CRM_REPLICATION_PENDING = "CRM_REPLICATION_PENDING";

    /** {@code ext_location} / {@code ext_bay} / {@code ext_mobile_unit}, replicated from pos-location. */
    public static final String LOCATION_REPLICATION_PENDING = "LOCATION_REPLICATION_PENDING";

    /** {@code ext_catalog_service}, replicated from pos-catalog. */
    public static final String CATALOG_REPLICATION_PENDING = "CATALOG_REPLICATION_PENDING";

    /** The mechanic projection built from staffing events. */
    public static final String MECHANIC_REPLICATION_PENDING = "MECHANIC_REPLICATION_PENDING";

    private ReplicationPendingCodes() {}
}
