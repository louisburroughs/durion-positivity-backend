package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.entity.VendorBillLine;
import com.positivity.accounting.internal.enums.VendorBillDebitClass;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CAP:550 S32d item 10, AC 8 and the bill half of AC 1: the entry a vendor bill posts when part of its stated tax is
 * recovered. The recovered tax debits {@code TAX_RECOVERABLE_<regime>} (1250 in the CAD template), the rest stays in the
 * line's class, and accounts payable is the gross. Mapping keys only; the accounts are the tenant's mappings.
 */
@DisplayName("VendorBillPostingService: the bill split (CAP:550 S32d AC 8)")
class VendorBillRecoveryLegsTest {

    private static final UUID BILL_ID = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4c01");
    private static final String RECOVERABLE = "TAX_RECOVERABLE_GST_HST";
    private static final Map<String, BigDecimal> GST_50 = Map.of(RECOVERABLE, new BigDecimal("50.00"));

    private static final VendorBillPostingService.Classification EXPENSE =
            new VendorBillPostingService.Classification(VendorBillDebitClass.EXPENSE, "EXPENSE_SHOP_SUPPLIES");
    private static final VendorBillPostingService.Classification GOODS =
            new VendorBillPostingService.Classification(VendorBillDebitClass.GOODS, null);

    /** An EDI bill: the vendor's header totals, no stored lines. */
    private static VendorBill ediBill(String gross, String net, String tax) {
        VendorBill bill = new VendorBill(BILL_ID);
        bill.setBillNumber("INV-CA-8");
        bill.setBillDate(LocalDateTime.of(2026, 10, 1, 0, 0));
        bill.setStatus(VendorBillStatus.AWAITING_APPROVAL);
        bill.setTotalAmount(new BigDecimal(gross));
        bill.setNetAmount(new BigDecimal(net));
        bill.setTaxAmount(new BigDecimal(tax));
        bill.setStatedLineCount(1);
        return bill;
    }

    private static List<String> legs(
            VendorBill bill,
            List<VendorBillLine> lines,
            VendorBillPostingService.Classification classification,
            Map<String, BigDecimal> recovered) {
        List<String> out = new ArrayList<>();
        for (VendorBillPostingService.Leg leg :
                VendorBillPostingService.legs(bill, lines, classification, null, recovered)) {
            BigDecimal amount = leg.signedAmount();
            out.add(leg.mappingKey()
                    + (amount.signum() > 0 ? " Dr " : " Cr ")
                    + amount.abs().toPlainString());
        }
        return out;
    }

    private static VendorBillLine line(
            int number, boolean stocked, String received, String receivedPrice, String billed, String billedPrice) {
        VendorBillLine line = new VendorBillLine();
        line.setLineNumber(number);
        line.setProductId(UUID.nameUUIDFromBytes(("product-" + number).getBytes()));
        line.setInventoryItem(stocked);
        line.setQuantity(new BigDecimal(received));
        line.setUnitPrice(new BigDecimal(receivedPrice));
        line.setBilledQuantity(new BigDecimal(billed));
        line.setBilledUnitPrice(new BigDecimal(billedPrice));
        return line;
    }

    @Test
    @DisplayName("AC 8: net 1,000.00, GST 50.00, PST 70.00, gross 1,120.00 -> Dr class 1,070.00 / Dr recoverable 50.00"
            + " / Cr AP 1,120.00")
    void expenseBillSplitsGst() {
        assertThat(legs(ediBill("1120.00", "1000.00", "120.00"), List.of(), EXPENSE, GST_50))
                .containsExactly(
                        "EXPENSE_SHOP_SUPPLIES Dr 1070.00", RECOVERABLE + " Dr 50.00", "ACCOUNTS_PAYABLE Cr 1120.00");
    }

    @Test
    @DisplayName("AC 8: on goods the 50.00 never reaches 2100 or inventory cost; PST stays in 5050")
    void goodsBillKeepsRecoverableTaxOutOfInventoryCost() {
        assertThat(legs(ediBill("1120.00", "1000.00", "120.00"), List.of(), GOODS, GST_50))
                .containsExactly(
                        "GOODS_RECEIVED_NOT_BILLED Dr 1000.00",
                        "PURCHASE_PRICE_DIFFERENCE Dr 70.00",
                        RECOVERABLE + " Dr 50.00",
                        "ACCOUNTS_PAYABLE Cr 1120.00");
    }

    @Test
    @DisplayName("AC 8: the credit note posts the mirror")
    void creditNoteMirrors() {
        assertThat(legs(ediBill("-1120.00", "-1000.00", "-120.00"), List.of(), EXPENSE, GST_50))
                .containsExactly(
                        "EXPENSE_SHOP_SUPPLIES Cr 1070.00", RECOVERABLE + " Cr 50.00", "ACCOUNTS_PAYABLE Dr 1120.00");
    }

    @Test
    @DisplayName("AC 8: header-only tax over several classes is prorated by line net, the residual cent on the largest"
            + " line; the recovered tax is not prorated")
    void headerTaxProratedOverClasses() {
        VendorBill bill = new VendorBill(BILL_ID);
        bill.setBillNumber("GR-8");
        bill.setBillDate(LocalDateTime.of(2026, 10, 1, 0, 0));
        bill.setStatus(VendorBillStatus.AWAITING_APPROVAL);
        bill.setTotalAmount(new BigDecimal("305.10"));
        bill.setTaxAmount(new BigDecimal("5.10"));
        List<VendorBillLine> lines = List.of(
                line(1, true, "1", "100.00", "1", "100.00"), // receipt-matched
                line(2, true, "0", "100.00", "1", "100.00"), // billed stock without a receipt: GOODS
                line(3, false, "1", "100.00", "1", "100.00")); // non-stock: EXPENSE

        assertThat(legs(bill, lines, EXPENSE, Map.of(RECOVERABLE, new BigDecimal("5.00"))))
                .containsExactly(
                        "GOODS_RECEIVED_NOT_BILLED Dr 200.00",
                        // 0.10 over 100 / 100 / 100: 0.03 each, the residual cent on the first largest line.
                        "PURCHASE_PRICE_DIFFERENCE Dr 0.07",
                        "EXPENSE_SHOP_SUPPLIES Dr 100.03",
                        RECOVERABLE + " Dr 5.00",
                        "ACCOUNTS_PAYABLE Cr 305.10");
    }

    @Test
    @DisplayName("the recovered amount is copied as stated: the rounding goes on another debit even when smaller")
    void recoveredTaxNeverTakesTheRounding() {
        assertThat(legs(ediBill("60.01", "10.00", "50.00"), List.of(), EXPENSE, GST_50))
                .containsExactly(
                        "EXPENSE_SHOP_SUPPLIES Dr 10.01", RECOVERABLE + " Dr 50.00", "ACCOUNTS_PAYABLE Cr 60.01");
    }

    @Test
    @DisplayName("AC 1 (bills) and AW51: nothing recovered books the gross exactly as before")
    void nothingRecoveredIsTodaysEntry() {
        VendorBill edi = ediBill("1120.00", "1000.00", "120.00");
        assertThat(legs(edi, List.of(), EXPENSE, Map.of()))
                .containsExactly("EXPENSE_SHOP_SUPPLIES Dr 1120.00", "ACCOUNTS_PAYABLE Cr 1120.00");
        assertThat(VendorBillPostingService.legs(edi, List.of(), GOODS, null, Map.of()))
                .isEqualTo(VendorBillPostingService.legs(edi, List.of(), GOODS, null));
    }
}
