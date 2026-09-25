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
import com.positivity.domainevents.inventory.InventoryAdjustedV1;
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
 * Real-Postgres IT for inventory adjustment GL posting (issue #2191, spec
 * SPEC-inventory-adjustment-gl-posting §6). Runs the full Flyway chain + repeatable seed (which
 * carries the {@code INVENTORY_ADJUSTMENT} posting category and its three mapping keys, and the
 * V4 {@code SKIPPED} status constraint) on a Testcontainers Postgres, and drives the
 * {@code inventory.adjustment.posted} envelope through {@link InventoryEventsListener} exactly as
 * Kafka would deliver it.
 *
 * <p>Requires Docker.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("Inventory adjustment GL posting (#2191, real Postgres)")
class InventoryAdjustmentGLPostingIT {

    /** A database of this IT's own: it commits fixtures and clears whole tables. */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        AccountingPostgresContainer.registerIsolatedDatabase(registry, "inventory-adjustment");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    private static final String EVENT_TYPE = InventoryAdjustedV1.EVENT_TYPE;

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
    @DisplayName("Seed: the three INVENTORY_ADJUSTMENT mapping keys resolve (loss and gain to 5100, asset to 1300)")
    void seededMappingKeysResolve() {
        LocalDateTime now = LocalDateTime.now(clock);
        assertThat(glMappingResolver.resolveGLAccount("INVENTORY_ADJUSTMENT", "ADJUSTMENT_LOSS", now))
                .isEqualTo(accountId("5100"));
        assertThat(glMappingResolver.resolveGLAccount("INVENTORY_ADJUSTMENT", "ADJUSTMENT_GAIN", now))
                .isEqualTo(accountId("5100"));
        assertThat(glMappingResolver.resolveGLAccount("INVENTORY_ADJUSTMENT", "INVENTORY_ASSET", now))
                .isEqualTo(accountId("1300"));
    }

