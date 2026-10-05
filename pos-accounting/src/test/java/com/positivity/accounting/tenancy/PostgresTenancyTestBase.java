package com.positivity.accounting.tenancy;

import com.positivity.accounting.AccountingPostgresContainer;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.service.AccountingTemplate;
import com.positivity.accounting.internal.service.AccountingTemplateReader;
import com.positivity.accounting.internal.service.AccountingTenantProvisioner;
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
}
