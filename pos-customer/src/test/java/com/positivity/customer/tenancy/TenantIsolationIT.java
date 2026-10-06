package com.positivity.customer.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.entity.PartyTag;
import com.positivity.customer.internal.enums.HouseAccountKind;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.PartyTagRepository;
import com.positivity.customer.internal.service.HouseAccountProvisioningService;
import com.positivity.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the isolation, not just the mapping (plan R-B7): a row written as tenant A is invisible to
 * tenant B through the repository (Hibernate's {@code @TenantId} filter) and through a raw {@code
 * JdbcTemplate} on the same pool (row-level security alone), and an unbound connection can neither
 * read nor write a scoped table.
 */
@DisplayName("Tenant isolation on Postgres (ADR-0062, pos-customer)")
class TenantIsolationIT extends PostgresTenancyTestBase {

    @Autowired
    private PartyTagRepository tags;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private CommercialPartyRepository parties;

    @Autowired
    private HouseAccountProvisioningService houseAccounts;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void aRowWrittenAsOneTenantIsInvisibleToAnotherAndToNoTenant() {
        String name = "isolation-" + UUID.randomUUID();
        UUID id = asTenant(
                TENANT_A,
                () -> tags.saveAndFlush(PartyTag.builder()
                                .name(name)
                                .category("isolation")
                                .build())
                        .getTagId());

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        asTenant(TENANT_A, () -> {
            assertThat(tags.findById(id))
                    .as("owner reads through the repository")
                    .isPresent();
            assertThat(tags.findById(id).orElseThrow().getTenantId()).isEqualTo(TENANT_A);
            assertThat(countByName(jdbc, name))
                    .as("owner reads through raw SQL")
                    .isEqualTo(1);
        });

        asTenant(TENANT_B, () -> {
            assertThat(tags.findById(id))
                    .as("Hibernate filter hides the other tenant's row")
                    .isEmpty();
            assertThat(countByName(jdbc, name))
                    .as("RLS hides it from raw SQL too")
                    .isZero();
            assertThat(jdbc.update("UPDATE party_tag SET category = 'stolen' WHERE tag_id = ?", id))
                    .as("RLS makes the row unreachable for UPDATE")
                    .isZero();
        });

        // Unbound: the pool RESETs app.current_tenant, so pos_app sees an empty table and cannot insert.
        assertThat(countByName(jdbc, name)).isZero();
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO party_tag (tag_id, name, active, created_at, updated_at) VALUES (?, ?, true, now(), now())",
                        UUID.randomUUID(),
                        "unbound-" + UUID.randomUUID()))
                .as("no tenant bound: the NOT NULL default is NULL and the policy's WITH CHECK refuses the row")
                .isInstanceOf(DataAccessException.class);

        asTenant(
                TENANT_A,
                () -> assertThat(tags.findById(id).orElseThrow().getCategory())
                        .as("tenant B's UPDATE touched nothing")
                        .isEqualTo("isolation"));
    }

    /**
     * Every tenant holds its own CASH house account (CAP:550 S7, #2505) under the same customer
     * number, and neither can see, guard against or count the other's: the account, its partial
     * unique index and the guard's lookup are all scoped to the bound tenant.
     */
    @Test
    void eachTenantsHouseAccountIsInvisibleToTheOther() {
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<UUID> created = new ArrayList<>();
        try {
            UUID houseOfA = asTenant(TENANT_A, () -> provisionOrFind(created));
            UUID houseOfB = asTenant(TENANT_B, () -> provisionOrFind(created));
            assertThat(houseOfA).isNotEqualTo(houseOfB);

            asTenant(TENANT_A, () -> {
                assertThat(parties.findByHouseAccount(HouseAccountKind.CASH_SALE))
                        .map(CommercialParty::getPartyId)
                        .as("tenant A resolves its own house account")
                        .contains(houseOfA);
                assertThat(parties.existsByPartyIdAndHouseAccountIsNotNull(houseOfA))
                        .isTrue();
                assertThat(parties.findById(houseOfB))
                        .as("Hibernate filter hides tenant B's house account")
                        .isEmpty();
                assertThat(parties.existsByPartyIdAndHouseAccountIsNotNull(houseOfB))
                        .as("the guard's lookup never sees another tenant's house account")
                        .isFalse();
                assertThat(countHouseAccounts(jdbc))
                        .as("RLS shows tenant A exactly its own")
                        .isEqualTo(1);
                assertThat(jdbc.update(
                                "UPDATE commercial_party SET legal_name = 'stolen' WHERE customer_id = ?", houseOfB))
                        .as("RLS makes tenant B's house account unreachable for UPDATE")
                        .isZero();
            });
            asTenant(TENANT_B, () -> {
                assertThat(parties.findByHouseAccount(HouseAccountKind.CASH_SALE))
                        .map(CommercialParty::getPartyId)
                        .contains(houseOfB);
                assertThat(parties.findById(houseOfA)).isEmpty();
                assertThat(countHouseAccounts(jdbc)).isEqualTo(1);
            });
            // Unbound: no tenant, no house account.
            assertThat(countHouseAccounts(jdbc)).isZero();
        } finally {
            created.forEach(partyId -> owner.update("DELETE FROM commercial_party WHERE customer_id = ?", partyId));
        }
    }

    /** Provisions the bound tenant's house account, remembering it for cleanup if this call created it. */
    private UUID provisionOrFind(List<UUID> created) {
        houseAccounts.createCashAccountIfMissing().ifPresent(created::add);
        return parties.findByHouseAccount(HouseAccountKind.CASH_SALE)
                .map(CommercialParty::getPartyId)
                .orElseThrow();
    }

    private static int countHouseAccounts(JdbcTemplate jdbc) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM commercial_party WHERE house_account IS NOT NULL", Integer.class);
        return count == null ? 0 : count;
    }

    private static int countByName(JdbcTemplate jdbc, String name) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM party_tag WHERE name = ?", Integer.class, name);
        return count == null ? 0 : count;
    }
}
