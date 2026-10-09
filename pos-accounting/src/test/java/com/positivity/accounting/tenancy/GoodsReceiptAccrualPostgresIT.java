package com.positivity.accounting.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.internal.dto.GoodsReceivedEvent;
import com.positivity.accounting.internal.dto.ReprocessEventRequest;
import com.positivity.accounting.internal.dto.VendorBillCommands;
import com.positivity.accounting.internal.dto.VendorBillResponse;
import com.positivity.accounting.internal.dto.VendorInvoiceReceivedEvent;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.exception.AccountingPeriodClosedException;
import com.positivity.accounting.internal.repository.AccountingEventRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.accounting.internal.service.AccountingCalendarZoneResolver;
import com.positivity.accounting.internal.service.EventIngestionService;
import com.positivity.accounting.internal.service.FinancialReportingService;
import com.positivity.accounting.internal.service.GoodsReceiptAccrualPostingService;
import com.positivity.accounting.internal.service.IdempotencyService;
import com.positivity.accounting.internal.service.InventoryAdjustmentPostingService;
import com.positivity.accounting.internal.service.InventoryEventsListener;
import com.positivity.accounting.internal.service.InventoryRevaluationPostingService;
import com.positivity.accounting.internal.service.InventoryShrinkagePostingService;
import com.positivity.accounting.internal.service.KafkaFactIngestionRecorder;
import com.positivity.accounting.internal.service.VendorBillApprovalService;
import com.positivity.accounting.internal.service.VendorBillService;
import com.positivity.domainevents.inventory.GoodsReceiptLine;
import com.positivity.domainevents.inventory.GoodsReceiptRecordedV1;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.tenancy.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Real-Postgres IT for a goods receipt's accrual (CAP:550 S41, #2602; AW38 as amended on #2598), driven through
 * {@link InventoryEventsListener} exactly as Kafka delivers {@code goodsreceipt.recorded}, on fresh tenants provisioned
 * from the accounting template, so the {@code GOODS_RECEIPT} mappings (1300, 2100, 5050) and the "Deliveries not yet
 * billed" statement line resolve end to end; and, with S12's approval posting, a matched bill clearing the accrual.
 *
 * <p>Requires Docker.
 */
@DisplayName("Goods receipts post to inventory and GRNI (#2602, real Postgres)")
class GoodsReceiptAccrualPostgresIT extends PostgresTenancyTestBase {

    private static final String EVENT_TYPE = GoodsReceiptRecordedV1.EVENT_TYPE;
    private static final String[] CONTROLLER_GRANTS = {
        "accounting:ap:view",
        "accounting:ap:pay",
        "accounting:ap:approve",
        "accounting:ap:reject",
        "accounting:ap:approve_over_limit",
        "accounting:je:post"
    };

    @Autowired
    private Clock clock;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private InventoryShrinkagePostingService shrinkagePostingService;

    @Autowired
    private InventoryAdjustmentPostingService adjustmentPostingService;

    @Autowired
    private InventoryRevaluationPostingService revaluationPostingService;

    @Autowired
    private GoodsReceiptAccrualPostingService goodsReceiptPostingService;

    @Autowired
    private KafkaFactIngestionRecorder ingestionRecorder;

    @Autowired
    private ObjectProvider<MeterRegistry> meterRegistry;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private AccountingCalendarZoneResolver zoneResolver;

    @Autowired
    private FinancialReportingService reports;

    @Autowired
    private VendorBillService vendorBills;

    @Autowired
    private VendorBillApprovalService approvals;

    @Autowired
    private EventIngestionService eventIngestionService;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private AccountingEventRepository accountingEventRepository;

    @Autowired
    private IdempotencyService idempotencyService;

    private final List<UUID> tenants = new ArrayList<>();

    private InventoryEventsListener listener;
    private Instant occurredAt;

