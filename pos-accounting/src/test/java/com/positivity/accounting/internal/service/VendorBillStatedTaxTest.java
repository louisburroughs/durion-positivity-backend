package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.bankrec.service.FunctionalCurrency;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.VendorBillCommands;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.entity.VendorBillTax;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.repository.VendorBillTaxRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * CAP:550 S32d item 10, ACs 9 and 10: every bill keeps the tax its document states by type, and an approval's {@code
 * taxByType[]} must add up to the stated tax, else 422 {@code AP_BILL_TAX_SPLIT_MISMATCH}.
 */
@DisplayName("VendorBillStatedTax: tax by type on every bill (CAP:550 S32d)")
class VendorBillStatedTaxTest {

    private static final UUID BILL_ID = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4d01");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC);

    private final VendorBillTaxRepository billTaxes = mock();
    private final VendorBillStatedTax statedTax =
            new VendorBillStatedTax(billTaxes, new FunctionalCurrency(new LedgerCurrency("USD")), CLOCK);

    private static VendorBill bill(String gross, String tax) {
        VendorBill bill = new VendorBill(BILL_ID);
        bill.setBillNumber("INV-9");
        bill.setTotalAmount(new BigDecimal(gross));
        bill.setTaxAmount(tax == null ? null : new BigDecimal(tax));
        return bill;
    }

    private static VendorBillCommands.TaxAmount tax(String type, String amount) {
        return new VendorBillCommands.TaxAmount(type, new BigDecimal(amount));
    }

    @SuppressWarnings("unchecked")
    private List<VendorBillTax> saved() {
        ArgumentCaptor<List<VendorBillTax>> rows = ArgumentCaptor.forClass(List.class);
        verify(billTaxes).saveAll(rows.capture());
        return rows.getValue();
    }

    @Test
    @DisplayName("AC 9: a document's tax by type is stored as stated, one row per type")
    void storesTheDocumentsTaxByType() {
        Map<String, BigDecimal> byType = new LinkedHashMap<>();
        byType.put("GST", new BigDecimal("50.00"));
        byType.put("PST", new BigDecimal("70.00"));

        statedTax.storeFromDocument(bill("1120.00", "120.00"), byType);

        assertThat(saved())
                .extracting(VendorBillTax::getTaxType, VendorBillTax::getAmount, VendorBillTax::getSource)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "GST", new BigDecimal("50.00"), VendorBillTax.Source.DOCUMENT),
                        org.assertj.core.groups.Tuple.tuple(
                                "PST", new BigDecimal("70.00"), VendorBillTax.Source.DOCUMENT));
    }

    @Test
    @DisplayName("AC 9: a document without tax by type stores nothing")
    void noTaxByTypeStoresNothing() {
        statedTax.storeFromDocument(bill("100.00", "0.00"), null);
        statedTax.storeFromDocument(bill("100.00", "0.00"), Map.of());

        verifyNoInteractions(billTaxes);
    }

    @Test
    @DisplayName("AC 10: a taxByType[] that adds up replaces the stored rows, signed like the bill")
    void approvalReplacesTheSplit() {
        statedTax.replaceFromApproval(bill("-1120.00", "-120.00"), List.of(tax("GST", "50.00"), tax("PST", "70.00")));

        InOrder order = inOrder(billTaxes);
        order.verify(billTaxes).deleteByVendorBillId(BILL_ID);
        assertThat(saved())
                .extracting(VendorBillTax::getTaxType, VendorBillTax::getAmount, VendorBillTax::getSource)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "GST", new BigDecimal("-50.00"), VendorBillTax.Source.APPROVAL),
                        org.assertj.core.groups.Tuple.tuple(
                                "PST", new BigDecimal("-70.00"), VendorBillTax.Source.APPROVAL));
    }

    @Test
    @DisplayName("AC 10: a taxByType[] whose sum differs from the stated tax -> 422 AP_BILL_TAX_SPLIT_MISMATCH,"
            + " nothing written")
    void mismatchIsRefused() {
        assertThatThrownBy(() -> statedTax.replaceFromApproval(
                        bill("1120.00", "120.00"), List.of(tax("GST", "50.00"), tax("PST", "60.00"))))
                .isInstanceOfSatisfying(VendorBillException.class, refused -> {
                    assertThat(refused.getCode()).isEqualTo(VendorBillException.Code.AP_BILL_TAX_SPLIT_MISMATCH);
                    assertThat(refused.getCode().status().value()).isEqualTo(422);
                    assertThat(refused.getFieldErrors())
                            .singleElement()
                            .extracting(VendorBillException.FieldError::field)
                            .isEqualTo("taxByType");
                });
        verify(billTaxes, never()).deleteByVendorBillId(any());
        verify(billTaxes, never()).saveAll(any());
    }

    @Test
    @DisplayName("a bill stating no tax takes no split: any amount is a mismatch")
    void noStatedTaxMismatch() {
        assertThatThrownBy(() -> statedTax.replaceFromApproval(bill("100.00", null), List.of(tax("GST", "5.00"))))
                .isInstanceOfSatisfying(
                        VendorBillException.class,
                        refused -> assertThat(refused.getCode())
                                .isEqualTo(VendorBillException.Code.AP_BILL_TAX_SPLIT_MISMATCH));
    }

    @Test
    @DisplayName("a tax type named twice -> 400 VALIDATION_ERROR naming it")
    void duplicateTypeIsRefused() {
        assertThatThrownBy(() -> statedTax.replaceFromApproval(
                        bill("1120.00", "120.00"), List.of(tax("GST", "50.00"), tax("GST", "70.00"))))
                .isInstanceOfSatisfying(VendorBillException.class, refused -> {
                    assertThat(refused.getCode()).isEqualTo(VendorBillException.Code.VALIDATION_ERROR);
                    assertThat(refused.getFieldErrors())
                            .extracting(VendorBillException.FieldError::field)
                            .containsExactly("taxByType[1].taxType");
                });
        verifyNoInteractions(billTaxes);
    }

    @Test
    @DisplayName("ADR-0067 PC-6: an amount finer than the currency -> 422 AMOUNT_PRECISION_EXCEEDS_CURRENCY, never"
            + " rounded")
    void overPreciseAmountIsRefused() {
        assertThatThrownBy(() -> statedTax.replaceFromApproval(
                        bill("1120.00", "120.00"), List.of(tax("GST", "50.005"), tax("PST", "69.995"))))
                .isInstanceOfSatisfying(BankRecException.class, refused -> {
                    assertThat(refused.code()).isEqualTo(BankRecErrorCode.AMOUNT_PRECISION_EXCEEDS_CURRENCY);
                    assertThat(refused.fieldErrors()).containsOnlyKeys("taxByType[0].amount", "taxByType[1].amount");
                });
        verifyNoInteractions(billTaxes);
    }

    @Test
    @DisplayName("no taxByType[] keeps what the bill states")
    void absentKeepsTheStoredSplit() {
        statedTax.replaceFromApproval(bill("1120.00", "120.00"), null);

        verifyNoInteractions(billTaxes);
    }

    @Test
    @DisplayName("the approval audit records the split it copied")
    void auditRecordsTheSplit() {
        assertThat(VendorBillStatedTax.auditOf(List.of(tax("GST", "50.00"), tax("PST", "70.00"))))
                .isEqualTo("taxByType=GST:50.00,PST:70.00");
        assertThat(VendorBillStatedTax.auditOf(null)).isNull();
    }
}
