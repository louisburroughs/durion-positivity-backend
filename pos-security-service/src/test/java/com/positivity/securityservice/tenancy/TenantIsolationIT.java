package com.positivity.securityservice.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.repository.ExtTenantRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantContext;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Identity rows are tenant rows (ADR-0062 §6): a user written as tenant A is invisible to tenant B
 * and to an unbound connection, through the repository and through raw SQL; the same username is
 * free per tenant; and the global {@code ext_tenant} replica is readable with nothing bound, which
 * is what lets login resolve a slug before it binds anything.
 */
@DisplayName("pos-security-service on Postgres: tenant isolation and the global replica (ADR-0062)")
class TenantIsolationIT extends PostgresTenancyTestBase {

    @Autowired
    private UserRepository users;

    @Autowired
    private ExtTenantRepository extTenants;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static User user(String username) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("{noop}not-a-real-password");
        return user;
    }

    @Test
    void aUserWrittenAsOneTenantIsInvisibleToAnotherAndToNoTenant() {
        String username = "isolation-" + UUID.randomUUID();
        UUID id = asTenant(TENANT_A, () -> users.saveAndFlush(user(username)).getId());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        asTenant(TENANT_A, () -> {
            assertThat(users.findByUsername(username)).isPresent();
            assertThat(users.findById(id).orElseThrow().getTenantId()).isEqualTo(TENANT_A);
            assertThat(countByUsername(jdbc, username)).isEqualTo(1);
        });
        asTenant(TENANT_B, () -> {
            assertThat(users.findByUsername(username)).isEmpty();
            assertThat(countByUsername(jdbc, username)).isZero();
            assertThat(jdbc.update("UPDATE users SET enabled = false WHERE id = ?", id))
                    .isZero();
        });
        assertThat(countByUsername(jdbc, username)).as("unbound").isZero();
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO users (id, username, password) VALUES (?, ?, 'x')",
                        UUID.randomUUID(),
                        "unbound-" + username))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void theSameUsernameIsFreePerTenant() {
        String username = "shared-" + UUID.randomUUID();
        asTenant(TENANT_A, () -> users.saveAndFlush(user(username)));
        asTenant(TENANT_B, () -> users.saveAndFlush(user(username)));

        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        assertThat(owner.queryForObject("SELECT count(*) FROM users WHERE username = ?", Integer.class, username))
                .isEqualTo(2);
    }

    @Test
    void theTenantReplicaIsGlobalAndBootstrapped() {
        assertThat(extTenants.findBySlug(PlatformTenant.SLUG))
                .as("readable with nothing bound: login resolves the slug first")
                .isPresent()
                .get()
                .satisfies(tenant -> assertThat(tenant.getTenantId()).isEqualTo(PlatformTenant.ID));
        assertThat(extTenants.findBySlug("alpha")).isPresent();
        asTenant(
                TENANT_B,
                () -> assertThat(extTenants.findBySlug("alpha"))
                        .as("global: every tenant sees the same projection")
                        .isPresent());
    }

    private static int countByUsername(JdbcTemplate jdbc, String username) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM users WHERE username = ?", Integer.class, username);
        return count == null ? 0 : count;
    }
}
