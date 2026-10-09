package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.client.TaxReferenceClient;
import com.positivity.accounting.internal.dto.TaxUseQuote;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.entity.VendorBillLine;
import com.positivity.accounting.internal.enums.VendorBillDebitClass;
import com.positivity.accounting.internal.exception.TaxQuoteRefusedException;
import com.positivity.accounting.internal.exception.TaxServiceUnavailableException;
import com.positivity.accounting.internal.repository.VendorBillLineRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * CAP:550 S43 (#2604, AW44): which bills offer the purchase-tax rules anything (bill level, AW39 proration, credit notes
 * exempt), the read's 5-minute rules cache that a decision never uses, and the use-tax quote's sum. Tax country
 * {@code ZZ}; every value is a fixture, not tax law.
 */
@DisplayName("VendorBillPurchaseTax: qualification, rules cache and the use-tax quote (S43)")
class VendorBillPurchaseTaxTest {

    private static final VendorBillPostingService.Classification GOODS =
            new VendorBillPostingService.Classification(VendorBillDebitClass.GOODS, null);
    private static final VendorBillPostingService.Classification SHOP_SUPPLIES =
            new VendorBillPostingService.Classification(VendorBillDebitClass.EXPENSE, "EXPENSE_SHOP_SUPPLIES");

    private static VendorBill header(String gross, String net, String tax) {
        VendorBill bill = new VendorBill(UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4f01"));
        bill.setBillNumber("INV-43");
        bill.setTotalAmount(new BigDecimal(gross));
        bill.setNetAmount(net == null ? null : new BigDecimal(net));
        bill.setTaxAmount(tax == null ? null : new BigDecimal(tax));
        bill.setStatedLineCount(net == null ? null : 1);
        return bill;
    }

    private static VendorBillLine line(int number, boolean stocked, String quantity, String price) {
        VendorBillLine line = new VendorBillLine();
        line.setLineNumber(number);
        line.setInventoryItem(stocked);
        line.setQuantity(stocked ? new BigDecimal(quantity) : BigDecimal.ZERO);
        line.setUnitPrice(new BigDecimal(price));
        line.setBilledQuantity(new BigDecimal(quantity));
        line.setBilledUnitPrice(new BigDecimal(price));
        return line;
    }

    @Nested
    @DisplayName("Qualification, at bill level")
    class Qualification {

        @Test
        @DisplayName("A header-only GOODS bill with tax 28.00 may be held; it never accrues")
        void headerGoodsWithTax() {
            VendorBillPostingService.PurchaseTaxBasis basis =
                    VendorBillPostingService.purchaseTaxBasis(header("428.00", "400.00", "28.00"), List.of(), GOODS);

            assertThat(basis.mayHold()).isTrue();
            assertThat(basis.taxOnGoods()).isEqualByComparingTo("28.00");
            assertThat(basis.statedTax()).isEqualByComparingTo("28.00");
            assertThat(basis.mayAccrue()).isFalse();
        }

        @Test
        @DisplayName("A goods-receipt bill: the header tax prorated by line net onto a RECEIPT_MATCHED line holds; the"
                + " share on its expense line does not")
        void prorationOntoReceiptMatchedLines() {
            VendorBill bill = header("330.00", null, "30.00");
            List<VendorBillLine> lines = List.of(line(1, true, "2", "100.00"), line(2, false, "1", "100.00"));

            VendorBillPostingService.PurchaseTaxBasis basis =
                    VendorBillPostingService.purchaseTaxBasis(bill, lines, SHOP_SUPPLIES);

            assertThat(basis.taxOnGoods()).isEqualByComparingTo("20.00");
            assertThat(basis.mayHold()).isTrue();
            assertThat(basis.mayAccrue()).as("the bill states tax: no accrual").isFalse();
        }

        @Test
        @DisplayName("A goods-receipt bill without tax accrues on each EXPENSE line with a net above 0.00, by line"
                + " number; goods lines never accrue")
        void untaxedExpenseLines() {
            VendorBill bill = header("350.00", null, null);
            List<VendorBillLine> lines =
                    List.of(line(1, true, "1", "100.00"), line(2, false, "1", "200.00"), line(3, false, "2", "25.00"));

            VendorBillPostingService.PurchaseTaxBasis basis =
                    VendorBillPostingService.purchaseTaxBasis(bill, lines, SHOP_SUPPLIES);

            assertThat(basis.mayHold()).isFalse();
            assertThat(basis.mayAccrue()).isTrue();
            assertThat(basis.untaxedExpense())
                    .containsExactly(
                            new VendorBillPostingService.UntaxedLine("2", new BigDecimal("200.00")),
                            new VendorBillPostingService.UntaxedLine("3", new BigDecimal("50.00")));
            assertThat(basis.expenseMappingKey()).isEqualTo("EXPENSE_SHOP_SUPPLIES");
        }

        @Test
        @DisplayName("A header-only EXPENSE bill without tax accrues on its header net; with tax 5.00 it does not")
        void headerExpense() {
            assertThat(VendorBillPostingService.purchaseTaxBasis(
                                    header("200.00", "200.00", "0.00"), List.of(), SHOP_SUPPLIES)
                            .untaxedExpense())
                    .containsExactly(new VendorBillPostingService.UntaxedLine("1", new BigDecimal("200.00")));
            assertThat(VendorBillPostingService.purchaseTaxBasis(
                                    header("205.00", "200.00", "5.00"), List.of(), SHOP_SUPPLIES)
                            .mayAccrue())
                    .isFalse();
        }

        @Test
        @DisplayName("[M] A credit note neither holds nor accrues")
        void creditNotesAreExempt() {
            VendorBillPostingService.PurchaseTaxBasis expense = VendorBillPostingService.purchaseTaxBasis(
                    header("-50.00", "-50.00", "0.00"), List.of(), SHOP_SUPPLIES);
            VendorBillPostingService.PurchaseTaxBasis goods =
                    VendorBillPostingService.purchaseTaxBasis(header("-53.50", "-50.00", "-3.50"), List.of(), GOODS);

            assertThat(expense.mayAccrue()).isFalse();
            assertThat(expense.mayHold()).isFalse();
            assertThat(goods.mayHold()).isFalse();
        }

        @Test
        @DisplayName("An expense line without a key never accrues (the bill refuses as unclassified first)")
        void noKeyNoAccrual() {
            assertThat(VendorBillPostingService.purchaseTaxBasis(
                                    header("200.00", "200.00", "0.00"),
                                    List.of(),
                                    new VendorBillPostingService.Classification(VendorBillDebitClass.EXPENSE, null))
                            .mayAccrue())
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("The rules: a read caches, a decision never does")
    class Rules {

        private final TaxReferenceClient client = mock();
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-08T12:00:00Z"));
        private final Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        private final VendorBillPurchaseTax purchaseTax = PurchaseTaxFixtures.purchaseTax(
                client, mock(SupplierVendorCopies.class), mock(VendorBillLineRepository.class), clock);
        private final LocalDate today = LocalDate.of(2026, 10, 8);

        @Test
        @DisplayName("Reads within 5 minutes share one pos-tax call per (country, date); after it, pos-tax is asked"
                + " again")
        void readsAreCached() {
            when(client.purchaseRules("ZZ", today)).thenReturn(PurchaseTaxFixtures.HOLD_AND_SELF_ASSESS);

            assertThat(purchaseTax.cachedRules(today)).contains(PurchaseTaxFixtures.HOLD_AND_SELF_ASSESS);
            now.set(now.get().plusSeconds(299));
            assertThat(purchaseTax.cachedRules(today)).contains(PurchaseTaxFixtures.HOLD_AND_SELF_ASSESS);
            verify(client, times(1)).purchaseRules("ZZ", today);

            now.set(now.get().plusSeconds(2));
            purchaseTax.cachedRules(today);
            verify(client, times(2)).purchaseRules("ZZ", today);
        }

        @Test
        @DisplayName("Rules pos-tax cannot give are empty for a read, and never cached")
        void unavailableIsEmptyAndNotCached() {
            when(client.purchaseRules(any(), any()))
                    .thenThrow(new TaxServiceUnavailableException("unavailable"))
                    .thenReturn(PurchaseTaxFixtures.OFF);

            assertThat(purchaseTax.cachedRules(today)).isEmpty();
            assertThat(purchaseTax.cachedRules(today)).contains(PurchaseTaxFixtures.OFF);
        }

        @Test
        @DisplayName("A decision asks pos-tax every time, even with fresh cached rules, and a failure is 503")
        void decisionsAreNeverCached() {
            when(client.purchaseRules("ZZ", today)).thenReturn(PurchaseTaxFixtures.HOLD_AND_SELF_ASSESS);
            purchaseTax.cachedRules(today);

            purchaseTax.rules(today);
            purchaseTax.rules(today);

            verify(client, times(3)).purchaseRules("ZZ", today);
            when(client.purchaseRules("ZZ", today)).thenThrow(new TaxServiceUnavailableException("unavailable"));
            assertThatThrownBy(() -> purchaseTax.rules(today)).isInstanceOf(TaxServiceUnavailableException.class);
        }
    }

    @Nested
    @DisplayName("The use-tax quote")
    class Quote {

        private final TaxReferenceClient client = mock();
        private final VendorBillPurchaseTax purchaseTax = PurchaseTaxFixtures.purchaseTax(
                client, mock(SupplierVendorCopies.class), mock(VendorBillLineRepository.class));
        private final VendorBillPostingService.PurchaseTaxBasis basis = new VendorBillPostingService.PurchaseTaxBasis(
                new BigDecimal("0.00"),
                new BigDecimal("0.00"),
                List.of(
                        new VendorBillPostingService.UntaxedLine("2", new BigDecimal("200.00")),
                        new VendorBillPostingService.UntaxedLine("3", new BigDecimal("50.00"))),
                "EXPENSE_SHOP_SUPPLIES");

        @Test
        @DisplayName("Posts the sum of the line taxes above 0.00 as returned, ignoring a line it did not ask for")
        void sumsTheReturnedLines() {
            List<TaxUseQuote.LineTax> taxes = new ArrayList<>();
            taxes.add(new TaxUseQuote.LineTax("2", new BigDecimal("17.00")));
            taxes.add(new TaxUseQuote.LineTax("3", new BigDecimal("4.25")));
            taxes.add(new TaxUseQuote.LineTax("9", new BigDecimal("99.00")));
            when(client.useTax(any())).thenReturn(new TaxUseQuote.Response(new BigDecimal("120.25"), taxes));

            assertThat(purchaseTax.quote(header("250.00", null, null), basis, LocalDate.of(2026, 10, 1)))
                    .isEqualTo(new VendorBillPostingService.UseTax("EXPENSE_SHOP_SUPPLIES", new BigDecimal("21.25")));
        }

        @Test
        @DisplayName("A2: the scale is the ledger currency's exponent: a 3-decimal ledger posts 1.235 as returned; a"
                + " line tax finer than the minor unit is an unusable answer (503), never rounded")
        void ledgerScale() {
            VendorBillPurchaseTax kwd = PurchaseTaxFixtures.purchaseTax(
                    client,
                    mock(SupplierVendorCopies.class),
                    mock(VendorBillLineRepository.class),
                    java.time.Clock.systemUTC(),
                    "KWD");
            when(client.useTax(any()))
                    .thenReturn(new TaxUseQuote.Response(
                            new BigDecimal("1.235"), List.of(new TaxUseQuote.LineTax("2", new BigDecimal("1.235")))))
                    .thenReturn(new TaxUseQuote.Response(
                            new BigDecimal("1.2345"), List.of(new TaxUseQuote.LineTax("2", new BigDecimal("1.2345")))));

            VendorBillPostingService.UseTax quoted =
                    kwd.quote(header("250.000", null, null), basis, LocalDate.of(2026, 10, 1));
            assertThat(quoted.amount()).isEqualTo(new BigDecimal("1.235"));
            assertThatThrownBy(() -> kwd.quote(header("250.000", null, null), basis, LocalDate.of(2026, 10, 1)))
                    .isInstanceOf(TaxServiceUnavailableException.class);

            when(client.useTax(any()))
                    .thenReturn(new TaxUseQuote.Response(
                            new BigDecimal("17.005"), List.of(new TaxUseQuote.LineTax("2", new BigDecimal("17.005")))));
            assertThatThrownBy(() -> purchaseTax.quote(header("250.00", null, null), basis, LocalDate.of(2026, 10, 1)))
                    .as("USD has 2 decimals: 17.005 is not rounded to 17.01")
                    .isInstanceOf(TaxServiceUnavailableException.class);
        }

        @Test
        @DisplayName(
                "A relayed refusal is rethrown with accounting's message per code, naming the bill and the setting")
        void relayedRefusalMessages() {
            when(client.useTax(any()))
                    .thenThrow(new TaxQuoteRefusedException("CURRENCY_NOT_SUPPORTED", "pos-tax text"))
                    .thenThrow(new TaxQuoteRefusedException("TAX_CAPABILITY_UNSUPPORTED", "pos-tax text"));

            assertThatThrownBy(() -> purchaseTax.quote(header("250.00", null, null), basis, LocalDate.of(2026, 10, 1)))
                    .isInstanceOfSatisfying(TaxQuoteRefusedException.class, e -> {
                        assertThat(e.getCode()).isEqualTo("CURRENCY_NOT_SUPPORTED");
                        assertThat(e.getMessage())
                                .contains("INV-43", "accounting.ledger.base-currency")
                                .doesNotContain("pos-tax text");
                    });
            assertThatThrownBy(() -> purchaseTax.quote(header("250.00", null, null), basis, LocalDate.of(2026, 10, 1)))
                    .isInstanceOfSatisfying(
                            TaxQuoteRefusedException.class,
                            e -> assertThat(e.getMessage()).contains("provider"));
        }

        @Test
        @DisplayName("No tax above 0.00 accrues nothing; an answer without line taxes is 503")
        void nothingOrUnreadable() {
            when(client.useTax(any()))
                    .thenReturn(new TaxUseQuote.Response(
                            BigDecimal.ZERO, List.of(new TaxUseQuote.LineTax("2", new BigDecimal("0.00")))))
                    .thenReturn(new TaxUseQuote.Response(new BigDecimal("17.00"), null));

            assertThat(purchaseTax.quote(header("250.00", null, null), basis, LocalDate.of(2026, 10, 1)))
                    .isNull();
            assertThatThrownBy(() -> purchaseTax.quote(header("250.00", null, null), basis, LocalDate.of(2026, 10, 1)))
                    .isInstanceOf(TaxServiceUnavailableException.class);
        }
    }
}