    @BeforeEach
    void setUp() {
        listener = new InventoryEventsListener(
                clock,
                objectMapper,
                processedEventRepository,
                shrinkagePostingService,
                adjustmentPostingService,
                revaluationPostingService,
                goodsReceiptPostingService,
                ingestionRecorder,
                meterRegistry,
                transactionManager,
                zoneResolver);
        occurredAt = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS).minus(2, ChronoUnit.HOURS);
    }

    @AfterEach
    void removeTestTenants() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        List<String> scoped = owner.queryForList(
                "SELECT table_name FROM information_schema.columns WHERE table_schema = 'public'"
                        + " AND column_name = 'tenant_id' AND table_name NOT LIKE 'pg_%' ORDER BY table_name",
                String.class);
        for (UUID tenant : tenants) {
            owner.update(
                    "UPDATE journal_entry SET reversal_journal_entry_id = NULL, reversed_by_journal_entry_id = NULL"
                            + " WHERE tenant_id = ?",
                    tenant);
            owner.update("UPDATE vendor_bill SET journal_entry_id = NULL WHERE tenant_id = ?", tenant);
            for (int pass = 0; pass < 8; pass++) {
                boolean blocked = false;
                for (String table : scoped) {
                    try {
                        owner.update("DELETE FROM " + table + " WHERE tenant_id = ?", tenant);
                    } catch (RuntimeException stillReferenced) {
                        blocked = true;
                    }
                }
                if (!blocked) {
                    break;
                }
            }
        }
        tenants.clear();
    }

    @Test
    @DisplayName("AC1, AC3, AC8: 4 x 100.00 posts Dr 1300 / Cr 2100 400.00 once, dated occurredAt, shown on"
            + " \"Deliveries not yet billed\"; a second tenant posts its own (ADR-0062)")
    void receiptPostsOnceAndShowsAsDeliveriesNotBilled() {
        UUID tenant = tenant();
        GoodsReceiptRecordedV1 fact = fact("USD", 40_000L, line("4", 40_000L, 40_000L, "AVERAGE"));
        String eventId = UUID.randomUUID().toString();

        asTenant(tenant, () -> listener.onInventoryEvent(envelope(eventId, fact)));

        assertThat(lines(tenant)).containsExactlyInAnyOrder("1300 D400.0000", "2100 C400.0000");
        Map<String, Object> entry = onlyEntry(tenant);
        assertThat(entry)
                .containsEntry("source_event_type", "GOODS_RECEIPT_ACCRUAL")
                .containsEntry("source_event_id", GoodsReceiptAccrualPostingService.toSourceEventId(fact.receiptId()))
                .containsEntry("status", "POSTED");
        assertThat(((java.sql.Timestamp) entry.get("transaction_date")).toLocalDateTime())
                .isEqualTo(LocalDateTime.ofInstant(occurredAt, ZoneOffset.UTC));
        assertThat(records(tenant, fact.receiptId())).singleElement().satisfies(record -> {
            assertThat(record.get("status")).isEqualTo("PROCESSED");
            assertThat(record.get("idempotency_outcome")).isEqualTo("NEW");
            assertThat(record.get("journal_entry_id")).isEqualTo(entry.get("journal_entry_id"));
            assertThat((String) record.get("payload")).contains("receiptLineId").contains("inventoryValueMinor");
        });

        // AC8: 2100 reports on "Deliveries not yet billed".
        assertThat(asTenant(tenant, () -> reports.generateBalanceSheet(today()))
                        .getLineItems()
                        .get("BS_DELIVERIES_NOT_BILLED"))
                .isEqualByComparingTo("400.00");

        // AC3: the same eventId is dropped before any transaction; the same receipt under a new one posts nothing.
        asTenant(tenant, () -> listener.onInventoryEvent(envelope(eventId, fact)));
        assertThat(records(tenant, fact.receiptId())).hasSize(1);
        asTenant(
                tenant,
                () -> listener.onInventoryEvent(envelope(UUID.randomUUID().toString(), fact)));
        assertThat(entryCount(tenant)).isEqualTo(1);
        assertThat(records(tenant, fact.receiptId()))
                .extracting(record -> record.get("idempotency_outcome"))
                .containsExactlyInAnyOrder("NEW", "DUPLICATE_IGNORED");

        // ADR-0062, read through the application pool under row-level security: the other tenant's entry lookup,
        // ingestion records and posting key all come back empty for this receipt.
        UUID other = tenant();
        UUID sourceEventId = GoodsReceiptAccrualPostingService.toSourceEventId(fact.receiptId());
        String postingKey = GoodsReceiptAccrualPostingService.postingKey(fact.receiptId());
        assertThat(asTenant(tenant, () -> journalEntryRepository.findBySourceEvent(sourceEventId)))
                .hasSize(1);
        assertThat(asTenant(other, () -> journalEntryRepository.findBySourceEvent(sourceEventId)))
                .isEmpty();
        assertThat(asTenant(
                        other,
                        () -> accountingEventRepository.findAll().stream()
                                .filter(row -> fact.receiptId().toString().equals(row.getDomainKeyId()))
                                .toList()))
                .isEmpty();
        assertThat(asTenant(tenant, () -> idempotencyService.isKeyProcessed(postingKey)))
                .isTrue();
        assertThat(asTenant(other, () -> idempotencyService.isKeyProcessed(postingKey)))
                .isFalse();
        // processed_events is deliberately global (@TenantGlobal, db/tenancy-global-tables.txt): an eventId is
        // deduplicated across tenants and the tenant it was applied under is kept as data. So the same envelope
        // delivered to the other tenant is dropped, while the same receipt under its own eventId posts its own entry:
        // the posting key, the entry and the ingestion row are tenant-scoped.
        assertThat(asTenant(other, () -> processedEventRepository.existsById(eventId)))
                .isTrue();
        assertThat(new JdbcTemplate(ownerDataSource())
                        .queryForObject(
                                "SELECT tenant_id FROM processed_events WHERE event_id = ?", UUID.class, eventId))
                .isEqualTo(tenant);
        asTenant(other, () -> listener.onInventoryEvent(envelope(eventId, fact)));
        assertThat(entryCount(other)).isZero();
        asTenant(
                other,
                () -> listener.onInventoryEvent(envelope(UUID.randomUUID().toString(), fact)));
        assertThat(lines(other)).containsExactlyInAnyOrder("1300 D400.0000", "2100 C400.0000");
        assertThat(asTenant(other, () -> journalEntryRepository.findBySourceEvent(sourceEventId)))
                .hasSize(1);
        assertThat(entryCount(tenant)).isEqualTo(1);

        // Past the posting key's 24 hours, the entry's deterministic source event is the backstop.
        new JdbcTemplate(ownerDataSource())
                .update(
                        "UPDATE idempotency_keys SET expires_at = now() - interval '1 day' WHERE tenant_id = ?",
                        tenant);
        asTenant(
                tenant,
                () -> listener.onInventoryEvent(envelope(UUID.randomUUID().toString(), fact)));
        assertThat(entryCount(tenant)).isEqualTo(1);
    }

    @Test
    @DisplayName("Ruling 4: reprocessing a held receipt re-runs its own path: a currency-less one and a malformed one"
            + " stay held with their reasons; one whose data is now valid posts once; a second held row of the"
            + " posted receipt closes DUPLICATE_IGNORED")
    void reprocessRoutesThroughTheReceiptsOwnPath() {
        UUID tenant = tenant();
        GoodsReceiptRecordedV1 noCurrency = fact(null, 40_000L, line("4", 40_000L, 40_000L, "AVERAGE"));
        GoodsReceiptRecordedV1 malformed = fact("USD", 40_001L, line("4", 40_000L, 40_000L, "AVERAGE"));
        asTenant(
                tenant,
                () -> listener.onInventoryEvent(envelope(UUID.randomUUID().toString(), noCurrency)));
        asTenant(
                tenant,
                () -> listener.onInventoryEvent(envelope(UUID.randomUUID().toString(), malformed)));

        reprocess(tenant, heldRow(tenant, noCurrency.receiptId()));
        reprocess(tenant, heldRow(tenant, malformed.receiptId()));

        assertThat(records(tenant, noCurrency.receiptId())).singleElement().satisfies(record -> {
            assertThat(record.get("status")).isEqualTo("SUSPENDED");
            assertThat(record.get("failure_reason_code")).isEqualTo("CURRENCY_NOT_SUPPORTED");
        });
        assertThat(records(tenant, malformed.receiptId())).singleElement().satisfies(record -> {
            assertThat(record.get("status")).isEqualTo("SUSPENDED");
            assertThat(record.get("failure_reason_code")).isEqualTo("VALIDATION_ERROR");
            assertThat((String) record.get("error_message")).contains("not totalAccruedAmountMinor 40001");
        });
        assertThat(entryCount(tenant)).isZero();

        // The held row's data made valid (the stored fact now states USD): the reprocess posts it once.
        UUID first = heldRow(tenant, noCurrency.receiptId());
        statePayloadCurrency(tenant, first, "USD");
        reprocess(tenant, first);
        assertThat(entryCount(tenant)).isEqualTo(1);
        assertThat(lines(tenant)).containsExactlyInAnyOrder("1300 D400.0000", "2100 C400.0000");
        assertThat(records(tenant, noCurrency.receiptId())).singleElement().satisfies(record -> {
            assertThat(record.get("status")).isEqualTo("PROCESSED");
            assertThat(record.get("idempotency_outcome")).isEqualTo("NEW");
            assertThat(record.get("failure_reason_code")).isNull();
            assertThat(record.get("journal_entry_id"))
                    .isEqualTo(onlyEntry(tenant).get("journal_entry_id"));
        });

        // The same receipt held again under a new eventId, then made valid: the key already posted, so nothing posts.
        asTenant(
                tenant,
                () -> listener.onInventoryEvent(envelope(UUID.randomUUID().toString(), noCurrency)));
        UUID second = heldRow(tenant, noCurrency.receiptId());
        statePayloadCurrency(tenant, second, "USD");
        reprocess(tenant, second);
        assertThat(entryCount(tenant)).isEqualTo(1);
        assertThat(records(tenant, noCurrency.receiptId()))
                .extracting(record -> record.get("idempotency_outcome"))
                .containsExactlyInAnyOrder("NEW", "DUPLICATE_IGNORED");
        assertThat(count(tenant, "reprocessing_attempt_history")).isEqualTo(4);
    }

    @Test
    @DisplayName("AC2, AC9: a STANDARD line (380.00 against 400.00) and an unpriced line (150.00) post Dr 1300 530.00 /"
            + " Cr 2100 400.00 / Dr 5050 20.00 / Cr 5050 150.00, the unpriced credit on its own line")
    void standardVarianceAndUnpricedLine() {
        UUID tenant = tenant();
        GoodsReceiptLine unpriced = new GoodsReceiptLine(
                null, "SKU-9", BigDecimal.ONE, 0L, UUIDv7Generator.generate(), null, 15_000L, "AVERAGE", null);
        GoodsReceiptRecordedV1 fact = fact("USD", 40_000L, line("4", 40_000L, 38_000L, "STANDARD"), unpriced);

        asTenant(
                tenant,
                () -> listener.onInventoryEvent(envelope(UUID.randomUUID().toString(), fact)));

        assertThat(lines(tenant))
                .containsExactlyInAnyOrder(
                        "1300 D380.0000", "2100 C400.0000", "5050 D20.0000", "1300 D150.0000", "5050 C150.0000");
        assertThat(entryCount(tenant)).isEqualTo(1);
        assertThat(new JdbcTemplate(ownerDataSource())
                        .queryForObject(
                                "SELECT l.description FROM journal_entry_line l WHERE l.tenant_id = ? AND"
                                        + " l.credit_amount = 150",
                                String.class,
                                tenant))
                .startsWith("Unpriced receipt line")
                .contains(unpriced.receiptLineId().toString())
                .contains("sku SKU-9");
    }

    @Test
    @DisplayName("AC4: no currency, or EUR for a USD ledger: no entry, one SUSPENDED / CURRENCY_NOT_SUPPORTED record")
    void foreignOrMissingCurrencyIsHeld() {
        UUID tenant = tenant();
        GoodsReceiptRecordedV1 euro = fact("EUR", 40_000L, line("4", 40_000L, null, "AVERAGE"));
        GoodsReceiptRecordedV1 none = fact(null, 40_000L, line("4", 40_000L, null, "AVERAGE"));

        asTenant(
                tenant,
                () -> listener.onInventoryEvent(envelope(UUID.randomUUID().toString(), euro)));
        asTenant(
                tenant,
                () -> listener.onInventoryEvent(envelope(UUID.randomUUID().toString(), euro)));
        asTenant(
                tenant,
                () -> listener.onInventoryEvent(envelope(UUID.randomUUID().toString(), none)));

        assertThat(entryCount(tenant)).isZero();
        for (UUID receipt : List.of(euro.receiptId(), none.receiptId())) {
            assertThat(records(tenant, receipt)).singleElement().satisfies(record -> {
                assertThat(record.get("status")).isEqualTo("SUSPENDED");
                assertThat(record.get("failure_reason_code")).isEqualTo("CURRENCY_NOT_SUPPORTED");
            });
        }
    }

    @Test
    @DisplayName("AC10: a null value beside an accrual holds the whole fact SUSPENDED / VALIDATION_ERROR, once")
    void malformedFactIsHeld() {
        UUID tenant = tenant();
        GoodsReceiptRecordedV1 fact =
                fact("USD", 80_000L, line("4", 40_000L, 40_000L, "AVERAGE"), line("4", 40_000L, null, "NONE"));

        asTenant(
                tenant,
                () -> listener.onInventoryEvent(envelope(UUID.randomUUID().toString(), fact)));
        asTenant(
                tenant,
                () -> listener.onInventoryEvent(envelope(UUID.randomUUID().toString(), fact)));

        assertThat(entryCount(tenant)).isZero();
        assertThat(records(tenant, fact.receiptId())).singleElement().satisfies(record -> {
            assertThat(record.get("status")).isEqualTo("SUSPENDED");
            assertThat(record.get("failure_reason_code")).isEqualTo("VALIDATION_ERROR");
            assertThat((String) record.get("error_message")).contains("no inventoryValueMinor");
        });
    }

    @Test
    @DisplayName("AC11: every line uncosted: no entry, one SKIPPED / UNCOSTED_FACT record")
    void uncostedFactIsSkipped() {
        UUID tenant = tenant();
        GoodsReceiptRecordedV1 fact = fact("USD", 0L, line("4", 0L, null, "NONE"));

        asTenant(
                tenant,
                () -> listener.onInventoryEvent(envelope(UUID.randomUUID().toString(), fact)));

        assertThat(entryCount(tenant)).isZero();
        assertThat(records(tenant, fact.receiptId())).singleElement().satisfies(record -> {
            assertThat(record.get("status")).isEqualTo("SKIPPED");
            assertThat(record.get("failure_reason_code")).isEqualTo("UNCOSTED_FACT");
        });
    }

    @Test
    @DisplayName(
            "AC5: occurredAt in a closed period commits nothing and leaves the fact unmarked for retry and the DLQ")
    void closedPeriodCommitsNothing() {
        UUID tenant = tenant();
        GoodsReceiptRecordedV1 fact = fact("USD", 40_000L, line("4", 40_000L, 40_000L, "AVERAGE"));
        String eventId = UUID.randomUUID().toString();
        LocalDate day = LocalDate.ofInstant(occurredAt, ZoneOffset.UTC);
        closePeriod(tenant, day);

        assertThatThrownBy(() -> asTenant(tenant, () -> listener.onInventoryEvent(envelope(eventId, fact))))
                .isInstanceOf(AccountingPeriodClosedException.class);

        assertThat(entryCount(tenant)).isZero();
        assertThat(records(tenant, fact.receiptId())).isEmpty();
        assertThat(count(tenant, "idempotency_keys")).isZero();
        assertThat(asTenant(tenant, () -> processedEventRepository.existsById(eventId)))
                .isFalse();

        // Once the period is open again, the retry of the same record posts.
        new JdbcTemplate(ownerDataSource()).update("DELETE FROM accounting_period WHERE tenant_id = ?", tenant);
        asTenant(tenant, () -> listener.onInventoryEvent(envelope(eventId, fact)));
        assertThat(entryCount(tenant)).isEqualTo(1);
    }

    @Test
    @DisplayName("AC6, AC7: the goods-receipt bill posts nothing until approval; approved at 4 x 103.00 against the"
            + " receipt's 4 x 100.00, 2100 nets to 0 and 5050 holds 12.00")
    void approvedMatchedBillClearsTheAccrual() {
        UUID tenant = tenant();
        LocalDate today = today();
        GoodsReceiptRecordedV1 fact = fact("USD", 40_000L, line("4", 40_000L, 40_000L, "AVERAGE"));
        asTenant(
                tenant,
                () -> listener.onInventoryEvent(envelope(UUID.randomUUID().toString(), fact)));

        signIn("receiving.dock", "accounting:ap:pay");
        UUID vendor = UUIDv7Generator.generate();
        UUID product = UUIDv7Generator.generate();
        VendorBillResponse created =
                asTenant(tenant, () -> vendorBills.handleGoodsReceivedEvent(goodsReceived(vendor, product, today)));
        VendorBillResponse matched =
                asTenant(tenant, () -> vendorBills.handleVendorInvoiceReceivedEvent(invoice(vendor, product, today)));
        assertThat(matched.getStatus()).isEqualTo(VendorBillStatus.AWAITING_APPROVAL);
        assertThat(entryCount(tenant))
                .as("AC6: the bill's creation and match post nothing; only the receipt's accrual is booked")
                .isEqualTo(1);

        signIn("controller.cfo", CONTROLLER_GRANTS);
        asTenant(
                tenant,
                () -> approvals.approve(
                        created.getVendorBillId(),
                        new VendorBillCommands.Approve("Checked the delivery", null, null, null, null)));

        assertThat(entryCount(tenant)).isEqualTo(2);
        assertThat(net(tenant, "2100")).isEqualByComparingTo("0");
        assertThat(net(tenant, "5050")).isEqualByComparingTo("12.00");
        assertThat(net(tenant, "1300")).isEqualByComparingTo("400.00");
        assertThat(net(tenant, "2000")).isEqualByComparingTo("-412.00");
    }

    // ---- fixtures -------------------------------------------------------------------------------------------------

    /** The receipt's one held (SUSPENDED) ingestion row. */
    private static UUID heldRow(UUID tenant, UUID receiptId) {
        return new JdbcTemplate(ownerDataSource())
                .queryForObject(
                        "SELECT event_id FROM accounting_event WHERE tenant_id = ? AND event_type = ? AND"
                                + " domain_key_id = ? AND status = 'SUSPENDED'",
                        UUID.class,
                        tenant,
                        EVENT_TYPE,
                        receiptId.toString());
    }

    /** Stands in for a corrected source: the held row's stored fact now states {@code currency}. */
    private static void statePayloadCurrency(UUID tenant, UUID eventId, String currency) {
        new JdbcTemplate(ownerDataSource())
                .update(
                        "UPDATE accounting_event SET payload = jsonb_set(payload, '{currencyCode}', to_jsonb(?::text))"
                                + " WHERE tenant_id = ? AND event_id = ?",
                        currency,
                        tenant,
                        eventId);
    }

    private void reprocess(UUID tenant, UUID eventId) {
        asTenant(
                tenant,
                () -> eventIngestionService.reprocessEvent(eventId, new ReprocessEventRequest(), "ops.controller"));
    }

    private static GoodsReceiptLine line(String quantity, long accruedMinor, Long valueMinor, String costSource) {
        return new GoodsReceiptLine(
                UUIDv7Generator.generate(),
                "SKU-1",
                new BigDecimal(quantity),
                accruedMinor,
                UUIDv7Generator.generate(),
                UUIDv7Generator.generate(),
                valueMinor,
                costSource,
                UUIDv7Generator.generate());
    }

    private GoodsReceiptRecordedV1 fact(String currency, long total, GoodsReceiptLine... lines) {
        return new GoodsReceiptRecordedV1(
                UUIDv7Generator.generate(),
                "GR-41",
                UUIDv7Generator.generate(),
                null,
                total,
                occurredAt,
                Arrays.asList(lines),
                currency);
    }

    private String envelope(String eventId, GoodsReceiptRecordedV1 fact) {
        return """
                {"eventId":"%s","eventType":"%s","schemaVersion":1,"aggregateId":"%s","aggregateVersion":0,
                 "occurredAtUtc":"%s","sourceService":"pos-inventory","payload":%s}
                """.formatted(
                eventId, EVENT_TYPE, fact.purchaseOrderId(), fact.occurredAt(), objectMapper.writeValueAsString(fact));
    }

    /** A goods receipt of the vendor, put in the bound tenant's vendor copy first (S24). */
    private static GoodsReceivedEvent goodsReceived(UUID vendor, UUID product, LocalDate received) {
        return GoodsReceivedEvent.builder()
                .eventId(UUIDv7Generator.generate())
                .purchaseOrderId(UUIDv7Generator.generate())
                .vendorId(copiedVendor(vendor))
                .vendorName("Acme Parts Co")
                .receivedDate(received.atTime(9, 30))
                .lineItems(List.of(GoodsReceivedEvent.ReceivedLineItem.builder()
                        .productId(product)
                        .description("Brake pads")
                        .quantity(new BigDecimal("4"))
                        .unitPrice(new BigDecimal("100.00"))
                        .isInventoryItem(true)
                        .build()))
                .build();
    }

    private static VendorInvoiceReceivedEvent invoice(UUID vendor, UUID product, LocalDate invoiced) {
        return VendorInvoiceReceivedEvent.builder()
                .eventId(UUIDv7Generator.generate())
                .vendorId(vendor)
                .invoiceReference("INV-" + vendor.toString().substring(24))
                .invoiceDate(invoiced.atStartOfDay())
                .dueDate(invoiced.plusDays(30).atStartOfDay())
                .lineItems(List.of(VendorInvoiceReceivedEvent.InvoiceLineItem.builder()
                        .productId(product)
                        .description("Brake pads")
                        .quantity(new BigDecimal("4"))
                        .unitPrice(new BigDecimal("103.00"))
                        .build()))
                .build();
    }

    private LocalDate today() {
        // The tenants of this test take the provisioning seed's UTC accounting zone.
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private UUID tenant() {
        UUID tenant = tenantWithZone();
        tenants.add(tenant);
        provisionAccounting(tenant);
        return tenant;
    }

    private static void closePeriod(UUID tenant, LocalDate inPeriod) {
        LocalDate start = inPeriod.withDayOfMonth(1);
        new JdbcTemplate(ownerDataSource())
                .update(
                        "INSERT INTO accounting_period (tenant_id, period_id, period_code, start_date, end_date,"
                                + " status, created_at, created_by, modified_at, modified_by, version) VALUES (?, ?, ?, ?,"
                                + " ?, 'CLOSED', TIMESTAMPTZ '2026-09-01 00:00:00+00', 't', TIMESTAMPTZ '2026-09-01"
                                + " 00:00:00+00', 't', 0)",
                        tenant,
                        UUIDv7Generator.generate(),
                        start.toString().substring(0, 7),
                        start,
                        start.plusMonths(1).minusDays(1));
    }

    private static void signIn(String username, String... authorities) {
        UsernamePasswordAuthenticationToken caller = new UsernamePasswordAuthenticationToken(
                username,
                "n/a",
                Stream.of(authorities).map(SimpleGrantedAuthority::new).toList());
        caller.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, username));
        SecurityContextHolder.getContext().setAuthentication(caller);
    }

    /** "code D|C amount" for each journal line of the tenant. */
    private static List<String> lines(UUID tenant) {
        return new JdbcTemplate(ownerDataSource())
                .query(
                        "SELECT g.account_code, l.debit_amount, l.credit_amount FROM journal_entry_line l JOIN"
                                + " gl_account g ON g.tenant_id = l.tenant_id AND g.gl_account_id = l.gl_account_id"
                                + " WHERE l.tenant_id = ?",
                        (rs, n) -> {
                            BigDecimal debit = rs.getBigDecimal(2);
                            return rs.getString(1) + " "
                                    + (debit != null && debit.signum() > 0
                                            ? "D" + debit.toPlainString()
                                            : "C" + rs.getBigDecimal(3).toPlainString());
                        },
                        tenant);
    }

    /** Debits minus credits on one account, across the tenant's posted entries. */
    private static BigDecimal net(UUID tenant, String accountCode) {
        return new JdbcTemplate(ownerDataSource())
                .queryForObject(
                        "SELECT COALESCE(SUM(l.debit_amount - l.credit_amount), 0) FROM journal_entry_line l JOIN"
                                + " gl_account g ON g.tenant_id = l.tenant_id AND g.gl_account_id = l.gl_account_id"
                                + " WHERE l.tenant_id = ? AND g.account_code = ?",
                        BigDecimal.class,
                        tenant,
                        accountCode);
    }

    private static Map<String, Object> onlyEntry(UUID tenant) {
        return new JdbcTemplate(ownerDataSource())
                .queryForMap(
                        "SELECT journal_entry_id, source_event_type, source_event_id, status, transaction_date FROM"
                                + " journal_entry WHERE tenant_id = ?",
                        tenant);
    }

    private static int entryCount(UUID tenant) {
        return count(tenant, "journal_entry");
    }

    private static int count(UUID tenant, String table) {
        return new JdbcTemplate(ownerDataSource())
                .queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = ?", Integer.class, tenant);
    }

    private static List<Map<String, Object>> records(UUID tenant, UUID receiptId) {
        return new JdbcTemplate(ownerDataSource())
                .queryForList(
                        "SELECT status, idempotency_outcome, journal_entry_id, failure_reason_code, error_message,"
                                + " payload::text AS payload FROM accounting_event WHERE tenant_id = ? AND event_type = ?"
                                + " AND domain_key_id = ?",
                        tenant,
                        EVENT_TYPE,
                        receiptId.toString());
    }
}
