package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.positivity.accounting.AccountingPostgresContainer;
import com.positivity.accounting.internal.config.TestSecurityConfig;
import com.positivity.accounting.internal.dto.AccountingEventFilter;
import com.positivity.accounting.internal.dto.AccountingEventResponse;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.exception.AccountingPeriodClosedException;
import com.positivity.accounting.internal.repository.AccountingEventRepository;
import com.positivity.accounting.internal.repository.AccountingPeriodRepository;
import com.positivity.accounting.internal.repository.AccountingSequenceRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.IdempotencyKeyRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.accounting.internal.repository.ReprocessingAttemptHistoryRepository;
import com.positivity.domainevents.inventory.ProductValueChangedV1;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Real-Postgres IT for inventory revaluation GL posting (issue #2193, spec
 * SPEC-inventory-adjustment-gl-posting §4.10, #2186 decision D7 final). Runs the full Flyway
 * chain + repeatable seed (which carries the {@code INVENTORY_REVALUATION} posting category and its
 * two mapping keys) on a Testcontainers Postgres, and drives the
 * {@code inventory.product-value.changed} envelope through {@link InventoryEventsListener} exactly
 * as Kafka would deliver it.
 *
 * <p>Requires Docker.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("Inventory revaluation GL posting (#2193, real Postgres)")
class InventoryRevaluationGLPostingIT {

    /** A database of this IT's own: it commits fixtures and clears whole tables. */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        AccountingPostgresContainer.registerIsolatedDatabase(registry, "inventory-revaluation");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    private static final String EVENT_TYPE = ProductValueChangedV1.EVENT_TYPE;

    @Autowired
    private Clock clock;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InventoryShrinkagePostingService shrinkagePostingService;

    @Autowired
    private InventoryAdjustmentPostingService adjustmentPostingService;

    @Autowired
    private InventoryRevaluationPostingService revaluationPostingService;

    @Autowired
    private InventoryFactIngestionRecorder ingestionRecorder;

    @Autowired
    private GLMappingResolver glMappingResolver;

    @Autowired
    private EventIngestionService eventIngestionService;

    @Autowired
    private AccountingPeriodService accountingPeriodService;

    @Autowired
    private AccountingPeriodRepository periodRepository;

    @Autowired
    private AccountingEventRepository accountingEventRepository;

    @Autowired
    private ReprocessingAttemptHistoryRepository reprocessingAttemptHistoryRepository;

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

    private SimpleMeterRegistry meterRegistry;
    private InventoryEventsListener listener;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        ObjectProvider<MeterRegistry> registryProvider = org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.when(registryProvider.getIfAvailable()).thenReturn(meterRegistry);
        // Constructed manually: the Kafka rails (pos.accounting.kafka.enabled) stay off in tests.
        listener = new InventoryEventsListener(
                clock,
                objectMapper,
                processedEventRepository,
                shrinkagePostingService,
                adjustmentPostingService,
                revaluationPostingService,
                ingestionRecorder,
                registryProvider,
                transactionManager);
    }

    @AfterEach
    void cleanUp() {
        journalEntryRepository.deleteAll();
        reprocessingAttemptHistoryRepository.deleteAll();
        accountingEventRepository.deleteAll();
        sequenceRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
        processedEventRepository.deleteAll();
        periodRepository.deleteAll();
    }

    @Test
    @DisplayName("Seed: the two INVENTORY_REVALUATION mapping keys resolve (asset to 1300, offset to 5000)")
    void seededMappingKeysResolve() {
        LocalDateTime now = LocalDateTime.now(clock);
        assertThat(glMappingResolver.resolveGLAccount("INVENTORY_REVALUATION", "INVENTORY_ASSET", now))
                .isEqualTo(accountId("1300"));
        assertThat(glMappingResolver.resolveGLAccount("INVENTORY_REVALUATION", "REVALUATION_OFFSET", now))
                .isEqualTo(accountId("5000"));
    }

    @Test
    @DisplayName(
            "Write-up posts one balanced Dr 1300 / Cr 5000 = abs(totalValueDelta) dated occurredAt, recorded PROCESSED")
    void writeUpPostsInventoryAgainstCogs() {
        UUID revaluationId = UUID.randomUUID();
        Instant occurredAt = Instant.now(clock).minusSeconds(3600).truncatedTo(ChronoUnit.SECONDS);
        String eventId = UUID.randomUUID().toString();

        // (7.25 - 5.00) x 4 = 9.00
        listener.onInventoryEvent(envelope(eventId, revaluationId, "5.00", "7.25", "4", occurredAt));

        BigDecimal expected = new BigDecimal("9.00");
        assertBalancedEntry(accountId("1300"), accountId("5000"), expected);
        JournalEntry entry = onlyEntry();
        assertThat(entry.getTransactionDate()).isEqualTo(LocalDateTime.ofInstant(occurredAt, clock.getZone()));
        assertThat(entry.getSourceEventId())
                .isEqualTo(InventoryRevaluationPostingService.toSourceEventId(revaluationId));
        assertThat(entry.getDescription()).contains("write-up").contains(revaluationId.toString());
        assertThat(processedEventRepository.existsById(eventId)).isTrue();

        AccountingEventResponse record = onlyRecord(revaluationId);
        assertThat(record.getStatus()).isEqualTo(AccountingEventStatus.PROCESSED);
        assertThat(record.getJournalEntryId()).isEqualTo(entry.getJournalEntryId());
        assertThat(record.getIdempotencyOutcome()).isEqualTo("NEW");
        assertThat(record.getSourceSystem()).isEqualTo("pos-inventory");
        assertThat(record.getIngestionId()).isEqualTo(UUID.fromString(eventId));
        assertThat(record.getEventReference()).startsWith("AE-");
        assertThat(meterRegistry
                        .counter(InventoryEventsListener.POSTED_METRIC, "eventType", EVENT_TYPE)
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("Write-down posts one balanced Dr 5000 / Cr 1300")
    void writeDownPostsCogsAgainstInventory() {
        UUID revaluationId = UUID.randomUUID();

        // (6.00 - 10.00) x 2 = -8.00
        listener.onInventoryEvent(
                envelope(UUID.randomUUID().toString(), revaluationId, "10.00", "6.00", "2", Instant.now(clock)));

        assertBalancedEntry(accountId("5000"), accountId("1300"), new BigDecimal("8.00"));
        assertThat(onlyRecord(revaluationId).getJournalEntryId())
                .isEqualTo(onlyEntry().getJournalEntryId());
    }

    @Test
    @DisplayName("Zero value delta posts no journal entry and is recorded PROCESSED, never SKIPPED")
    void zeroDeltaPostsNothingAndIsRecordedProcessed() {
        UUID revaluationId = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();

        listener.onInventoryEvent(envelope(eventId, revaluationId, "5.00", "5.00", "4", Instant.now(clock)));

        assertThat(journalEntryRepository.count()).isZero();
        assertThat(processedEventRepository.existsById(eventId)).isTrue();
        AccountingEventResponse record = onlyRecord(revaluationId);
        assertThat(record.getStatus()).isEqualTo(AccountingEventStatus.PROCESSED);
        assertThat(record.getIdempotencyOutcome()).isEqualTo("NEW");
        assertThat(record.getJournalEntryId()).isNull();
        assertThat(meterRegistry
                        .counter(InventoryEventsListener.SKIPPED_METRIC, "eventType", EVENT_TYPE, "reason", "UNCOSTED")
                        .count())
                .isEqualTo(0.0);
    }

    @Test
    @DisplayName("Redelivery of the same eventId posts nothing more and writes no second record")
    void duplicateEventIdIsDropped() {
        UUID revaluationId = UUID.randomUUID();
        String message = envelope(UUID.randomUUID().toString(), revaluationId, "5.00", "6.00", "1", Instant.now(clock));

        listener.onInventoryEvent(message);
        listener.onInventoryEvent(message);

        assertThat(journalEntryRepository.count()).isEqualTo(1);
        assertThat(accountingEventRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "Re-emission under a new eventId posts nothing more; recorded DUPLICATE_IGNORED with the original entry")
    void reEmittedFactIsDuplicateIgnored() {
        UUID revaluationId = UUID.randomUUID();
        Instant occurredAt = Instant.now(clock);
        String firstEventId = UUID.randomUUID().toString();
        String secondEventId = UUID.randomUUID().toString();

        listener.onInventoryEvent(envelope(firstEventId, revaluationId, "5.00", "6.00", "1", occurredAt));
        listener.onInventoryEvent(envelope(secondEventId, revaluationId, "5.00", "6.00", "1", occurredAt));

        assertThat(journalEntryRepository.count()).isEqualTo(1);
        assertThat(processedEventRepository.existsById(secondEventId)).isTrue();
        UUID original = onlyEntry().getJournalEntryId();
        List<AccountingEventResponse> records = records(revaluationId);
        assertThat(records).hasSize(2);
        assertThat(records)
                .extracting(AccountingEventResponse::getIdempotencyOutcome)
                .containsExactlyInAnyOrder("NEW", "DUPLICATE_IGNORED");
        assertThat(records).allSatisfy(r -> assertThat(r.getJournalEntryId()).isEqualTo(original));
        assertThat(meterRegistry
                        .counter(InventoryEventsListener.POSTED_METRIC, "eventType", EVENT_TYPE)
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("PERIOD_CLOSED propagates and leaves no processed_events row, record or entry")
    void closedPeriodPropagatesUnmarked() {
        accountingPeriodService.closePeriod("2024-03");
        UUID revaluationId = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();
        String message = envelope(eventId, revaluationId, "5.00", "7.00", "2", Instant.parse("2024-03-15T10:00:00Z"));

        assertThatExceptionOfType(AccountingPeriodClosedException.class)
                .isThrownBy(() -> listener.onInventoryEvent(message));

        assertThat(processedEventRepository.existsById(eventId)).isFalse();
        assertThat(journalEntryRepository.count()).isZero();
        assertThat(accountingEventRepository.count()).isZero();
    }

    // ===== helpers =====

    private String envelope(
            String eventId,
            UUID revaluationId,
            String previousUnitCost,
            String newUnitCost,
            String onHandQuantity,
            Instant occurredAt) {
        BigDecimal delta = new BigDecimal(newUnitCost)
                .subtract(new BigDecimal(previousUnitCost))
                .multiply(new BigDecimal(onHandQuantity));
        return """
                {"eventId":"%s","eventType":"inventory.product-value.changed","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":0,
                 "occurredAtUtc":"%s","sourceService":"pos-inventory",
                 "payload":{"revaluationId":"%s","sku":"OIL-FILTER-7","costingMethod":"AVERAGE",
                            "previousUnitCost":%s,"newUnitCost":%s,"onHandQuantity":%s,
                            "totalValueDelta":%s,"reason":"Supplier price correction","actor":"jdoe",
                            "occurredAt":"%s"}}
                """.formatted(
                        eventId,
                        revaluationId,
                        occurredAt,
                        revaluationId,
                        previousUnitCost,
                        newUnitCost,
                        onHandQuantity,
                        delta.toPlainString(),
                        occurredAt);
    }

    private List<AccountingEventResponse> records(UUID revaluationId) {
        AccountingEventFilter filter = AccountingEventFilter.builder()
                .eventType(EVENT_TYPE)
                .domainKeyId(revaluationId.toString())
                .build();
        return eventIngestionService.listEvents(filter, PageRequest.of(0, 10)).getContent();
    }

    private AccountingEventResponse onlyRecord(UUID revaluationId) {
        List<AccountingEventResponse> records = records(revaluationId);
        assertThat(records).hasSize(1);
        return records.getFirst();
    }

    private JournalEntry onlyEntry() {
        List<JournalEntry> entries = journalEntryRepository.findAll();
        assertThat(entries).hasSize(1);
        return entries.getFirst();
    }

    private void assertBalancedEntry(UUID debitAccount, UUID creditAccount, BigDecimal expected) {
        assertThat(journalEntryRepository.count()).isEqualTo(1);
        new TransactionTemplate(transactionManager).executeWithoutResult(_ -> {
            JournalEntry entry = journalEntryRepository.findAll().getFirst();
            assertThat(entry.getStatus()).isEqualTo(JournalEntryStatus.POSTED);
            assertThat(entry.getLines()).hasSize(2);
            Map<UUID, BigDecimal> debits = new HashMap<>();
            Map<UUID, BigDecimal> credits = new HashMap<>();
            for (JournalEntryLine line : entry.getLines()) {
                debits.merge(line.getGlAccountId(), zeroIfNull(line.getDebitAmount()), BigDecimal::add);
                credits.merge(line.getGlAccountId(), zeroIfNull(line.getCreditAmount()), BigDecimal::add);
            }
            assertThat(debits.getOrDefault(debitAccount, BigDecimal.ZERO)).isEqualByComparingTo(expected);
            assertThat(credits.getOrDefault(creditAccount, BigDecimal.ZERO)).isEqualByComparingTo(expected);
        });
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private UUID accountId(String accountCode) {
        return glAccountRepository
                .findByAccountCode(accountCode)
                .orElseThrow(() -> new IllegalStateException("Seed missing GL account " + accountCode))
                .getGlAccountId();
    }
}
