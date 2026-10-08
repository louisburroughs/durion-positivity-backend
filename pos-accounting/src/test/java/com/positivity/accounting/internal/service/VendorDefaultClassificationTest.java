package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.VendorBillDebitClass;
import com.positivity.accounting.internal.exception.VendorBillException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AC 13 (AW39, ruling 1 of #2517): the vendor's AP defaults are the third tier of an approval's classification, after
 * the approver's choice and the proposal at submission, and before 422 {@code AP_BILL_UNCLASSIFIED}.
 */
@DisplayName("Vendor AP defaults in an approval's classification (S24, AC 13)")
class VendorDefaultClassificationTest {

    private static final VendorBillPostingService.Classification SHOP_SUPPLIES =
            new VendorBillPostingService.Classification(VendorBillDebitClass.EXPENSE, "EXPENSE_SHOP_SUPPLIES");

    /** V's header-only EDI bill: gross 288.00 = net 240.00 + tax 48.00, no stored lines. */
    private static VendorBill headerOnly() {
        VendorBill bill = new VendorBill();
        bill.setVendorBillId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f6a01"));
        bill.setVendorId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f6a02"));
        bill.setBillNumber("INV-1");
        bill.setTotalAmount(new BigDecimal("288.00"));
        bill.setNetAmount(new BigDecimal("240.00"));
        bill.setTaxAmount(new BigDecimal("48.00"));
        bill.setStatedLineCount(1);
        return bill;
    }

    private static Map<String, BigDecimal> legs(VendorBill bill, VendorBillPostingService.Classification effective) {
        return VendorBillPostingService.legs(bill, List.of(), effective, null).stream()
                .collect(Collectors.toMap(
                        VendorBillPostingService.Leg::mappingKey,
                        VendorBillPostingService.Leg::signedAmount,
                        BigDecimal::add));
    }

    @Test
    @DisplayName("no classification given: the vendor default posts Dr EXPENSE_SHOP_SUPPLIES / Cr ACCOUNTS_PAYABLE")
    void defaultApplies() {
        VendorBill bill = headerOnly();

        VendorBillPostingService.Classification effective =
                VendorBillApprovalServiceImpl.merge(null, bill, SHOP_SUPPLIES);

        assertThat(effective).isEqualTo(SHOP_SUPPLIES);
        Map<String, BigDecimal> legs = legs(bill, effective);
        assertThat(legs.get("EXPENSE_SHOP_SUPPLIES")).isEqualByComparingTo("288.00");
        assertThat(legs.get("ACCOUNTS_PAYABLE")).isEqualByComparingTo("-288.00");
    }

    @Test
    @DisplayName("the approver's GOODS wins over the vendor default: Dr GOODS_RECEIVED_NOT_BILLED (2100)")
    void approverWins() {
        VendorBill bill = headerOnly();

        VendorBillPostingService.Classification effective = VendorBillApprovalServiceImpl.merge(
                new VendorBillPostingService.Classification(VendorBillDebitClass.GOODS, null), bill, SHOP_SUPPLIES);

        assertThat(effective.debitClass()).isEqualTo(VendorBillDebitClass.GOODS);
        Map<String, BigDecimal> legs = legs(bill, effective);
        assertThat(legs).containsKey("GOODS_RECEIVED_NOT_BILLED").doesNotContainKey("EXPENSE_SHOP_SUPPLIES");
        assertThat(legs.get("ACCOUNTS_PAYABLE")).isEqualByComparingTo("-288.00");
    }

    @Test
    @DisplayName("the proposal at submission wins over the vendor default too")
    void proposalWins() {
        VendorBill bill = headerOnly();
        bill.setProposedDebitClass(VendorBillDebitClass.GOODS);

        assertThat(VendorBillApprovalServiceImpl.merge(null, bill, SHOP_SUPPLIES)
                        .debitClass())
                .isEqualTo(VendorBillDebitClass.GOODS);
    }

    @Test
    @DisplayName("no class anywhere: 422 AP_BILL_UNCLASSIFIED")
    void noDefaultIsUnclassified() {
        VendorBill bill = headerOnly();

        assertThat(VendorBillApprovalServiceImpl.merge(null, bill, null)).isNull();
        assertThatThrownBy(() -> legs(bill, new VendorBillPostingService.Classification(null, null)))
                .isInstanceOfSatisfying(
                        VendorBillException.class,
                        e -> assertThat(e.getCode()).isEqualTo(VendorBillException.Code.AP_BILL_UNCLASSIFIED));
    }

    @Test
    @DisplayName("a GOODS default never classes a credit note")
    void goodsDefaultSkipsCreditNotes() {
        VendorBill credit = headerOnly();
        credit.setTotalAmount(new BigDecimal("-288.00"));

        assertThat(VendorBillApprovalServiceImpl.merge(
                        null, credit, new VendorBillPostingService.Classification(VendorBillDebitClass.GOODS, null)))
                .isNull();
    }
}
