package com.positivity.tenancy;

/** Wire names for the tenant, shared by the gateway, the services, and the Kafka producers. */
public final class TenantHeaders {

    /** HTTP header the gateway injects from the JWT {@code tid} claim; stripped from inbound traffic. */
    public static final String HTTP_TENANT_ID = "X-Tenant-Id";

    /** HTTP header the gateway derives from the {@code Host} header for the login route only. */
    public static final String HTTP_TENANT_SLUG = "X-Tenant-Slug";

    /** Kafka record header carrying the producing tenant's id (ADR-0062 §3, async path). */
    public static final String KAFKA_TENANT_ID = "tenantId";

    /** Postgres session setting the row-level-security policies read. */
    public static final String PG_SETTING = "app.current_tenant";

    private TenantHeaders() {}
}
