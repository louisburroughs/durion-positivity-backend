package com.positivity.accounting.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * {@code V3__backfill_journal_entry_source_event_type.sql} (#2434) against rows written before
 * every entry named its source. Flyway already ran it on the empty schema; the test seeds pre-#2434
 * rows as the owner (the superuser bypasses RLS, as the alpha owner does) and runs the script
 * again, which is idempotent.
 *
 * <p>Requires Docker.
 */
@DisplayName("Journal entry source backfill (#2434)")
class JournalEntrySourceBackfillPostgresIT extends PostgresTenancyTestBase {

    private static final String BACKFILL = "db/migration/V3__backfill_journal_entry_source_event_type.sql";

    private final DataSource owner = ownerDataSource();
    private final JdbcTemplate jdbc = new JdbcTemplate(owner);

    private final UUID revenue = UUID.randomUUID();
    private final UUID revenueKey = UUID.randomUUID();
    private final UUID revenueReversal = UUID.randomUUID();
    private final UUID revenueReversalKey = UUID.randomUUID();
    private final UUID manual = UUID.randomUUID();
    private final UUID manualReversal = UUID.randomUUID();
    private final UUID otherAutomated = UUID.randomUUID();
    private final UUID otherAutomatedKey = UUID.randomUUID();
    private final UUID tenantBEntry = UUID.randomUUID();
    private final UUID tenantBKey = UUID.randomUUID();
    private final UUID alreadySourced = UUID.randomUUID();
    private final UUID alreadySourcedKey = UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM invoice_gl_posting WHERE journal_entry_id IN (?, ?)", revenue, tenantBEntry);
        jdbc.update("DELETE FROM journal_entry WHERE journal_entry_id IN (?, ?)", manualReversal, revenueReversal);
        jdbc.update(
                "DELETE FROM journal_entry WHERE journal_entry_id IN (?, ?, ?, ?, ?)",
                revenue,
                manual,
                otherAutomated,
                tenantBEntry,
                alreadySourced);
    }

    @Test
    @DisplayName(
            "Invoice entries take their type from invoice_gl_posting, manual entries MANUAL, reversals their original's")
    void backfillsDerivableSources() throws Exception {
        insertEntry(TENANT_A, revenue, revenueKey, null, null);
        insertEntry(TENANT_A, revenueReversal, revenueReversalKey, null, null);
        insertPosting(TENANT_A, revenue, revenueReversal);
        insertEntry(TENANT_A, manual, null, null, null);
        insertEntry(TENANT_A, manualReversal, null, null, manual);
        insertEntry(TENANT_A, otherAutomated, otherAutomatedKey, null, null);
        insertEntry(TENANT_A, alreadySourced, alreadySourcedKey, "SETTLEMENT", null);
        // Tenant B's own posting classifies tenant B's entry (the backfill is not tenant-bound).
        insertEntry(TENANT_B, tenantBEntry, tenantBKey, null, null);
        insertPosting(TENANT_B, tenantBEntry, null);

        runBackfill();

        assertThat(source(revenue)).containsExactly("INVOICE_REVENUE", revenueKey.toString());
        assertThat(source(revenueReversal)).containsExactly("INVOICE_REVENUE_REVERSAL", revenueReversalKey.toString());
        assertThat(source(manual)).containsExactly("MANUAL", manual.toString());
        assertThat(source(manualReversal)).containsExactly("MANUAL", manual.toString());
        assertThat(source(otherAutomated))
                .as("an automated entry with no derivable category keeps a null type")
                .containsExactly(null, otherAutomatedKey.toString());
        assertThat(source(alreadySourced)).containsExactly("SETTLEMENT", alreadySourcedKey.toString());
        assertThat(source(tenantBEntry)).containsExactly("INVOICE_REVENUE", tenantBKey.toString());
    }

    @Test
    @DisplayName("A posting in another tenant never classifies an entry")
    void ignoresCrossTenantPostings() throws Exception {
        insertEntry(TENANT_A, revenue, revenueKey, null, null);
        // Same journal_entry_id, wrong tenant.
        jdbc.update(
                "INSERT INTO invoice_gl_posting (tenant_id, invoice_gl_posting_id, invoice_id, finalized_at,"
                        + " journal_entry_id, posted_at, revenue_amount, tax_amount, created_at, updated_at)"
                        + " VALUES (?, ?, ?, TIMESTAMPTZ '2026-09-01 00:00:00+00', ?,"
                        + " TIMESTAMPTZ '2026-09-01 00:00:00+00', 10, 0,"
                        + " TIMESTAMPTZ '2026-09-01 00:00:00+00', TIMESTAMPTZ '2026-09-01 00:00:00+00')",
                TENANT_B,
                UUID.randomUUID(),
                UUID.randomUUID(),
                revenue);

        runBackfill();

        assertThat(source(revenue)).containsExactly(null, revenueKey.toString());
    }

    private void runBackfill() throws Exception {
        try (Connection connection = owner.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(BACKFILL));
        }
    }

    private List<String> source(UUID journalEntryId) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT source_event_type, source_event_id::text AS source_event_id FROM journal_entry"
                        + " WHERE journal_entry_id = ?",
                journalEntryId);
        return java.util.Arrays.asList((String) row.get("source_event_type"), (String) row.get("source_event_id"));
    }

    private void insertEntry(UUID tenant, UUID id, UUID sourceEventId, String sourceEventType, UUID reverses) {
        jdbc.update(
                "INSERT INTO journal_entry (tenant_id, journal_entry_id, is_balanced, total_credits, total_debits,"
                        + " created_at, modified_at, transaction_date, entry_type, status, created_by, modified_by,"
                        + " source_event_id, source_event_type, reversal_journal_entry_id)"
                        + " VALUES (?, ?, true, 0, 0, TIMESTAMPTZ '2026-09-01 00:00:00+00',"
                        + " TIMESTAMPTZ '2026-09-01 00:00:00+00', TIMESTAMP '2026-09-01 00:00:00',"
                        + " 'EVENT_DRIVEN', 'POSTED', 'SYSTEM', 'SYSTEM', ?, ?, ?)",
                tenant,
                id,
                sourceEventId,
                sourceEventType,
                reverses);
    }

    private void insertPosting(UUID tenant, UUID journalEntryId, UUID reversalJournalEntryId) {
        jdbc.update(
                "INSERT INTO invoice_gl_posting (tenant_id, invoice_gl_posting_id, invoice_id, finalized_at,"
                        + " journal_entry_id, posted_at, revenue_amount, tax_amount, reversal_journal_entry_id,"
                        + " reversed_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, TIMESTAMPTZ '2026-09-01 00:00:00+00', ?,"
                        + " TIMESTAMPTZ '2026-09-01 00:00:00+00', 10, 0, ?, ?,"
                        + " TIMESTAMPTZ '2026-09-01 00:00:00+00', TIMESTAMPTZ '2026-09-01 00:00:00+00')",
                tenant,
                UUID.randomUUID(),
                UUID.randomUUID(),
                journalEntryId,
                reversalJournalEntryId,
                reversalJournalEntryId == null
                        ? null
                        : java.sql.Timestamp.from(java.time.Instant.parse("2026-09-02T00:00:00Z")));
    }
}