    @Test
    @DisplayName(
            "Loss posts one balanced Dr 5100 / Cr 1300 = abs(delta) x unitCost dated occurredAt, recorded PROCESSED")
    void lossPostsShrinkageAgainstInventory() {
        UUID adjustmentId = UUID.randomUUID();
        Instant occurredAt = Instant.now(clock).minusSeconds(3600).truncatedTo(ChronoUnit.SECONDS);
        String eventId = UUID.randomUUID().toString();

        // -4 x 7.25 = 29.00
        listener.onInventoryEvent(envelope(eventId, adjustmentId, "CYCLE_COUNT", "-4", "7.25", "AVERAGE", occurredAt));

        BigDecimal expected = new BigDecimal("29.00");
        assertBalancedEntry(accountId("5100"), accountId("1300"), expected);
        JournalEntry entry = onlyEntry();
        assertThat(entry.getTransactionDate()).isEqualTo(LocalDateTime.ofInstant(occurredAt, clock.getZone()));
        assertThat(entry.getSourceEventId())
                .isEqualTo(InventoryAdjustmentPostingService.toSourceEventId("CYCLE_COUNT", adjustmentId));
        assertThat(entry.getDescription()).contains("COUNT_ERROR").contains(adjustmentId.toString());
        assertThat(processedEventRepository.existsById(eventId)).isTrue();

        AccountingEventResponse record = onlyRecord(adjustmentId);
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
    @DisplayName("Gain posts one balanced Dr 1300 / Cr ADJUSTMENT_GAIN (5100)")
    void gainPostsInventoryAgainstGainAccount() {
        UUID adjustmentId = UUID.randomUUID();

        // +2.5 x 10.00 = 25.00 (decimal-capable delta, ADR-0055)
        listener.onInventoryEvent(envelope(
                UUID.randomUUID().toString(),
                adjustmentId,
                "MANUAL_ADJUSTMENT",
                "2.5",
                "10.00",
                "STANDARD",
                Instant.now(clock)));

        assertBalancedEntry(accountId("1300"), accountId("5100"), new BigDecimal("25.00"));
        assertThat(onlyRecord(adjustmentId).getJournalEntryId())
                .isEqualTo(onlyEntry().getJournalEntryId());
    }

    @Test
    @DisplayName("Uncosted fact posts nothing, counts the skip and leaves a SKIPPED / UNCOSTED_FACT record")
    void uncostedFactIsSkippedAndRecorded() {
        UUID adjustmentId = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();

        listener.onInventoryEvent(
                envelope(eventId, adjustmentId, "CYCLE_COUNT", "-3", "null", "NONE", Instant.now(clock)));

        assertThat(journalEntryRepository.count()).isZero();
        assertThat(processedEventRepository.existsById(eventId)).isTrue();
        AccountingEventResponse record = onlyRecord(adjustmentId);
        assertThat(record.getStatus()).isEqualTo(AccountingEventStatus.SKIPPED);
        assertThat(record.getFailureReasonCode()).isEqualTo("UNCOSTED_FACT");
        assertThat(record.getJournalEntryId()).isNull();
        assertThat(meterRegistry
                        .counter(InventoryEventsListener.SKIPPED_METRIC, "eventType", EVENT_TYPE, "reason", "UNCOSTED")
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("Redelivery of the same eventId posts nothing more and writes no second record")
    void duplicateEventIdIsDropped() {
        UUID adjustmentId = UUID.randomUUID();
        String message = envelope(
                UUID.randomUUID().toString(), adjustmentId, "CYCLE_COUNT", "-1", "5.00", "AVERAGE", Instant.now(clock));

        listener.onInventoryEvent(message);
        listener.onInventoryEvent(message);

        assertThat(journalEntryRepository.count()).isEqualTo(1);
        assertThat(accountingEventRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "Re-emission under a new eventId posts nothing more; recorded DUPLICATE_IGNORED with the original entry")
    void reEmittedFactIsDuplicateIgnored() {
        UUID adjustmentId = UUID.randomUUID();
        Instant occurredAt = Instant.now(clock);
        String firstEventId = UUID.randomUUID().toString();
        String secondEventId = UUID.randomUUID().toString();

        listener.onInventoryEvent(
                envelope(firstEventId, adjustmentId, "CYCLE_COUNT", "-1", "5.00", "AVERAGE", occurredAt));
        listener.onInventoryEvent(
                envelope(secondEventId, adjustmentId, "CYCLE_COUNT", "-1", "5.00", "AVERAGE", occurredAt));

        assertThat(journalEntryRepository.count()).isEqualTo(1);
        assertThat(processedEventRepository.existsById(secondEventId)).isTrue();
        UUID original = onlyEntry().getJournalEntryId();
        List<AccountingEventResponse> records = records(adjustmentId);
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
        UUID adjustmentId = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();
        String message = envelope(
                eventId, adjustmentId, "CYCLE_COUNT", "-2", "4.00", "AVERAGE", Instant.parse("2024-03-15T10:00:00Z"));

        assertThatExceptionOfType(AccountingPeriodClosedException.class)
                .isThrownBy(() -> listener.onInventoryEvent(message));

        assertThat(processedEventRepository.existsById(eventId)).isFalse();
        assertThat(journalEntryRepository.count()).isZero();
        assertThat(accountingEventRepository.count()).isZero();
    }

    // ===== helpers =====

    private String envelope(
            String eventId,
            UUID adjustmentId,
            String kind,
            String quantityDelta,
            String unitCost,
            String costSource,
            Instant occurredAt) {
        return """
                {"eventId":"%s","eventType":"inventory.adjustment.posted","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":0,
                 "occurredAtUtc":"%s","sourceService":"pos-inventory",
                 "payload":{"adjustmentId":"%s","adjustmentKind":"%s","ledgerEventType":"COUNT_VARIANCE_OUT",
                            "ledgerEntryId":"%s","sku":"OIL-FILTER-7","locationId":null,"taskId":null,
                            "reasonCode":"COUNT_ERROR","quantityDelta":%s,"unitCost":%s,
                            "costSource":"%s","occurredAt":"%s"}}
                """.formatted(
                        eventId,
                        adjustmentId,
                        occurredAt,
                        adjustmentId,
                        kind,
                        UUID.randomUUID(),
                        quantityDelta,
                        unitCost,
                        costSource,
                        occurredAt);
    }

    private List<AccountingEventResponse> records(UUID adjustmentId) {
        AccountingEventFilter filter = AccountingEventFilter.builder()
                .eventType(EVENT_TYPE)
                .domainKeyId(adjustmentId.toString())
                .build();
        return eventIngestionService.listEvents(filter, PageRequest.of(0, 10)).getContent();
    }

    private AccountingEventResponse onlyRecord(UUID adjustmentId) {
        List<AccountingEventResponse> records = records(adjustmentId);
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
