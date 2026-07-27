package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.CreditMemoTax;
import com.positivity.accounting.internal.entity.ExtInvoiceTax;
import com.positivity.accounting.internal.repository.CreditMemoTaxRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceTaxRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for the frozen per-jurisdiction credit attribution (issue #996) that replaces the
 * T8 report's read-time pro-rata approximation.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CreditMemoTaxAttributionService (#996)")
class CreditMemoTaxAttributionServiceTest {

    private static final UUID CREDIT_MEMO_ID = UUID.randomUUID();
    private static final UUID INVOICE_ID = UUID.randomUUID();

    @Mock
    private ExtInvoiceTaxRepository extInvoiceTaxRepository;

    @Mock
    private CreditMemoTaxRepository creditMemoTaxRepository;

    @InjectMocks
    private CreditMemoTaxAttributionService service;

    @Test
    @DisplayName("Partial credit splits pro-rata across jurisdictions and sums exactly to the scalar")
    void partialCredit_splitsProRataAndSumsExactly() {
        // Invoice collected 60.00 STATE + 40.00 CITY = 100.00. Credit reverses 30.00.
        stubBreakdown(taxRow("STATE", "CA", "60.00"), taxRow("CITY", "SF", "40.00"));
        stubSaveEcho();

        List<CreditMemoTax> rows = service.attribute(CREDIT_MEMO_ID, INVOICE_ID, new BigDecimal("30.00"), false);

        assertThat(rows).hasSize(2);
        assertThat(reversedFor(rows, "STATE", "CA")).isEqualByComparingTo("18.00");
        assertThat(reversedFor(rows, "CITY", "SF")).isEqualByComparingTo("12.00");
        assertThat(sum(rows)).isEqualByComparingTo("30.00");
    }

    @Test
    @DisplayName("Rounding residual lands on the largest-weight jurisdiction so the parts sum exactly")
    void partialCredit_residualLandsOnLargestWeight() {
        // Three equal-ish weights and an amount that does not divide cleanly.
        stubBreakdown(taxRow("STATE", "CA", "10.00"), taxRow("COUNTY", "SM", "10.00"), taxRow("CITY", "SF", "10.00"));
        stubSaveEcho();

        List<CreditMemoTax> rows = service.attribute(CREDIT_MEMO_ID, INVOICE_ID, new BigDecimal("10.00"), false);

        assertThat(sum(rows)).isEqualByComparingTo("10.00");
        // 10/3 = 3.33 each, residual 0.01 goes to the highest-ranked jurisdiction on a weight tie.
        assertThat(reversedFor(rows, "STATE", "CA")).isEqualByComparingTo("3.34");
    }

    @Test
    @DisplayName("Final credit reverses each jurisdiction's own residual, not a pro-rata share")
    void finalCredit_reversesPerJurisdictionResidual() {
        stubBreakdown(taxRow("STATE", "CA", "60.00"), taxRow("CITY", "SF", "40.00"));
        // A prior partial credit already reversed 18.00 STATE / 12.00 CITY.
        when(creditMemoTaxRepository.sumReversedForInvoiceJurisdiction(INVOICE_ID, "STATE", "CA"))
                .thenReturn(new BigDecimal("18.00"));
        when(creditMemoTaxRepository.sumReversedForInvoiceJurisdiction(INVOICE_ID, "CITY", "SF"))
                .thenReturn(new BigDecimal("12.00"));
        stubSaveEcho();

        List<CreditMemoTax> rows = service.attribute(CREDIT_MEMO_ID, INVOICE_ID, new BigDecimal("70.00"), true);

        // Each jurisdiction lands exactly on its collected tax across the two credits.
        assertThat(reversedFor(rows, "STATE", "CA")).isEqualByComparingTo("42.00");
        assertThat(reversedFor(rows, "CITY", "SF")).isEqualByComparingTo("28.00");
        assertThat(sum(rows)).isEqualByComparingTo("70.00");
    }

    @Test
    @DisplayName("Fully-exempt invoice (no collected tax) attributes nothing rather than inventing a jurisdiction")
    void noCollectedTax_attributesNothing() {
        stubBreakdown(exemptRow("STATE", "OR"));

        List<CreditMemoTax> rows = service.attribute(CREDIT_MEMO_ID, INVOICE_ID, new BigDecimal("5.00"), false);

        assertThat(rows).isEmpty();
        verify(creditMemoTaxRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("Invoice with no replicated breakdown attributes nothing (pre-T5 invoice)")
    void noBreakdown_attributesNothing() {
        when(extInvoiceTaxRepository.findByInvoiceId(INVOICE_ID)).thenReturn(List.of());

        assertThat(service.attribute(CREDIT_MEMO_ID, INVOICE_ID, new BigDecimal("5.00"), true))
                .isEmpty();
        verify(creditMemoTaxRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("Zero reversed tax writes no rows")
    void zeroReversal_writesNothing() {
        assertThat(service.attribute(CREDIT_MEMO_ID, INVOICE_ID, BigDecimal.ZERO, false))
                .isEmpty();
        verify(extInvoiceTaxRepository, never()).findByInvoiceId(any());
    }

    // ===== helpers =====

    private void stubBreakdown(ExtInvoiceTax... rows) {
        when(extInvoiceTaxRepository.findByInvoiceId(INVOICE_ID)).thenReturn(List.of(rows));
    }

    @SuppressWarnings("unchecked")
    private void stubSaveEcho() {
        when(creditMemoTaxRepository.saveAll(any())).thenAnswer(invocation -> {
            List<CreditMemoTax> saved = new ArrayList<>();
            ((Iterable<CreditMemoTax>) invocation.getArgument(0)).forEach(saved::add);
            return saved;
        });
    }

    private static ExtInvoiceTax taxRow(String type, String code, String taxAmount) {
        return ExtInvoiceTax.builder()
                .invoiceId(INVOICE_ID)
                .jurisdictionType(type)
                .jurisdictionCode(code)
                .rate(new BigDecimal("0.075"))
                .taxableBase(new BigDecimal("1000.00"))
                .taxAmount(new BigDecimal(taxAmount))
                .exempt(false)
                .build();
    }

    private static ExtInvoiceTax exemptRow(String type, String code) {
        return ExtInvoiceTax.builder()
                .invoiceId(INVOICE_ID)
                .jurisdictionType(type)
                .jurisdictionCode(code)
                .rate(BigDecimal.ZERO)
                .taxableBase(new BigDecimal("1000.00"))
                .taxAmount(BigDecimal.ZERO)
                .exempt(true)
                .exemptionReasonCode("RESALE")
                .build();
    }

    private static BigDecimal reversedFor(List<CreditMemoTax> rows, String type, String code) {
        return rows.stream()
                .filter(r -> r.getJurisdictionType().equals(type)
                        && r.getJurisdictionCode().equals(code))
                .map(CreditMemoTax::getTaxAmountReversed)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No attribution row for " + type + "/" + code));
    }

    private static BigDecimal sum(List<CreditMemoTax> rows) {
        return rows.stream().map(CreditMemoTax::getTaxAmountReversed).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
