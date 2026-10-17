package com.positivity.accounting.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.internal.service.ReadingAllowanceReplicaWriter;
import com.positivity.accounting.internal.service.TenantEventsListener;
import com.positivity.accounting.internal.service.TenantReadingAllowanceReader;
import com.positivity.accounting.internal.service.TenantReadingAllowanceReader.ReadingAllowance;
import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The reading-allowance replica on the real schema (#2619; CAP:550 S45, AC 7 and 8): the pool as {@code pos_app} with
 * row-level security in force, the listener driven by hand (Kafka rails are off in the {@code pg} profile), each
 * tenant seeing only its own row.
 *
 * <p>Requires Docker.
 */
@DisplayName("Reading-allowance replica (#2619, real Postgres)")
class ReadingAllowanceReplicaPostgresIT extends PostgresTenancyTestBase {

    @Autowired
    private TenantReadingAllowanceReader reader;

    @Autowired
    private ReadingAllowanceReplicaWriter writer;

    @Autowired
    private com.positivity.accounting.internal.repository.ProcessedEventRepository processedEvents;

    @Autowired
    private com.positivity.accounting.internal.service.AccountingTemplateReader templateReader;

    @Autowired
    private com.positivity.accounting.internal.service.AccountingTenantProvisioner provisioner;

    @Autowired
    private com.positivity.accounting.internal.service.TenantLocaleReplicaWriter localeWriter;

    @Autowired
    private javax.sql.DataSource appDataSource;

    private final List<String> eventIds = new java.util.ArrayList<>();

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        owner.update("DELETE FROM bill_intake_reading_allowance WHERE tenant_id IN (?, ?)", TENANT_A, TENANT_B);
        eventIds.forEach(id -> owner.update("DELETE FROM processed_events WHERE event_id = ?", id));
        eventIds.clear();
    }

    @Test
    @DisplayName("AC 7: a tenant with no row has no allowance (the meter reads nothing); the fact makes it present")
    void noRowIsEmptyUntilTheFactArrives() {
        assertThat(reader.allowanceOf(TENANT_A)).isEmpty();

        consume(TENANT_A, 1, "STANDARD", "20.00");

        assertThat(reader.allowanceOf(TENANT_A)).hasValueSatisfying(allowance -> {
            assertThat(allowance.tierCode()).isEqualTo("STANDARD");
            assertThat(allowance.amount()).isEqualByComparingTo("20.00");
            assertThat(allowance.currencyCode()).isEqualTo("USD");
        });
    }

    @Test
    @DisplayName("AC 8: an older fact replayed after a newer one leaves the stored allowance unchanged")
    void olderFactAfterNewerIsIgnored() {
        consume(TENANT_A, 5, "PRO", "60.00");
        consume(TENANT_A, 2, "STANDARD", "20.00");

        ReadingAllowance held = reader.allowanceOf(TENANT_A).orElseThrow();
        assertThat(held.tierCode()).isEqualTo("PRO");
        assertThat(held.amount()).isEqualByComparingTo("60.00");

        consume(TENANT_A, 5, "PRO", "65.00");
        assertThat(reader.allowanceOf(TENANT_A).orElseThrow().amount())
                .as("an equal version applies")
                .isEqualByComparingTo("65.00");
    }

    @Test
    @DisplayName("two tenants each see only their own row, under RLS")
    void tenantsAreIsolated() {
        consume(TENANT_A, 1, "STANDARD", "19.99");
        consume(TENANT_B, 1, "PRO", "60.00");

        assertThat(reader.allowanceOf(TENANT_A).orElseThrow().amount()).isEqualByComparingTo("19.99");
        assertThat(reader.allowanceOf(TENANT_B).orElseThrow().amount()).isEqualByComparingTo("60.00");
        // The reading is by bound tenant: a statement bound to A cannot see B's row.
        assertThat(asTenant(
                        TENANT_A,
                        () -> new JdbcTemplate(appDataSource)
                                .queryForList("SELECT tenant_id FROM bill_intake_reading_allowance", UUID.class)))
                .containsExactly(TENANT_A);
        assertThat(new JdbcTemplate(ownerDataSource())
                        .queryForObject(
                                "SELECT count(*) FROM bill_intake_reading_allowance WHERE tenant_id IN (?, ?)",
                                Integer.class,
                                TENANT_A,
                                TENANT_B))
                .isEqualTo(2);
        assertThat(asTenant(PlatformTenant.ID, () -> reader.allowanceOf(PlatformTenant.ID)))
                .isEqualTo(Optional.empty());
    }

    private void consume(UUID tenant, long version, String tier, String amount) {
        String eventId = UUID.randomUUID().toString();
        eventIds.add(eventId);
        TenantEventsListener listener = new TenantEventsListener(
                new ObjectMapper(), processedEvents, templateReader, provisioner, localeWriter, writer);
        asTenant(
                PlatformTenant.ID, () -> listener.onTenantEvent("""
                        {"eventId":"%s","eventType":"tenant.reading-allowance.changed","aggregateVersion":%d,
                         "payload":{"tenantId":"%s","tierCode":"%s",
                                    "monthlyAllowance":{"amount":%s,"currencyCode":"USD"},
                                    "effectiveAt":"2026-10-17T00:00:00Z"}}
                        """.formatted(eventId, version, tenant, tier, amount)));
    }
}
