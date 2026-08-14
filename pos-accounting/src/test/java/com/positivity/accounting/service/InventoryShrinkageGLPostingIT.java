package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.internal.config.TestSecurityConfig;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.repository.AccountingSequenceRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.IdempotencyKeyRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.accounting.internal.service.InventoryEventsListener;
import com.positivity.accounting.internal.service.InventoryShrinkagePostingService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * Real-Postgres IT for inventory shrinkage GL posting (odoo-parity D2, issue #1043). Runs the full
 * Flyway chain + repeatable seed (which now carries the {@code INVENTORY_SHRINKAGE} posting
 * category, its two mapping keys, and the 1300 Inventory / 5100 Inventory Shrinkage accounts) on a
 * Testcontainers Postgres, so seed correctness and
 * {@link com.positivity.accounting.service.GLMappingResolver} account resolution are exercised
 * end-to-end.
 *
 * <p>Drives the {@code inventory.scrap.posted} envelope through
 * {@link InventoryEventsListener} exactly as Kafka would deliver it and asserts:
 * fact &rarr; one balanced POSTED entry (Dr 5100 / Cr 1300, quantity x unitCost); replay of the
 * same record and redelivery under a fresh eventId are both no-ops (eventId dedup + scrapId
 * posting key); an uncosted fact (ADR-0048 {@code costSource=NONE}) posts nothing but is marked
 * processed.
 *
 * <p>Skipped cleanly when Docker is unavailable ({@code disabledWithoutDocker}).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("Inventory shrinkage GL posting (parity-D2 #1043, real Postgres)")
class InventoryShrinkageGLPostingIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        // Schema + seed come from the real Flyway chain, not Hibernate DDL.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private Clock clock;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InventoryShrinkagePostingService shrinkagePostingService;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private AccountingSequenceRepository sequenceRepository;

    @Autowired
    private GLAccountRepository glAccountRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * The listener under test, wired with the real Spring collaborators. Constructed manually
     * because the Kafka rails ({@code pos.accounting.kafka.enabled}) stay off in tests — the
     * message path from envelope JSON to posting is identical.
     */
    private InventoryEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new InventoryEventsListener(
                clock,
                objectMapper,
                processedEventRepository,
                shrinkagePostingService,
                org.mockito.Mockito.mock(ObjectProvider.class));
    }

    @AfterEach
    void cleanUp() {
        journalEntryRepository.deleteAll();
        sequenceRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
        processedEventRepository.deleteAll();
    }

    @Test
    @DisplayName("Scrap fact posts one balanced Dr 5100 / Cr 1300 entry; replays and redeliveries are no-ops")
    void scrapFactPostsBalancedEntryExactlyOnce() {
        UUID scrapId = UUID.randomUUID();
        // 3 x 12.50 = 37.50
        String message = envelope("evt-1", scrapId, "\"DAMAGED\"", "12.50", "LATEST_RECEIPT");

        listener.onInventoryEvent(message);

        UUID shrinkage = accountId("5100");
        UUID inventory = accountId("1300");
        BigDecimal expected = new BigDecimal("37.50");

        assertThat(journalEntryRepository.count()).isEqualTo(1);
        Map<UUID, BigDecimal> debits = new HashMap<>();
        Map<UUID, BigDecimal> credits = new HashMap<>();
        sumLines(debits, credits);
        assertThat(debits.getOrDefault(shrinkage, BigDecimal.ZERO)).isEqualByComparingTo(expected);
        assertThat(credits.getOrDefault(inventory, BigDecimal.ZERO)).isEqualByComparingTo(expected);

        inTransaction(() -> {
            JournalEntry entry = journalEntryRepository.findAll().getFirst();
            assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.POSTED);
            // Reason code rides into the memo; unknown codes never block posting.
            assertThat(entry.getDescription()).contains("DAMAGED").contains(scrapId.toString());
            assertThat(entry.getLines()).hasSize(2);
        });

        // Replay of the exact same record: eventId dedup, no second entry.
        listener.onInventoryEvent(message);
        assertThat(journalEntryRepository.count()).isEqualTo(1);

        // Redelivery under a fresh eventId (outbox re-publish): scrapId posting key, no second entry.
        listener.onInventoryEvent(envelope("evt-2", scrapId, "\"DAMAGED\"", "12.50", "LATEST_RECEIPT"));
        assertThat(journalEntryRepository.count()).isEqualTo(1);
        assertThat(processedEventRepository.existsById("evt-1")).isTrue();
        assertThat(processedEventRepository.existsById("evt-2")).isTrue();

        Map<UUID, BigDecimal> debitsAfter = new HashMap<>();
        sumLines(debitsAfter, new HashMap<>());
        assertThat(debitsAfter.getOrDefault(shrinkage, BigDecimal.ZERO)).isEqualByComparingTo(expected);
    }

    @Test
    @DisplayName("Uncosted fact (costSource NONE) posts nothing but is marked processed")
    void uncostedFactIsSkippedButProcessed() {
        UUID scrapId = UUID.randomUUID();

        listener.onInventoryEvent(envelope("evt-3", scrapId, "\"LOST\"", "null", "NONE"));

        assertThat(journalEntryRepository.count()).isZero();
        assertThat(processedEventRepository.existsById("evt-3")).isTrue();
    }

    // ===== helpers =====

    private String envelope(String eventId, UUID scrapId, String reasonCode, String unitCost, String costSource) {
        Instant occurredAt = Instant.now(clock);
        return """
                {"eventId":"%s","eventType":"inventory.scrap.posted","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":0,
                 "occurredAtUtc":"%s","sourceService":"pos-inventory",
                 "payload":{"scrapId":"%s","sku":"BRAKE-PAD-22","locationId":null,
                            "storageLocationId":null,"quantity":3,"reasonCode":%s,
                            "unitCost":%s,"costSource":"%s","workorderId":null,
                            "occurredAt":"%s"}}
                """.formatted(eventId, scrapId, occurredAt, scrapId, reasonCode, unitCost, costSource, occurredAt);
    }

    private UUID accountId(String accountCode) {
        return glAccountRepository
                .findByAccountCode(accountCode)
                .orElseThrow(() -> new IllegalStateException("Seed missing GL account " + accountCode))
                .getGlAccountId();
    }

    /** Sum debit and credit amounts per GL account across all journal entries (lazy assoc loaded in-tx). */
    private void sumLines(Map<UUID, BigDecimal> debits, Map<UUID, BigDecimal> credits) {
        inTransaction(() -> {
            for (JournalEntry entry : journalEntryRepository.findAll()) {
                for (JournalEntryLine line : entry.getLines()) {
                    BigDecimal debit = line.getDebitAmount() == null ? BigDecimal.ZERO : line.getDebitAmount();
                    BigDecimal credit = line.getCreditAmount() == null ? BigDecimal.ZERO : line.getCreditAmount();
                    debits.merge(line.getGlAccountId(), debit, BigDecimal::add);
                    credits.merge(line.getGlAccountId(), credit, BigDecimal::add);
                }
            }
        });
    }

    private void inTransaction(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }
}
