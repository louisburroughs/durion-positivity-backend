package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.JournalEntryCreateRequest;
import com.positivity.accounting.internal.dto.JournalEntryResponse;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.entity.VendorBillGlPosting;
import com.positivity.accounting.internal.entity.VendorBillLine;
import com.positivity.accounting.internal.enums.VendorBillDebitClass;
import com.positivity.accounting.internal.enums.VendorBillDifferenceClass;
import com.positivity.accounting.internal.enums.VendorBillPostingDateRule;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.event.LedgerReversalApplied;
import com.positivity.accounting.internal.exception.AccountingPeriodHardLockedException;
import com.positivity.accounting.internal.exception.GLAccountNotActiveException;
import com.positivity.accounting.internal.exception.GLMappingNotConfiguredException;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.repository.VendorBillGlPostingRepository;
import com.positivity.accounting.internal.repository.VendorBillLineRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The entry a vendor bill posts at approval (CAP:550 S12, #2509; AW39, AW42): the legs by class, the posting date
 * rule, the durable once-only guard and the void's reversal. The full chain on Postgres is
 * VendorBillApprovalPostgresIT's.
 */
@DisplayName("VendorBillPostingService: the entry at approval (#2509, AW39, AW42)")
class VendorBillPostingServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-03T15:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 3);
    private static final UUID BILL_ID = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a01");

    private static final VendorBillPostingService.Classification NONE =
            new VendorBillPostingService.Classification(null, null);

    private static VendorBill bill(String total) {
        VendorBill bill = new VendorBill(BILL_ID);
        bill.setVendorId(UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a02"));
        bill.setVendorName("Acme Parts Co");
        bill.setBillNumber("INV-1");
        bill.setBillDate(LocalDateTime.of(2026, 10, 1, 0, 0));
        bill.setTotalAmount(new BigDecimal(total));
        bill.setStatus(VendorBillStatus.AWAITING_APPROVAL);
        return bill;
    }

    private static VendorBillLine line(
            int number, boolean stocked, String received, String receivedPrice, String billed, String billedPrice) {
        VendorBillLine line = new VendorBillLine();
        line.setLineNumber(number);
        line.setProductId(UUID.nameUUIDFromBytes(("product-" + number).getBytes()));
        line.setInventoryItem(stocked);
        line.setQuantity(new BigDecimal(received));
        line.setUnitPrice(new BigDecimal(receivedPrice));
        line.setBilledQuantity(billed == null ? null : new BigDecimal(billed));
        line.setBilledUnitPrice(billedPrice == null ? null : new BigDecimal(billedPrice));
        return line;
    }

    private static List<String> legs(
            VendorBill bill, List<VendorBillLine> lines, VendorBillPostingService.Classification c) {
        return legs(bill, lines, c, null);
    }

    private static List<String> legs(
            VendorBill bill,
            List<VendorBillLine> lines,
            VendorBillPostingService.Classification c,
            VendorBillPostingService.Difference difference) {
        List<String> out = new ArrayList<>();
        for (VendorBillPostingService.Leg leg : VendorBillPostingService.legs(bill, lines, c, difference)) {
            BigDecimal amount = leg.signedAmount();
            out.add(leg.mappingKey()
                    + (amount.signum() > 0 ? " Dr " : " Cr ")
                    + amount.abs().toPlainString());
        }
        return out;
    }

    @Nested
    @DisplayName("The legs by class (AW39)")
    class Legs {

        @Test
        @DisplayName("AC13(a): a bill matched at 4 x 103.00 against a 4 x 100.00 receipt -> Dr 2100 400.00 /"
                + " Dr 5050 12.00 / Cr 2000 412.00")
        void receiptMatchedLineClearsAccrualAtTheReceivedPrice() {
            assertThat(legs(bill("412.00"), List.of(line(1, true, "4", "100.00", "4", "103.00")), NONE))
                    .containsExactly(
                            "GOODS_RECEIVED_NOT_BILLED Dr 400.00",
                            "PURCHASE_PRICE_DIFFERENCE Dr 12.00",
                            "ACCOUNTS_PAYABLE Cr 412.00");
        }

        @Test
        @DisplayName("Ruling Q2 AC3: 5 billed against 4 received -> Dr 2100 500.00 / Cr 2000 500.00")
        void quantityDifferenceStaysOpenIn2100() {
            assertThat(legs(bill("500.00"), List.of(line(1, true, "4", "100.00", "5", "100.00")), NONE))
                    .containsExactly("GOODS_RECEIVED_NOT_BILLED Dr 500.00", "ACCOUNTS_PAYABLE Cr 500.00");
        }

        @Test
        @DisplayName("A billed price under the received price credits 5050")
        void lowerBilledPriceCreditsTheDifference() {
            assertThat(legs(bill("392.00"), List.of(line(1, true, "4", "100.00", "4", "98.00")), NONE))
                    .containsExactly(
                            "GOODS_RECEIVED_NOT_BILLED Dr 400.00",
                            "PURCHASE_PRICE_DIFFERENCE Cr 8.00",
                            "ACCOUNTS_PAYABLE Cr 392.00");
        }

        @Test
        @DisplayName("A received line the invoice did not bill posts nothing; an invoice line with no receipt is GOODS")
        void unbilledAndUnreceivedLines() {
            List<VendorBillLine> lines =
                    List.of(line(1, true, "4", "100.00", "0", "100.00"), line(2, true, "0", "25.00", "2", "25.00"));
            assertThat(legs(bill("50.00"), lines, NONE))
                    .containsExactly("GOODS_RECEIVED_NOT_BILLED Dr 50.00", "ACCOUNTS_PAYABLE Cr 50.00");
        }

        @Test
        @DisplayName("L-a: a goods-receipt bill whose lines post more than 0.05 away from its total is 422"
                + " AP_BILL_TOTALS_UNRECONCILED, never absorbed; within 0.05 it is rounding")
        void lineBillPlugIsCapped() {
            List<VendorBillLine> lines = List.of(line(1, true, "4", "100.00", "4", "100.00"));
            assertThatThrownBy(() -> VendorBillPostingService.legs(bill("400.06"), lines, NONE, null))
                    .isInstanceOfSatisfying(
                            VendorBillException.class,
                            e -> assertThat(e.getCode())
                                    .isEqualTo(VendorBillException.Code.AP_BILL_TOTALS_UNRECONCILED));
            assertThat(VendorBillPostingService.entry(bill("400.05"), lines, NONE, null)
                            .roundingAdjustment())
                    .isEqualByComparingTo("0.05");
        }

        @Test
        @DisplayName("A bill never matched posts its received lines as billed")
        void unmatchedBillPostsReceivedAsBilled() {
            assertThat(legs(bill("400.00"), List.of(line(1, true, "4", "100.00", null, null)), NONE))
                    .containsExactly("GOODS_RECEIVED_NOT_BILLED Dr 400.00", "ACCOUNTS_PAYABLE Cr 400.00");
        }

        @Test
        @DisplayName("A non-stock line posts to the approver's expense key; without one it is 422 AP_BILL_UNCLASSIFIED")
        void nonStockLineNeedsAnExpenseKey() {
            List<VendorBillLine> lines =
                    List.of(line(1, true, "4", "100.00", "4", "100.00"), line(2, false, "1", "50.00", "1", "50.00"));
            assertThat(legs(
                            bill("450.00"),
                            lines,
                            new VendorBillPostingService.Classification(null, "EXPENSE_POSTAGE_SHIPPING")))
                    .containsExactly(
                            "GOODS_RECEIVED_NOT_BILLED Dr 400.00",
                            "EXPENSE_POSTAGE_SHIPPING Dr 50.00",
                            "ACCOUNTS_PAYABLE Cr 450.00");
            assertThatThrownBy(() -> VendorBillPostingService.legs(bill("450.00"), lines, NONE, null))
                    .isInstanceOfSatisfying(
                            VendorBillException.class,
                            e -> assertThat(e.getCode()).isEqualTo(VendorBillException.Code.AP_BILL_UNCLASSIFIED));
        }

        @Test
        @DisplayName("Ruling Q2 AC4: an EDI GOODS bill, net 1,000.00, US tax 70.00 -> Dr 2100 1,000.00 / Dr 5050 70.00"
                + " / Cr 2000 1,070.00")
        void ediGoodsBillTaxIntoPriceDifference() {
            VendorBill edi = bill("1070.00");
            edi.setNetAmount(new BigDecimal("1000.00"));
            edi.setTaxAmount(new BigDecimal("70.00"));
            assertThat(legs(
                            edi,
                            List.of(),
                            new VendorBillPostingService.Classification(VendorBillDebitClass.GOODS, null)))
                    .containsExactly(
                            "GOODS_RECEIVED_NOT_BILLED Dr 1000.00",
                            "PURCHASE_PRICE_DIFFERENCE Dr 70.00",
                            "ACCOUNTS_PAYABLE Cr 1070.00");
        }

        @Test
        @DisplayName("AC13(e): an EDI EXPENSE bill, net 200.00, tax 14.00 -> Dr EXPENSE_SHOP_SUPPLIES 214.00 /"
                + " Cr 2000 214.00")
        void ediExpenseBillTaxIntoTheExpense() {
            VendorBill edi = bill("214.00");
            edi.setNetAmount(new BigDecimal("200.00"));
            edi.setTaxAmount(new BigDecimal("14.00"));
            assertThat(legs(
                            edi,
                            List.of(),
                            new VendorBillPostingService.Classification(
                                    VendorBillDebitClass.EXPENSE, "EXPENSE_SHOP_SUPPLIES")))
                    .containsExactly("EXPENSE_SHOP_SUPPLIES Dr 214.00", "ACCOUNTS_PAYABLE Cr 214.00");
        }

        @Test
        @DisplayName("Ruling Q2 AC7: a bill without lines, class or vendor default is 422 AP_BILL_UNCLASSIFIED")
        void headerBillWithoutClassIsUnclassified() {
            assertThatThrownBy(() -> VendorBillPostingService.legs(bill("214.00"), List.of(), NONE, null))
                    .isInstanceOfSatisfying(
                            VendorBillException.class,
                            e -> assertThat(e.getCode()).isEqualTo(VendorBillException.Code.AP_BILL_UNCLASSIFIED));
        }

        @Test
        @DisplayName("Ruling Q2 AC6: a credit note of -50.00, class EXPENSE -> Dr 2000 50.00 / Cr the expense 50.00")
        void creditNoteMirrors() {
            assertThat(legs(
                            bill("-50.00"),
                            List.of(),
                            new VendorBillPostingService.Classification(
                                    VendorBillDebitClass.EXPENSE, "EXPENSE_SHOP_SUPPLIES")))
                    .containsExactly("EXPENSE_SHOP_SUPPLIES Cr 50.00", "ACCOUNTS_PAYABLE Dr 50.00");
            assertThat(legs(
                            bill("-50.00"),
                            List.of(),
                            new VendorBillPostingService.Classification(VendorBillDebitClass.PRICE_ALLOWANCE, null)))
                    .containsExactly("PURCHASE_PRICE_DIFFERENCE Cr 50.00", "ACCOUNTS_PAYABLE Dr 50.00");
        }

        @Test
        @DisplayName("A class the document cannot take is 400 VALIDATION_ERROR")
        void classTheDocumentCannotTake() {
            assertThatThrownBy(() -> VendorBillPostingService.legs(
                            bill("-50.00"),
                            List.of(),
                            new VendorBillPostingService.Classification(VendorBillDebitClass.GOODS, null),
                            null))
                    .isInstanceOfSatisfying(
                            VendorBillException.class,
                            e -> assertThat(e.getCode()).isEqualTo(VendorBillException.Code.VALIDATION_ERROR));
            assertThatThrownBy(() -> VendorBillPostingService.legs(
                            bill("50.00"),
                            List.of(),
                            new VendorBillPostingService.Classification(VendorBillDebitClass.PRICE_ALLOWANCE, null),
                            null))
                    .isInstanceOf(VendorBillException.class);
        }

        @Test
        @DisplayName("L7: a bill totalling 0.00 has nothing to post: 422 AP_BILL_ZERO_TOTAL")
        void zeroBillHasNothingToPost() {
            assertThatThrownBy(() -> VendorBillPostingService.legs(bill("0.00"), List.of(), NONE, null))
                    .isInstanceOfSatisfying(
                            VendorBillException.class,
                            e -> assertThat(e.getCode()).isEqualTo(VendorBillException.Code.AP_BILL_ZERO_TOTAL));
        }

        @Test
        @DisplayName("US tax on a mixed bill is prorated by line net, the residual cent on the largest line, into the"
                + " expense of an expense line and 5050 for goods")
        void headerTaxProratedByLineNet() {
            VendorBill mixed = bill("110.01");
            mixed.setTaxAmount(new BigDecimal("10.01"));
            List<VendorBillLine> lines =
                    List.of(line(1, true, "1", "66.67", "1", "66.67"), line(2, false, "1", "33.33", "1", "33.33"));
            // 10.01 x 66.67 / 100 = 6.674 -> 6.67; 10.01 x 33.33 / 100 = 3.336 -> 3.34; residual 0.00.
            assertThat(legs(mixed, lines, new VendorBillPostingService.Classification(null, "EXPENSE_SMALL_TOOLS")))
                    .containsExactly(
                            "GOODS_RECEIVED_NOT_BILLED Dr 66.67",
                            "PURCHASE_PRICE_DIFFERENCE Dr 6.67",
                            "EXPENSE_SMALL_TOOLS Dr 36.67",
                            "ACCOUNTS_PAYABLE Cr 110.01");
        }

        @Test
        @DisplayName("prorate gives the residual cent to the largest weight and always sums to the total")
        void prorateResidualCent() {
            List<BigDecimal> shares = VendorBillPostingService.prorate(
                    new BigDecimal("0.10"),
                    List.of(new BigDecimal("1.00"), new BigDecimal("1.00"), new BigDecimal("1.00")));
            assertThat(shares)
                    .usingElementComparator(BigDecimal::compareTo)
                    .containsExactly(new BigDecimal("0.04"), new BigDecimal("0.03"), new BigDecimal("0.03"));
        }
    }

    private static VendorBill ediBill(String gross, String net, String tax, int statedLines) {
        VendorBill edi = bill(gross);
        edi.setNetAmount(new BigDecimal(net));
        edi.setTaxAmount(new BigDecimal(tax));
        edi.setStatedLineCount(statedLines);
        return edi;
    }

    private static final VendorBillPostingService.Classification GOODS =
            new VendorBillPostingService.Classification(VendorBillDebitClass.GOODS, null);

    @Nested
    @DisplayName("The vendor's own totals: gross vs net + tax (AW47)")
    class Totals {

        @Test
        @DisplayName("AC(a): gross 1,085.00 / net 1,000.00 / tax 70.00 without a difference is 422"
                + " AP_BILL_TOTALS_UNRECONCILED")
        void unreconciledWithoutDifferenceIsRefused() {
            assertThatThrownBy(() -> VendorBillPostingService.legs(
                            ediBill("1085.00", "1000.00", "70.00", 1), List.of(), GOODS, null))
                    .isInstanceOfSatisfying(VendorBillException.class, e -> {
                        assertThat(e.getCode()).isEqualTo(VendorBillException.Code.AP_BILL_TOTALS_UNRECONCILED);
                        assertThat(e.getMessage()).contains("net 1000.00 + tax 70.00 ≠ total 1085.00");
                    });
        }

        @Test
        @DisplayName("AC(b): the same with FREIGHT -> Dr 2100 1,000.00 / Dr 5050 70.00 / Dr 5060 15.00 / Cr 2000"
                + " 1,085.00")
        void freightDifferencePostsToFreightIn() {
            VendorBillPostingService.Entry entry = VendorBillPostingService.entry(
                    ediBill("1085.00", "1000.00", "70.00", 1),
                    List.of(),
                    GOODS,
                    new VendorBillPostingService.Difference(VendorBillDifferenceClass.FREIGHT, null));

            assertThat(entry.legs())
                    .extracting(
                            leg -> leg.mappingKey() + " " + leg.signedAmount().toPlainString())
                    .containsExactly(
                            "GOODS_RECEIVED_NOT_BILLED 1000.00",
                            "PURCHASE_PRICE_DIFFERENCE 70.00",
                            "FREIGHT_IN 15.00",
                            "ACCOUNTS_PAYABLE -1085.00");
            assertThat(entry.roundingAdjustment()).isEqualByComparingTo("0.00");
            assertThat(entry.differenceAmount()).isEqualByComparingTo("15.00");
        }

        @Test
        @DisplayName("AC(c): gross 1,070.01 -> Dr 2100 1,000.01 / Dr 5050 70.00 / Cr 2000 1,070.01, roundingAdjustment"
                + " 0.01")
        void roundingWithinToleranceGoesOnTheLargestDebit() {
            VendorBillPostingService.Entry entry =
                    VendorBillPostingService.entry(ediBill("1070.01", "1000.00", "70.00", 1), List.of(), GOODS, null);

            assertThat(entry.legs())
                    .extracting(
                            leg -> leg.mappingKey() + " " + leg.signedAmount().toPlainString())
                    .containsExactly(
                            "GOODS_RECEIVED_NOT_BILLED 1000.01",
                            "PURCHASE_PRICE_DIFFERENCE 70.00",
                            "ACCOUNTS_PAYABLE -1070.01");
            assertThat(entry.roundingAdjustment()).isEqualByComparingTo("0.01");
            assertThat(entry.difference()).isNull();
        }

        @Test
        @DisplayName("AC(d): gross 1,060.00 with PRICE_DIFFERENCE -> Dr 2100 1,000.00 / Dr 5050 60.00 / Cr 2000"
                + " 1,060.00")
        void negativeDifferenceCreditsPriceDifference() {
            assertThat(legs(
                            ediBill("1060.00", "1000.00", "70.00", 1),
                            List.of(),
                            GOODS,
                            new VendorBillPostingService.Difference(VendorBillDifferenceClass.PRICE_DIFFERENCE, null)))
                    .containsExactly(
                            "GOODS_RECEIVED_NOT_BILLED Dr 1000.00",
                            "PURCHASE_PRICE_DIFFERENCE Dr 60.00",
                            "ACCOUNTS_PAYABLE Cr 1060.00");
        }

        @Test
        @DisplayName("An EXPENSE difference posts to its own key; GOODS adds to 2100")
        void expenseAndGoodsDifferences() {
            assertThat(legs(
                            ediBill("1085.00", "1000.00", "70.00", 1),
                            List.of(),
                            GOODS,
                            new VendorBillPostingService.Difference(
                                    VendorBillDifferenceClass.EXPENSE, "EXPENSE_POSTAGE_SHIPPING")))
                    .containsExactly(
                            "GOODS_RECEIVED_NOT_BILLED Dr 1000.00",
                            "PURCHASE_PRICE_DIFFERENCE Dr 70.00",
                            "EXPENSE_POSTAGE_SHIPPING Dr 15.00",
                            "ACCOUNTS_PAYABLE Cr 1085.00");
            assertThat(legs(
                            ediBill("1085.00", "1000.00", "70.00", 1),
                            List.of(),
                            GOODS,
                            new VendorBillPostingService.Difference(VendorBillDifferenceClass.GOODS, null)))
                    .containsExactly(
                            "GOODS_RECEIVED_NOT_BILLED Dr 1015.00",
                            "PURCHASE_PRICE_DIFFERENCE Dr 70.00",
                            "ACCOUNTS_PAYABLE Cr 1085.00");
        }

        @Test
        @DisplayName("AW47 ruling: gross 1,085.00 / net 1,000.00 / no tax with FREIGHT -> Dr 2100 1,000.00 / Dr 5060"
                + " 85.00 / Cr 2000 1,085.00; no Dr 5050")
        void missingTaxPostsNoPriceDifference() {
            assertThat(legs(
                            ediBill("1085.00", "1000.00", "0.00", 1),
                            List.of(),
                            GOODS,
                            new VendorBillPostingService.Difference(VendorBillDifferenceClass.FREIGHT, null)))
                    .containsExactly(
                            "GOODS_RECEIVED_NOT_BILLED Dr 1000.00",
                            "FREIGHT_IN Dr 85.00",
                            "ACCOUNTS_PAYABLE Cr 1085.00");
        }

        @Test
        @DisplayName("The tolerance is 0.01 per stated line, at most 0.05 per bill")
        void toleranceScalesWithStatedLinesUpToTheCap() {
            assertThat(VendorBillTotals.tolerance(null)).isEqualByComparingTo("0.01");
            assertThat(VendorBillTotals.tolerance(3)).isEqualByComparingTo("0.03");
            assertThat(VendorBillTotals.tolerance(12)).isEqualByComparingTo("0.05");
            assertThat(VendorBillTotals.of(ediBill("1070.03", "1000.00", "70.00", 3))
                            .orElseThrow()
                            .reconciled())
                    .isTrue();
            assertThat(VendorBillTotals.of(ediBill("1070.04", "1000.00", "70.00", 3))
                            .orElseThrow()
                            .reconciled())
                    .isFalse();
            assertThat(VendorBillTotals.of(ediBill("1070.06", "1000.00", "70.00", 12))
                            .orElseThrow()
                            .reconciled())
                    .isFalse();
            assertThat(VendorBillTotals.of(bill("412.00")))
                    .as("a bill without header totals")
                    .isEmpty();
        }

        @Test
        @DisplayName("An EDI EXPENSE bill puts its rounding on the expense")
        void expenseRounding() {
            VendorBillPostingService.Entry entry = VendorBillPostingService.entry(
                    ediBill("214.01", "200.00", "14.00", 1),
                    List.of(),
                    new VendorBillPostingService.Classification(VendorBillDebitClass.EXPENSE, "EXPENSE_SHOP_SUPPLIES"),
                    null);
            assertThat(entry.legs())
                    .extracting(
                            leg -> leg.mappingKey() + " " + leg.signedAmount().toPlainString())
                    .containsExactly("EXPENSE_SHOP_SUPPLIES 214.01", "ACCOUNTS_PAYABLE -214.01");
            assertThat(entry.roundingAdjustment()).isEqualByComparingTo("0.01");
        }
    }

    @Nested
    @DisplayName("Posting and the void (AW37, AW42)")
    class Posting {

        private final GLMappingResolver resolver = mock();
        private final GLAccountService accounts = mock();
        private final JournalEntryService journalEntries = mock();
        private final VendorBillGlPostingRepository postings = mock();
        private final VendorBillLineRepository lines = mock();
        private final AccountingPeriodGate gate = mock();
        private VendorBillPostingService service;

        private final UUID entryId = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a10");

        @BeforeEach
        void wire() {
            service = new VendorBillPostingService(
                    CLOCK,
                    resolver,
                    accounts,
                    journalEntries,
                    postings,
                    lines,
                    TestZoneResolvers.utc(CLOCK),
                    gate,
                    new LedgerCurrency("USD"));
            when(resolver.resolveGLAccount(eq("VENDOR_BILL"), anyString(), any(LocalDateTime.class)))
                    .thenAnswer(inv -> UUID.nameUUIDFromBytes(
                            inv.getArgument(1, String.class).getBytes()));
            when(postings.findByVendorBillId(BILL_ID)).thenReturn(Optional.empty());
            when(journalEntries.findOriginalBySourceEvent(any())).thenReturn(Optional.empty());
            when(journalEntries.createJournalEntry(any()))
                    .thenReturn(JournalEntryResponse.builder()
                            .journalEntryId(entryId)
                            .build());
            when(journalEntries.postJournalEntry(eq(entryId), any()))
                    .thenReturn(JournalEntryResponse.builder()
                            .journalEntryId(entryId)
                            .entryNumber("JE-202610-000007")
                            .build());
            when(postings.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
            when(lines.findByVendorBill_VendorBillIdOrderByLineNumber(BILL_ID))
                    .thenReturn(List.of(line(1, true, "4", "100.00", "4", "103.00")));
        }

        @Test
        @DisplayName("AW42: the bill date when it is on or before today and its period is open; the source"
                + " VENDOR_BILL:<billId>; one posting row; the bill's journalEntryId set")
        void postsOnTheBillDate() {
            VendorBill bill = bill("412.00");

            VendorBillGlPosting posting = service.post(bill, null, null, "controller.cfo");

            ArgumentCaptor<JournalEntryCreateRequest> request =
                    ArgumentCaptor.forClass(JournalEntryCreateRequest.class);
            verify(journalEntries).createJournalEntry(request.capture());
            assertThat(request.getValue().getTransactionDate())
                    .isEqualTo(LocalDate.of(2026, 10, 1).atStartOfDay());
            assertThat(request.getValue().getSourceEventType()).isEqualTo("VENDOR_BILL");
            assertThat(request.getValue().getSourceEventId())
                    .isEqualTo(UUID.nameUUIDFromBytes(("VENDOR_BILL:" + BILL_ID).getBytes()));
            assertThat(request.getValue().getLines()).hasSize(3);
            assertThat(posting.getPostingDate()).isEqualTo(LocalDate.of(2026, 10, 1));
            assertThat(posting.getPostingDateRule()).isEqualTo(VendorBillPostingDateRule.BILL_DATE);
            assertThat(posting.getSourceKey()).isEqualTo("VENDOR_BILL:" + BILL_ID);
            assertThat(posting.getGrossAmount()).isEqualByComparingTo("412.00");
            assertThat(posting.getCurrencyCode()).isEqualTo("USD");
            assertThat(posting.getPostedBy()).isEqualTo("controller.cfo");
            assertThat(posting.getRoundingAdjustment()).isEqualByComparingTo("0.00");
            assertThat(posting.getDifferenceClass()).isNull();
            assertThat(bill.getJournalEntryId()).isEqualTo(entryId);
        }

        @Test
        @DisplayName("AW47: the difference decided on the bill posts and is recorded with its amount and justification")
        void recordsTheDifference() {
            when(lines.findByVendorBill_VendorBillIdOrderByLineNumber(BILL_ID)).thenReturn(List.of());
            VendorBill edi = ediBill("1085.00", "1000.00", "70.00", 1);
            edi.setDifferenceClass(VendorBillDifferenceClass.FREIGHT);
            edi.setDifferenceJustification("Freight on the invoice, not stated");

            VendorBillGlPosting posting = service.post(edi, GOODS, null, "controller.cfo");

            ArgumentCaptor<JournalEntryCreateRequest> request =
                    ArgumentCaptor.forClass(JournalEntryCreateRequest.class);
            verify(journalEntries).createJournalEntry(request.capture());
            assertThat(request.getValue().getLines()).hasSize(4);
            assertThat(posting.getDifferenceClass()).isEqualTo(VendorBillDifferenceClass.FREIGHT);
            assertThat(posting.getDifferenceAmount()).isEqualByComparingTo("15.00");
            assertThat(posting.getDifferenceJustification()).isEqualTo("Freight on the invoice, not stated");
            assertThat(posting.getRoundingAdjustment()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("A4 (#2601): a key without an active mapping is 422 GL_MAPPING_NOT_CONFIGURED naming the"
                + " category, the key and what to do; nothing is created")
        void missingMappingIsGuided() {
            when(resolver.resolveGLAccount(
                            eq("VENDOR_BILL"), eq("PURCHASE_PRICE_DIFFERENCE"), any(LocalDateTime.class)))
                    .thenThrow(new GLMappingNotConfiguredException("No mapping for key PURCHASE_PRICE_DIFFERENCE"));

            assertThatThrownBy(() -> service.post(bill("412.00"), null, null, "controller.cfo"))
                    .isInstanceOfSatisfying(GLMappingNotConfiguredException.class, e -> {
                        assertThat(e.getReferenceId()).isEqualTo("VENDOR_BILL/PURCHASE_PRICE_DIFFERENCE");
                        assertThat(e.getNextAction())
                                .contains("Map VENDOR_BILL / PURCHASE_PRICE_DIFFERENCE", "2026-10-01");
                        assertThat(e.getMessage())
                                .contains("No active VENDOR_BILL mapping for key PURCHASE_PRICE_DIFFERENCE on"
                                        + " 2026-10-01");
                    });
            verify(journalEntries, never()).createJournalEntry(any());
        }

        @Test
        @DisplayName("#2601: a mapping whose account is not active on the posting date is the same 422"
                + " GL_MAPPING_NOT_CONFIGURED, naming category, key and date; nothing is created")
        void inactiveMappedAccountIsTheSameRefusal() {
            UUID inactive = UUID.nameUUIDFromBytes("GOODS_RECEIVED_NOT_BILLED".getBytes());
            org.mockito.Mockito.doThrow(new GLAccountNotActiveException("Account 2100 is inactive as of 2026-10-01"))
                    .when(accounts)
                    .validateAccountForPosting(eq(inactive), any(LocalDateTime.class));

            assertThatThrownBy(() -> service.post(bill("412.00"), null, null, "controller.cfo"))
                    .isInstanceOfSatisfying(GLMappingNotConfiguredException.class, e -> {
                        assertThat(e.getReferenceId()).isEqualTo("VENDOR_BILL/GOODS_RECEIVED_NOT_BILLED");
                        assertThat(e.getMessage())
                                .contains("VENDOR_BILL", "GOODS_RECEIVED_NOT_BILLED", "2026-10-01", "inactive");
                    });
            verify(journalEntries, never()).createJournalEntry(any());
        }

        @Test
        @DisplayName("Ruling Q7 AC1: a bill dated in a period that is not open posts on the approval date")
        void billPeriodNotOpenPostsOnTheApprovalDate() {
            VendorBill bill = bill("412.00");
            bill.setBillDate(LocalDateTime.of(2026, 9, 20, 0, 0));
            when(gate.isPostingBlocked(LocalDate.of(2026, 9, 20))).thenReturn(true);

            assertThat(service.postingDate(bill))
                    .isEqualTo(new VendorBillPostingService.PostingDate(
                            TODAY, VendorBillPostingDateRule.APPROVAL_DATE_BILL_PERIOD_NOT_OPEN));
        }

        @Test
        @DisplayName("A bill dated after the approval posts on the approval date")
        void futureBillDatePostsOnTheApprovalDate() {
            VendorBill bill = bill("412.00");
            bill.setBillDate(LocalDateTime.of(2026, 10, 20, 0, 0));

            assertThat(service.postingDate(bill))
                    .isEqualTo(new VendorBillPostingService.PostingDate(
                            TODAY, VendorBillPostingDateRule.APPROVAL_DATE_BILL_DATE_FUTURE));
        }

        @Test
        @DisplayName("AW37: a bill already posted (its row or its source entry) is refused and posts nothing more")
        void postsOnce() {
            when(journalEntries.findOriginalBySourceEvent(any()))
                    .thenReturn(Optional.of(JournalEntryResponse.builder()
                            .journalEntryId(entryId)
                            .build()));

            assertThatThrownBy(() -> service.post(bill("412.00"), null, null, "controller.cfo"))
                    .isInstanceOfSatisfying(
                            VendorBillException.class,
                            e -> assertThat(e.getCode()).isEqualTo(VendorBillException.Code.AP_BILL_NOT_APPROVABLE));
            verify(journalEntries, never()).createJournalEntry(any());
        }

        @Test
        @DisplayName("AW43: a bill in another currency is never posted at par")
        void foreignCurrencyIsNeverPosted() {
            VendorBill bill = bill("412.00");
            bill.setCurrency("EUR");

            assertThatThrownBy(() -> service.post(bill, null, null, "controller.cfo"))
                    .isInstanceOf(VendorBillException.class);
            verify(journalEntries, never()).createJournalEntry(any());
        }

        @Test
        @DisplayName("AW42: a refusal of the period gate propagates, so the approval rolls back with it")
        void periodRefusalPropagates() {
            when(journalEntries.postJournalEntry(eq(entryId), any()))
                    .thenThrow(new AccountingPeriodHardLockedException(TODAY.plusDays(1), "hard-locked"));

            assertThatThrownBy(() -> service.post(bill("412.00"), null, null, "controller.cfo"))
                    .isInstanceOf(AccountingPeriodHardLockedException.class);
            verify(postings, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("AC13(d): the void reverses the entry dated today, with the override, and records"
                + " VENDOR_BILL_VOID:<billId>")
        void voidReversesOnTheVoidDate() {
            VendorBillGlPosting posting = new VendorBillGlPosting();
            posting.setVendorBillId(BILL_ID);
            posting.setJournalEntryId(entryId);
            when(postings.findByVendorBillId(BILL_ID)).thenReturn(Optional.of(posting));
            UUID reversalId = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a11");
            VendorBillReversalReaction reaction = new VendorBillReversalReaction(postings);
            when(postings.findByJournalEntryId(entryId)).thenReturn(Optional.of(posting));
            when(journalEntries.reverseJournalEntry(eq(entryId), anyString(), eq(TODAY), eq("Late void, agreed")))
                    .thenAnswer(inv -> {
                        // A1: the reversal reaction sees this reversal as the bill's own void and lets it through.
                        reaction.onReversed(new LedgerReversalApplied(
                                entryId, reversalId, TODAY, List.of(), Set.of(), "controller.cfo", null, "voided"));
                        return JournalEntryResponse.builder()
                                .journalEntryId(reversalId)
                                .build();
                    });

            VendorBillGlPosting reversed = service.reverse(bill("412.00"), "Late void, agreed", "controller.cfo");

            assertThat(reversed.getReversalJournalEntryId()).isEqualTo(reversalId);
            assertThat(reversed.getReversalDate()).isEqualTo(TODAY);
            assertThat(reversed.getReversalSourceKey()).isEqualTo("VENDOR_BILL_VOID:" + BILL_ID);
            assertThat(reversed.getReversedBy()).isEqualTo("controller.cfo");
        }

        @Test
        @DisplayName("A second void of the same posting is 409 AP_BILL_NOT_VOIDABLE and reverses nothing")
        void voidsOnce() {
            VendorBillGlPosting posting = new VendorBillGlPosting();
            posting.setVendorBillId(BILL_ID);
            posting.setJournalEntryId(entryId);
            posting.setReversalJournalEntryId(UUID.randomUUID());
            when(postings.findByVendorBillId(BILL_ID)).thenReturn(Optional.of(posting));

            assertThatThrownBy(() -> service.reverse(bill("412.00"), null, "controller.cfo"))
                    .isInstanceOfSatisfying(
                            VendorBillException.class,
                            e -> assertThat(e.getCode()).isEqualTo(VendorBillException.Code.AP_BILL_NOT_VOIDABLE));
            verify(journalEntries, never()).reverseJournalEntry(any(), anyString(), any(), any());
        }
    }
}
