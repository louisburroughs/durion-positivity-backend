package com.positivity.accounting.tenancy;

import com.positivity.accounting.AccountingPostgresContainer;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.service.AccountingTemplate;
import com.positivity.accounting.internal.service.AccountingTemplateReader;
import com.positivity.accounting.internal.service.AccountingTenantProvisioner;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.testing.TenantTestSupport;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The tenancy tests' view of the shared {@link AccountingPostgresContainer}: the container's
 * superuser owns the schema and runs Flyway; the application pool connects as the shared non-owner
 * {@code pos_app} role (ADR-0062 §3, layer 2). The runtime is strict ({@code pg} profile): nothing
 * binds a tenant unless the test does.
 *
 * <p>Requires Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("pg")
public abstract class PostgresTenancyTestBase {

    static final String APP_ROLE = AccountingPostgresContainer.APP_ROLE;

    /** The container superuser: owns every table, runs Flyway, and bypasses RLS like the alpha owner does. */
    static DataSource ownerDataSource() {
        return AccountingPostgresContainer.ownerDataSource();
    }

    /**
     * Puts {@code vendorId} in the copy of the pos-supplier vendor master of the tenant bound to this thread, active,
     * at remit-to version 0, unless it is there already (CAP:550 S24): a goods-receipt bill and an AP payment must name
     * a vendor in the copy. Written by the owner, as the vendor-fact consumer's row would be.
     *
     * @return {@code vendorId}, so a call can stand where the id is used
     */
    static UUID copiedVendor(UUID vendorId) {
        UUID tenant = TenantContext.current()
                .orElseThrow(() -> new IllegalStateException("copiedVendor needs a bound tenant"));
        new org.springframework.jdbc.core.JdbcTemplate(ownerDataSource())
                .update(
                        "INSERT INTO ext_supplier_vendor (tenant_id, vendor_id, vendor_number, display_name, status,"
                                + " remit_to_version, tax_registrations, created_by, aggregate_version, updated_at)"
                                + " VALUES (?, ?, ?, 'Acme Parts Co', 'ACTIVE', 0, '[]'::jsonb, 'buyer.ben', 1, now())"
                                + " ON CONFLICT (vendor_id) DO NOTHING",
                        tenant,
                        vendorId,
                        "V-" + vendorId.toString().substring(0, 8));
        return vendorId;
    }

    /** Runs once the container is up and before the Spring context starts, so the role exists for Flyway and the pool. */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        AccountingPostgresContainer.registerDataSourceProperties(registry);
    }

    @Autowired
    private AccountingTemplateReader templateReader;

    @Autowired
    private AccountingTenantProvisioner tenantProvisioner;

    @Autowired
    private GLAccountRepository provisionedAccounts;

    /**
     * Provisions the alpha default tenant before every test, the way the startup sweep does on a real
     * start (#2526). The repeatable seed writes the accounting template into the platform tenant and no
     * tenant's rows, and this strict profile lists no registry tenant, so the sweep has visited nobody:
     * a test that needs a tenant's chart, mappings or statement lines gets them here, through the
     * provisioner, not from a seed. After the first call the applier stops at the fingerprint.
     */
    @BeforeEach
    void provisionDefaultTenant() {
        provisionAccounting(TenantTestSupport.TENANT_A);
    }

    /** Gives {@code tenantId} its accounting defaults from the template. Idempotent. */
    protected void provisionAccounting(UUID tenantId) {
        AccountingTemplate template = templateReader.snapshot();
        TenantTestSupport.asTenant(tenantId, () -> {
            tenantProvisioner.provision(tenantId, null, template);
        });
    }

    /** The id of {@code tenantId}'s account {@code accountCode}: accounts are found by code, never by a seeded id. */
    protected UUID provisionedAccountId(UUID tenantId, String accountCode) {
        provisionAccounting(tenantId);
        return TenantTestSupport.asTenant(
                tenantId,
                () -> provisionedAccounts
                        .findByAccountCode(accountCode)
                        .orElseThrow()
                        .getGlAccountId());
    }

    /**
     * Gives {@code tenantId} the accounting time zone tenant provisioning seeds (#2558), without provisioning
     * anything else: a tenant made up by a test has no row, and every dated read then fails closed. Idempotent.
     */
    protected static void seedAccountingTimeZone(UUID tenantId) {
        new org.springframework.jdbc.core.JdbcTemplate(ownerDataSource())
                .update(
                        "INSERT INTO accounting_configuration (tenant_id, config_id, config_key, config_value,"
                                + " created_at, created_by, modified_at, modified_by) VALUES (?, ?,"
                                + " 'ACCOUNTING_TIME_ZONE', 'UTC', TIMESTAMPTZ '2026-10-06 00:00:00+00', 'test',"
                                + " TIMESTAMPTZ '2026-10-06 00:00:00+00', 'test')"
                                + " ON CONFLICT (tenant_id, config_key) DO NOTHING",
                        tenantId,
                        com.positivity.shared.id.UUIDv7Generator.generate());
    }

    /** A new tenant id with the provisioning seed of its accounting time zone. */
    protected static UUID tenantWithZone() {
        UUID tenantId = com.positivity.shared.id.UUIDv7Generator.generate();
        seedAccountingTimeZone(tenantId);
        return tenantId;
    }
}
