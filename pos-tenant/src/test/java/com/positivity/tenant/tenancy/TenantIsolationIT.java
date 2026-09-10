package com.positivity.tenant.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenant.internal.dto.AccountCreateRequest;
import com.positivity.tenant.internal.dto.AccountResponse;
import com.positivity.tenant.internal.dto.TenantCreateRequest;
import com.positivity.tenant.internal.dto.TenantResponse;
import com.positivity.tenant.internal.enums.TenantStatus;
import com.positivity.tenant.internal.repository.TenantRepository;
import com.positivity.tenant.internal.service.AccountService;
import com.positivity.tenant.internal.service.TenantService;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The registry lives in the platform tenant (ADR-0062 §7): the bootstrap rows are visible there and
 * nowhere else, a tenant registered through the service is invisible to any other binding and to an
 * unbound connection, and the tenant status machine runs against the real baseline.
 */
@DisplayName("pos-tenant on Postgres: platform-tenant isolation and bootstrap (ADR-0062 §7)")
class TenantIsolationIT extends PostgresTenancyTestBase {

    @Autowired
    private TenantRepository tenants;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void bootstrapSeedsThePlatformAndAlphaTenantsInThePlatformTenant() {
        asTenant(PlatformTenant.ID, () -> {
            var platform = tenants.findById(PlatformTenant.ID).orElseThrow();
            assertThat(platform.getSlug()).isEqualTo(PlatformTenant.SLUG);
            assertThat(platform.getStatus()).isEqualTo(TenantStatus.ACTIVE);
            assertThat(platform.getTenantId()).isEqualTo(PlatformTenant.ID);
            assertThat(tenants.findBySlug("alpha")).isPresent();
        });
        asTenant(
                TENANT_B,
                () -> assertThat(tenants.findById(PlatformTenant.ID))
                        .as("registry rows are platform rows; another tenant sees nothing")
                        .isEmpty());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant", Integer.class))
                .as("unbound: RLS hides every registry row")
                .isZero();
    }

    @Test
    void aRegisteredTenantIsPlatformDataAndMovesThroughTheStatusMachine() {
        String slug = "it-" + UUID.randomUUID().toString().substring(0, 8);
        TenantResponse created = asTenant(PlatformTenant.ID, () -> {
            AccountResponse account = accountService.create(AccountCreateRequest.builder()
                    .legalName("Isolation " + slug)
                    .homeCountry("US")
                    .homeCurrency("USD")
                    .build());
            return tenantService.create(TenantCreateRequest.builder()
                    .slug(slug)
                    .displayName("Isolation")
                    .accountId(account.getId())
                    .initialAdminEmail("owner@" + slug + ".example")
                    .build());
        });
        assertThat(created.getStatus()).isEqualTo(TenantStatus.PENDING);

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        asTenant(TENANT_B, () -> assertThat(countBySlug(jdbc, slug)).isZero());
        assertThat(countBySlug(jdbc, slug)).as("unbound").isZero();
        asTenant(PlatformTenant.ID, () -> assertThat(countBySlug(jdbc, slug)).isEqualTo(1));

        asTenant(PlatformTenant.ID, () -> {
            tenantService.markProvisioned(created.getId());
            assertThat(tenantService.get(created.getId()).getStatus()).isEqualTo(TenantStatus.ACTIVE);
            assertThat(tenantService.suspend(created.getId()).getStatus()).isEqualTo(TenantStatus.SUSPENDED);
            assertThat(tenantService.reactivate(created.getId()).getStatus()).isEqualTo(TenantStatus.ACTIVE);
            assertThat(tenantService.decommission(created.getId()).getStatus()).isEqualTo(TenantStatus.DECOMMISSIONED);
        });

        // Unbound: the NOT NULL default is NULL and the policy's WITH CHECK refuses the row.
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO account (id, legal_name, status, home_country, home_currency, version,"
                                + " created_at, updated_at) VALUES (?, ?, 'ACTIVE', 'US', 'USD', 0, now(), now())",
                        UUID.randomUUID(),
                        "unbound-" + slug))
                .isInstanceOf(DataAccessException.class);
    }

    private static int countBySlug(JdbcTemplate jdbc, String slug) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM tenant WHERE slug = ?", Integer.class, slug);
        return count == null ? 0 : count;
    }
}
