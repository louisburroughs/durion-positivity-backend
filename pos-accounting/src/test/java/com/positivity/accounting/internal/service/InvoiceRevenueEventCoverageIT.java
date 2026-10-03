package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.AccountingPostgresContainer;
import com.positivity.accounting.BaseIntegrationTest;
import com.positivity.accounting.internal.repository.AccountingEventRepository;
import com.positivity.accounting.internal.repository.AccountingSequenceRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceTaxRepository;
import com.positivity.accounting.internal.repository.IdempotencyKeyRepository;
import com.positivity.accounting.internal.repository.InvoiceGlPostingRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.accounting.internal.repository.ReprocessingAttemptHistoryRepository;
import com.positivity.domainevents.invoice.InvoiceUpdatedV1;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;

/**
 * Real-Postgres IT for issue #2433: the invoice revenue path writes one {@code accounting_event}
 * row per consumed {@code invoice.invoice.updated} fact, and {@code GET
 * /v1/accounting/events?eventType=invoice.invoice.updated} returns them — the event list is the
 * audit view of the revenue facts accounting received, not only of inventory facts.
 *
 * <p>Drives envelopes through {@link InvoiceEventsListener} exactly as Kafka would deliver them
 * (the listener is built by hand because the Kafka rails stay off in tests): {@code FINALIZED}
 * posts revenue ({@code PROCESSED / NEW}, linked to the entry); the {@code POSTED} fact that
 * follows under a fresh event id finds the open posting ({@code PROCESSED / DUPLICATE_IGNORED},
 * linked to the same entry); a redelivery of the first envelope is short-circuited by {@code
 * processed_events} and writes no row; {@code CANCELLED} posts the reversal ({@code PROCESSED /
 * NEW}, linked to the reversal entry). Three facts about one invoice, three rows under the same
 * {@code domainKeyId}.
 *
 * <p>Requires Docker.
 */
@DisplayName("Invoice revenue facts in the accounting event list (#2433, real Postgres)")
class InvoiceRevenueEventCoverageIT extends BaseIntegrationTest {

    /**
     * A database of this IT's own inside the shared container: it commits its fixtures and clears
     * whole tables, so it must not share the default database with tests that read the seed.
     */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        AccountingPostgresContainer.registerIsolatedDatabase(registry, "invoice-event-coverage");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        // Schema + seed come from the real Flyway chain, not Hibernate DDL.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    private static final String EVENTS_API = "/v1/accounting/events";

    @Autowired
    private Clock clock;

    @Autowired
    private InvoiceRevenuePostingService invoiceRevenuePostingService;

    @Autowired
    private KafkaFactIngestionRecorder ingestionRecorder;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private ExtInvoiceRepository extInvoiceRepository;

    @Autowired
    private ExtInvoiceTaxRepository extInvoiceTaxRepository;

    @Autowired
    private InvoiceGlPostingRepository invoiceGlPostingRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private AccountingEventRepository accountingEventRepository;

    @Autowired
    private ReprocessingAttemptHistoryRepository reprocessingAttemptHistoryRepository;

    @Autowired
    private AccountingSequenceRepository sequenceRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private InvoiceEventsListener listener;

    @BeforeEach
    void setUpListener() {
        @SuppressWarnings("unchecked")
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> noMetrics =
                org.mockito.Mockito.mock(ObjectProvider.class);
        listener = new InvoiceEventsListener(
                clock,
                objectMapper,
                processedEventRepository,
                extInvoiceRepository,
                extInvoiceTaxRepository,
                invoiceRevenuePostingService,
                ingestionRecorder,
                noMetrics,
                transactionManager);
    }

    @AfterEach
    void cleanUp() {
        invoiceGlPostingRepository.deleteAll();
        journalEntryRepository.deleteAll();
        // reprocessing_attempt_history references accounting_event (#2202).
        reprocessingAttemptHistoryRepository.deleteAll();
        accountingEventRepository.deleteAll();
        sequenceRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
        processedEventRepository.deleteAll();
        extInvoiceTaxRepository.deleteAll();
        extInvoiceRepository.deleteAll();
    }

