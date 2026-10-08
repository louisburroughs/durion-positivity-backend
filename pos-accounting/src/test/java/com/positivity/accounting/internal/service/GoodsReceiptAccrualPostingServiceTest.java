package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.JournalEntryCreateRequest;
import com.positivity.accounting.internal.dto.JournalEntryResponse;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.exception.AccountingPeriodClosedException;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.accounting.internal.service.GoodsReceiptAccrualPostingService.Assessment;
import com.positivity.accounting.internal.service.GoodsReceiptAccrualPostingService.Leg;
import com.positivity.domainevents.inventory.GoodsReceiptLine;
import com.positivity.domainevents.inventory.GoodsReceiptRecordedV1;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The goods-receipt accrual (CAP:550 S41, #2602; AW38 as amended on #2598): the entry's legs, the unpriced line on its
 * own 5050 credit, the currency check before the line rules, the malformed and uncosted outcomes, minor units by the
 * currency's exponent, and the once-only posting.
 */
@DisplayName("GoodsReceiptAccrualPostingService (S41 #2602, AW38)")
class GoodsReceiptAccrualPostingServiceTest {

    static final UUID RECEIPT = UUID.fromString("019a0000-0000-7000-8000-000000000a01");
    static final UUID ORDER = UUID.fromString("019a0000-0000-7000-8000-000000000a02");
    static final Instant OCCURRED = Instant.parse("2026-10-08T14:30:00Z");

    private final IdempotencyService idempotency = mock(IdempotencyService.class);
    private final GLMappingResolver mappings = mock(GLMappingResolver.class);
    private final JournalEntryService journalEntries = mock(JournalEntryService.class);
    private final JournalEntryRepository entries = mock(JournalEntryRepository.class);
    private final Map<String, UUID> accounts = Map.of(
            GoodsReceiptAccrualPostingService.INVENTORY_ASSET_KEY, UUID.randomUUID(),
            GoodsReceiptAccrualPostingService.GOODS_RECEIVED_NOT_BILLED_KEY, UUID.randomUUID(),
            GoodsReceiptAccrualPostingService.PURCHASE_PRICE_DIFFERENCE_KEY, UUID.randomUUID());

    private GoodsReceiptAccrualPostingService service;

    @BeforeEach
    void setUp() {
        service = new GoodsReceiptAccrualPostingService(
                TestZoneResolvers.utc(Clock.fixed(OCCURRED, ZoneOffset.UTC)),
                idempotency,
                mappings,
                journalEntries,
                entries,
                new LedgerCurrency("USD"));
        accounts.forEach((key, account) -> when(mappings.resolveGLAccount(
                        eq(GoodsReceiptAccrualPostingService.POSTING_CATEGORY), eq(key), any(LocalDateTime.class)))
                .thenReturn(account));
    }

    @Nested
    @DisplayName("the entry")
    class TheEntry {

        @Test
        @DisplayName("AC1: 4 x 100.00 accrued and valued 400.00 is Dr 1300 400.00 / Cr 2100 400.00")
        void receiptAtItsAccrual() {
            assertThat(legs(fact("USD", 40_000L, line("4", 40_000L, 40_000L, "AVERAGE"))))
                    .containsExactly("INVENTORY_ASSET D400.00", "GOODS_RECEIVED_NOT_BILLED C400.00");
        }

        @Test
        @DisplayName("AC2: STANDARD value 380.00 against 400.00 accrued is Dr 1300 380.00 / Cr 2100 400.00 / Dr 5050"
                + " 20.00")
        void standardVariance() {
            assertThat(legs(fact("USD", 40_000L, line("4", 40_000L, 38_000L, "STANDARD"))))
                    .containsExactly(
                            "INVENTORY_ASSET D380.00",
                            "GOODS_RECEIVED_NOT_BILLED C400.00",
                            "PURCHASE_PRICE_DIFFERENCE D20.00");
        }

        @Test
        @DisplayName(
                "AC9: an unpriced AVERAGE line valued 150.00 is Dr 1300 / Cr 5050 150.00, its credit on its own line"
                        + " naming the line as unpriced")
        void unpricedLine() {
            GoodsReceiptLine priced = line("4", 40_000L, 40_000L, "AVERAGE");
            GoodsReceiptLine unpriced = new GoodsReceiptLine(
                    null,
                    "SKU-9",
                    new BigDecimal("1"),
                    0L,
                    UUID.randomUUID(),
                    null,
                    15_000L,
                    "AVERAGE",
                    UUID.randomUUID());

            List<Leg> legs = postable(fact("USD", 40_000L, priced, unpriced));

            assertThat(render(legs))
                    .containsExactly(
                            "INVENTORY_ASSET D400.00",
                            "GOODS_RECEIVED_NOT_BILLED C400.00",
                            "INVENTORY_ASSET D150.00",
                            "PURCHASE_PRICE_DIFFERENCE C150.00");
            assertThat(legs.getLast().description())
                    .startsWith("Unpriced receipt line")
                    .contains(unpriced.receiptLineId().toString())
                    .contains("sku SKU-9")
                    .contains("costSource AVERAGE")
                    .contains(unpriced.ledgerEntryId().toString());
        }

        @Test
        @DisplayName("AC12: 2.5 at 1.01 accrued and valued 2.53 is Dr 1300 2.53 / Cr 2100 2.53, nothing to 5050")
        void halfCentLine() {
            assertThat(legs(fact("USD", 253L, line("2.5", 253L, 253L, "AVERAGE"))))
                    .containsExactly("INVENTORY_ASSET D2.53", "GOODS_RECEIVED_NOT_BILLED C2.53");
        }

        @Test
        @DisplayName("AC11: one uncosted line among costed ones contributes nothing")
        void uncostedLineAmongCostedOnes() {
            GoodsReceiptLine uncosted = new GoodsReceiptLine(
                    UUID.randomUUID(),
                    "SKU-2",
                    new BigDecimal("3"),
                    0L,
                    UUID.randomUUID(),
                    null,
                    null,
                    "NONE",
                    UUID.randomUUID());

            assertThat(legs(fact("USD", 40_000L, line("4", 40_000L, 40_000L, "AVERAGE"), uncosted)))
                    .containsExactly("INVENTORY_ASSET D400.00", "GOODS_RECEIVED_NOT_BILLED C400.00");
        }

        @Test
        @DisplayName("lines with zero quantity are ignored")
        void zeroQuantityLineIgnored() {
            GoodsReceiptLine empty = new GoodsReceiptLine(
                    UUID.randomUUID(), "SKU-3", BigDecimal.ZERO, 0L, null, null, null, "NONE", null);

            assertThat(legs(fact("USD", 40_000L, line("4", 40_000L, 40_000L, "AVERAGE"), empty)))
                    .containsExactly("INVENTORY_ASSET D400.00", "GOODS_RECEIVED_NOT_BILLED C400.00");
        }

        @Test
        @DisplayName("minor units convert by the currency's exponent only: JPY has none")
        void exponentOnly() {
            service = new GoodsReceiptAccrualPostingService(
                    TestZoneResolvers.utc(Clock.systemUTC()),
                    idempotency,
                    mappings,
                    journalEntries,
                    entries,
                    new LedgerCurrency("JPY"));

            assertThat(legs(fact("JPY", 1_005L, line("1", 1_005L, 1_000L, "STANDARD"))))
                    .containsExactly(
                            "INVENTORY_ASSET D1000", "GOODS_RECEIVED_NOT_BILLED C1005", "PURCHASE_PRICE_DIFFERENCE D5");
        }

        @Test
        @DisplayName("every journal line carries costSource and ledgerEntryId for tie-out")
        void traceability() {
            GoodsReceiptLine line = line("4", 40_000L, 38_000L, "STANDARD");

            assertThat(postable(fact("USD", 40_000L, line)))
                    .allSatisfy(leg -> assertThat(leg.description())
                            .contains("costSource STANDARD")
                            .contains(line.ledgerEntryId().toString())
                            .contains(line.receiptLineId().toString())
                            .contains("PO line " + line.poLineId()));
        }
    }

    @Nested
    @DisplayName("the currency check, then the line rules")
    class Rules {

        @Test
        @DisplayName("AC4: no currencyCode is CURRENCY_NOT_SUPPORTED, before any line rule")
        void noCurrency() {
            // Also malformed (no receiptLineId): the currency is checked first.
            GoodsReceiptLine malformed =
                    new GoodsReceiptLine(null, "SKU-1", BigDecimal.ONE, 100L, null, null, null, "NONE", null);

            assertThat(service.assess(fact(null, 100L, malformed)))
                    .isInstanceOfSatisfying(
                            Assessment.CurrencyNotSupported.class,
                            held -> assertThat(held.detail()).contains("states no currency"));
        }

        @Test
        @DisplayName("AC4: EUR for a USD ledger is CURRENCY_NOT_SUPPORTED")
        void foreignCurrency() {
            assertThat(service.assess(fact("EUR", 40_000L, line("4", 40_000L, null, "NONE"))))
                    .isInstanceOfSatisfying(
                            Assessment.CurrencyNotSupported.class,
                            held -> assertThat(held.detail()).contains("EUR").contains("USD"));
        }

        @Test
        @DisplayName("AC10: a null value beside an accrual of 400.00 holds the whole fact")
        void nullValueBesideAccrual() {
            assertThat(service.assess(fact(
                            "USD", 80_000L, line("4", 40_000L, 40_000L, "AVERAGE"), line("4", 40_000L, null, "NONE"))))
                    .isInstanceOfSatisfying(
                            Assessment.Malformed.class,
                            held -> assertThat(held.detail())
                                    .contains("no inventoryValueMinor beside an accrual of 40000"));
        }

        @Test
        @DisplayName("AC10: a received line without receiptLineId holds the whole fact")
        void missingReceiptLineId() {
            GoodsReceiptLine noId = new GoodsReceiptLine(
                    UUID.randomUUID(),
                    "SKU-1",
                    new BigDecimal("4"),
                    40_000L,
                    null,
                    null,
                    40_000L,
                    "AVERAGE",
                    UUID.randomUUID());

            assertThat(service.assess(fact("USD", 40_000L, noId)))
                    .isInstanceOfSatisfying(
                            Assessment.Malformed.class,
                            held -> assertThat(held.detail()).contains("without receiptLineId"));
        }

        @Test
        @DisplayName("AC10: line accruals that do not sum to the total hold the whole fact")
        void linesDoNotSumToTotal() {
            assertThat(service.assess(fact("USD", 40_001L, line("4", 40_000L, 40_000L, "AVERAGE"))))
                    .isInstanceOfSatisfying(
                            Assessment.Malformed.class,
                            held -> assertThat(held.detail())
                                    .contains("sum to 40000")
                                    .contains("40001"));
        }

        @Test
        @DisplayName("AC11: every received line uncosted is UNCOSTED")
        void everyLineUncosted() {
            assertThat(service.assess(fact("USD", 0L, line("4", 0L, null, "NONE"), line("2", 0L, null, "NONE"))))
                    .isInstanceOf(Assessment.Uncosted.class);
        }

        @Test
        @DisplayName("nothing accrued and nothing valued posts nothing")
        void nothingToPost() {
            assertThat(service.assess(fact("USD", 0L, line("4", 0L, 0L, "AVERAGE"))))
                    .isInstanceOf(Assessment.NothingToPost.class);
            assertThat(service.assess(fact("USD", 0L))).isInstanceOf(Assessment.NothingToPost.class);
        }

        @Test
        @DisplayName("ledgerEntryId is never required: a line without one still posts")
        void ledgerEntryIdNeverHolds() {
            GoodsReceiptLine noLedgerEntry = new GoodsReceiptLine(
                    UUID.randomUUID(),
                    "SKU-1",
                    new BigDecimal("4"),
                    40_000L,
                    UUID.randomUUID(),
                    null,
                    40_000L,
                    "AVERAGE",
                    null);

            assertThat(service.assess(fact("USD", 40_000L, noLedgerEntry))).isInstanceOf(Assessment.Postable.class);
        }
    }

    @Nested
    @DisplayName("posting")
    class Posting {

        @Test
        @DisplayName("AC1: one posted entry dated occurredAt, source GOODS_RECEIPT_ACCRUAL:<receiptId>, key registered")
        void postsOnce() {
            UUID entryId = UUID.randomUUID();
            when(entries.findBySourceEvent(any())).thenReturn(List.of());
            when(journalEntries.createJournalEntry(any()))
                    .thenReturn(JournalEntryResponse.builder()
                            .journalEntryId(entryId)
                            .build());
            when(journalEntries.postJournalEntry(eq(entryId), isNull()))
                    .thenReturn(JournalEntryResponse.builder()
                            .journalEntryId(entryId)
                            .build());

            UUID posted = service.postAccrual(fact("USD", 40_000L, line("4", 40_000L, 40_000L, "AVERAGE")));

            assertThat(posted).isEqualTo(entryId);
            ArgumentCaptor<JournalEntryCreateRequest> request =
                    ArgumentCaptor.forClass(JournalEntryCreateRequest.class);
            verify(journalEntries).createJournalEntry(request.capture());
            assertThat(request.getValue().getTransactionDate()).isEqualTo(LocalDateTime.of(2026, 10, 8, 14, 30));
            assertThat(request.getValue().getSourceEventType()).isEqualTo("GOODS_RECEIPT_ACCRUAL");
            assertThat(request.getValue().getSourceEventId())
                    .isEqualTo(UUID.nameUUIDFromBytes(("GOODS_RECEIPT_ACCRUAL:" + RECEIPT).getBytes()));
            assertThat(request.getValue().getLines())
                    .extracting(JournalEntryCreateRequest.JournalEntryLineRequest::getGlAccountId)
                    .containsExactly(
                            accounts.get(GoodsReceiptAccrualPostingService.INVENTORY_ASSET_KEY),
                            accounts.get(GoodsReceiptAccrualPostingService.GOODS_RECEIVED_NOT_BILLED_KEY));
            verify(idempotency).registerKey("GOODS_RECEIPT_ACCRUAL:" + RECEIPT, entryId);
        }

        @Test
        @DisplayName("AC3: a receipt already posted (key or entry) posts nothing")
        void alreadyPosted() {
            when(idempotency.isKeyProcessed("GOODS_RECEIPT_ACCRUAL:" + RECEIPT)).thenReturn(true);
            assertThat(service.postAccrual(fact("USD", 40_000L, line("4", 40_000L, 40_000L, "AVERAGE"))))
                    .isNull();

            when(idempotency.isKeyProcessed(any())).thenReturn(false);
            when(entries.findBySourceEvent(GoodsReceiptAccrualPostingService.toSourceEventId(RECEIPT)))
                    .thenReturn(List.of(new JournalEntry()));
            assertThat(service.postAccrual(fact("USD", 40_000L, line("4", 40_000L, 40_000L, "AVERAGE"))))
                    .isNull();
            verify(journalEntries, never()).createJournalEntry(any());
        }

        @Test
        @DisplayName("AC5: a closed period propagates and registers no key")
        void closedPeriodPropagates() {
            UUID entryId = UUID.randomUUID();
            when(entries.findBySourceEvent(any())).thenReturn(List.of());
            when(journalEntries.createJournalEntry(any()))
                    .thenReturn(JournalEntryResponse.builder()
                            .journalEntryId(entryId)
                            .build());
            when(journalEntries.postJournalEntry(eq(entryId), isNull()))
                    .thenThrow(new AccountingPeriodClosedException("2026-10", "2026-10 is CLOSED"));

            assertThatThrownBy(() -> service.postAccrual(fact("USD", 40_000L, line("4", 40_000L, 40_000L, "AVERAGE"))))
                    .isInstanceOf(AccountingPeriodClosedException.class);
            verify(idempotency, never()).registerKey(any(), any());
        }

        @Test
        @DisplayName("an unpostable fact never reaches the ledger")
        void unpostableRefused() {
            assertThatThrownBy(() -> service.postAccrual(fact("EUR", 0L, line("4", 0L, null, "NONE"))))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // ---- fixtures ---------------------------------------------------------------------------------------------------

    private List<String> legs(GoodsReceiptRecordedV1 fact) {
        return render(postable(fact));
    }

    private List<Leg> postable(GoodsReceiptRecordedV1 fact) {
        Assessment assessment = service.assess(fact);
        assertThat(assessment).isInstanceOf(Assessment.Postable.class);
        return ((Assessment.Postable) assessment).legs();
    }

    private static List<String> render(List<Leg> legs) {
        return legs.stream()
                .map(leg -> leg.mappingKey() + " " + (leg.signedAmount().signum() > 0 ? "D" : "C")
                        + leg.signedAmount().abs().toPlainString())
                .collect(Collectors.toList());
    }

    static GoodsReceiptLine line(String quantity, long accruedMinor, Long valueMinor, String costSource) {
        return new GoodsReceiptLine(
                UUID.randomUUID(),
                "SKU-1",
                new BigDecimal(quantity),
                accruedMinor,
                UUID.randomUUID(),
                UUID.randomUUID(),
                valueMinor,
                costSource,
                UUID.randomUUID());
    }

    static GoodsReceiptRecordedV1 fact(String currency, long total, GoodsReceiptLine... lines) {
        return new GoodsReceiptRecordedV1(
                RECEIPT, "GR-41", ORDER, null, total, OCCURRED, Arrays.asList(lines), currency);
    }
}