    @Test
    @DisplayName("Each consumed invoice fact is one event-list row, linked to its entry; a redelivery adds none")
    void invoiceFactsAppearInTheEventList() throws Exception {
        UUID invoiceId = UUID.randomUUID();
        Instant finalizedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        String finalizedEnvelope = envelope(UUID.randomUUID(), invoiceId, 1, "FINALIZED", finalizedAt);

        listener.onInvoiceEvent(finalizedEnvelope);
        listener.onInvoiceEvent(envelope(UUID.randomUUID(), invoiceId, 2, "POSTED", finalizedAt));
        // Redelivery of the first envelope: processed_events short-circuits it, no second row.
        listener.onInvoiceEvent(finalizedEnvelope);
        listener.onInvoiceEvent(envelope(UUID.randomUUID(), invoiceId, 3, "CANCELLED", finalizedAt));

        UUID revenueEntryId = invoiceGlPostingRepository.findAll().getFirst().getJournalEntryId();
        UUID reversalEntryId = invoiceGlPostingRepository.findAll().getFirst().getReversalJournalEntryId();
        assertThat(journalEntryRepository.count()).isEqualTo(2);

        String body = mockMvc.perform(withAuth(get(EVENTS_API))
                        .param("eventType", InvoiceUpdatedV1.EVENT_TYPE)
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode content = objectMapper.readTree(body).path("content");

        List<JsonNode> rows = new ArrayList<>();
        content.forEach(rows::add);
        assertThat(rows).hasSize(3).allSatisfy(row -> {
            assertThat(row.path("eventType").stringValue(null)).isEqualTo(InvoiceUpdatedV1.EVENT_TYPE);
            assertThat(row.path("sourceSystem").stringValue(null)).isEqualTo("pos-invoice");
            assertThat(row.path("domainKeyId").stringValue(null)).isEqualTo(invoiceId.toString());
            assertThat(row.path("status").stringValue(null)).isEqualTo("PROCESSED");
            assertThat(row.path("eventReference").stringValue(null)).startsWith("AE-");
        });
        assertThat(rows)
                .extracting(
                        row -> row.path("idempotencyOutcome").stringValue(null),
                        row -> row.path("journalEntryId").stringValue(null))
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("NEW", revenueEntryId.toString()),
                        org.assertj.core.groups.Tuple.tuple("DUPLICATE_IGNORED", revenueEntryId.toString()),
                        org.assertj.core.groups.Tuple.tuple("NEW", reversalEntryId.toString()));

        // #2434: both entries name their source, and the journal-entry API returns it.
        assertSource(revenueEntryId, JournalEntrySourceTypes.INVOICE_REVENUE);
        assertSource(reversalEntryId, JournalEntrySourceTypes.INVOICE_REVENUE_REVERSAL);
    }

    private void assertSource(UUID journalEntryId, String expectedType) throws Exception {
        String body = mockMvc.perform(withAuth(get("/v1/accounting/journal-entries/" + journalEntryId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode entry = objectMapper.readTree(body);
        assertThat(entry.path("sourceEventType").stringValue(null)).isEqualTo(expectedType);
        assertThat(entry.path("sourceEventId").stringValue(null)).isNotBlank();
    }

    @Test
    @DisplayName("A deposit-take invoice is in the event list as SKIPPED / NOT_POSTABLE, with nothing posted")
    void depositTakeInvoiceIsListedSkipped() throws Exception {
        UUID invoiceId = UUID.randomUUID();
        Instant finalizedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);

        listener.onInvoiceEvent(envelope(UUID.randomUUID(), invoiceId, 1, "FINALIZED", finalizedAt)
                .replace("\"depositSourceType\":null", "\"depositSourceType\":\"WORKORDER\""));

        assertThat(journalEntryRepository.count()).isZero();
        String body = mockMvc.perform(withAuth(get(EVENTS_API))
                        .param("eventType", InvoiceUpdatedV1.EVENT_TYPE)
                        .param("domainKeyId", invoiceId.toString()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode content = objectMapper.readTree(body).path("content");
        assertThat(content.size()).isEqualTo(1);
        JsonNode row = content.get(0);
        assertThat(row.path("status").stringValue(null)).isEqualTo("SKIPPED");
        assertThat(row.path("failureReasonCode").stringValue(null)).isEqualTo("NOT_POSTABLE");
        assertThat(row.path("journalEntryId").isNull()
                        || row.path("journalEntryId").isMissingNode())
                .isTrue();
    }

    private static String envelope(UUID eventId, UUID invoiceId, long version, String status, Instant finalizedAt) {
        return """
                {"eventId":"%s","eventType":"invoice.invoice.updated","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":%d,"occurredAtUtc":"%s","sourceService":"pos-invoice",
                 "payload":{"invoiceId":"%s","invoiceNumber":"INV-2433-0001","status":"%s",
                            "subtotal":1000.00,"tax":80.00,"total":1080.00,"adjustmentsAmount":0,
                            "createdAt":"%s","finalizedAt":"%s","depositSourceType":null}}
                """.formatted(eventId, invoiceId, version, finalizedAt, invoiceId, status, finalizedAt, finalizedAt);
    }
}
