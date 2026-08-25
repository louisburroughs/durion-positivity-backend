package com.positivity.invoice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.positivity.invoice.internal.entity.InvoiceLineTax;
import com.positivity.invoice.internal.entity.InvoiceTaxSummary;
import com.positivity.invoice.internal.repository.InvoiceLineTaxRepository;
import com.positivity.invoice.internal.repository.InvoiceTaxSummaryRepository;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import com.positivity.tax.common.dto.TaxCalculationResponse.JurisdictionTax;
import com.positivity.tax.common.dto.TaxCalculationResponse.LineItemTax;
import com.positivity.tax.common.enums.ExemptionReasonCode;
import com.positivity.tax.common.enums.TaxJurisdictionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class InvoiceTaxBreakdownWriterTest {

    private static final UUID INVOICE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final InvoiceLineTaxRepository lineTaxRepository = mock(InvoiceLineTaxRepository.class);
    private final InvoiceTaxSummaryRepository taxSummaryRepository = mock(InvoiceTaxSummaryRepository.class);
    private final InvoiceTaxBreakdownWriter writer =
            new InvoiceTaxBreakdownWriter(lineTaxRepository, taxSummaryRepository);

    private static JurisdictionTax jurisdiction(TaxJurisdictionType type, String rate, String amount) {
        return JurisdictionTax.builder()
                .jurisdictionType(type)
                .code(type.code())
                .rate(new BigDecimal(rate))
                .amount(new BigDecimal(amount))
                .build();
    }

    private static TaxCalculationResponse response(List<LineItemTax> lines) {
        return TaxCalculationResponse.builder()
                .subtotal(BigDecimal.ZERO)
                .totalTax(BigDecimal.ZERO)
                .total(BigDecimal.ZERO)
                .effectiveTaxRate(BigDecimal.ZERO)
                .jurisdictions(List.of())
                .lineItemTaxes(lines)
                .calculatedAt(Instant.parse("2026-07-20T00:00:00Z"))
                .build();
    }

    @Test
    @DisplayName("Deletes existing rows first (wholesale replace) on every call")
    void deletesBeforeInsert() {
        writer.replace(INVOICE_ID, null);
        verify(lineTaxRepository).deleteByInvoiceId(INVOICE_ID);
        verify(taxSummaryRepository).deleteByInvoiceId(INVOICE_ID);
    }

    @Test
    @DisplayName("Maps each line x jurisdiction to a line-tax row and rolls up per jurisdiction")
    void mapsLinesAndBuildsSummary() {
        LineItemTax line1 = LineItemTax.builder()
                .lineItemId("1")
                .subtotal(new BigDecimal("100.00"))
                .taxAmount(new BigDecimal("8.27"))
                .total(new BigDecimal("108.27"))
                .jurisdictions(List.of(
                        jurisdiction(TaxJurisdictionType.STATE, "0.0725", "7.25"),
                        jurisdiction(TaxJurisdictionType.COUNTY, "0.0102", "1.02")))
                .build();
        LineItemTax line2 = LineItemTax.builder()
                .lineItemId("2")
                .subtotal(new BigDecimal("50.00"))
                .taxAmount(new BigDecimal("3.63"))
                .total(new BigDecimal("53.63"))
                .jurisdictions(List.of(jurisdiction(TaxJurisdictionType.STATE, "0.0725", "3.63")))
                .build();

        writer.replace(INVOICE_ID, response(List.of(line1, line2)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<InvoiceLineTax>> lineRows = ArgumentCaptor.forClass(List.class);
        verify(lineTaxRepository).saveAll(lineRows.capture());
        assertThat(lineRows.getValue()).hasSize(3);
        assertThat(lineRows.getValue())
                .allSatisfy(r -> assertThat(r.getInvoiceId()).isEqualTo(INVOICE_ID));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<InvoiceTaxSummary>> summaryRows = ArgumentCaptor.forClass(List.class);
        verify(taxSummaryRepository).saveAll(summaryRows.capture());
        // STATE rolls up across both lines (7.25 + 3.63), COUNTY only from line 1.
        assertThat(summaryRows.getValue()).hasSize(2);
        InvoiceTaxSummary state = summaryRows.getValue().stream()
                .filter(s -> "STATE".equals(s.getJurisdictionType()))
                .findFirst()
                .orElseThrow();
        assertThat(state.getTaxAmount()).isEqualByComparingTo("10.88");
        assertThat(state.getTaxableBase()).isEqualByComparingTo("150.00");
    }

    @Test
    @DisplayName("Preserves the exempt flag and reason on zero-rate rows")
    void preservesExemption() {
        LineItemTax exemptLine = LineItemTax.builder()
                .lineItemId("1")
                .subtotal(new BigDecimal("100.00"))
                .taxAmount(BigDecimal.ZERO)
                .total(new BigDecimal("100.00"))
                .taxExempt(true)
                .jurisdictions(List.of(JurisdictionTax.builder()
                        .jurisdictionType(TaxJurisdictionType.STATE)
                        .code("STATE")
                        .rate(new BigDecimal("0.0725"))
                        .amount(BigDecimal.ZERO)
                        .exempt(true)
                        .exemptionReasonCode(ExemptionReasonCode.RESALE)
                        .build()))
                .build();

        writer.replace(INVOICE_ID, response(List.of(exemptLine)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<InvoiceLineTax>> lineRows = ArgumentCaptor.forClass(List.class);
        verify(lineTaxRepository).saveAll(lineRows.capture());
        InvoiceLineTax row = lineRows.getValue().get(0);
        assertThat(row.isExempt()).isTrue();
        assertThat(row.getExemptionReasonCode()).isEqualTo("RESALE");
        assertThat(row.getTaxAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Null response clears rows without inserting")
    void nullResponseClearsOnly() {
        writer.replace(INVOICE_ID, null);
        verify(lineTaxRepository, org.mockito.Mockito.never()).saveAll(any());
        verify(taxSummaryRepository, org.mockito.Mockito.never()).saveAll(any());
    }

    @Test
    @DisplayName("Idempotent replay yields identical rows on re-price")
    void idempotentReplace() {
        LineItemTax line = LineItemTax.builder()
                .lineItemId("1")
                .subtotal(new BigDecimal("100.00"))
                .taxAmount(new BigDecimal("7.25"))
                .total(new BigDecimal("107.25"))
                .jurisdictions(List.of(jurisdiction(TaxJurisdictionType.STATE, "0.0725", "7.25")))
                .build();

        writer.replace(INVOICE_ID, response(List.of(line)));
        writer.replace(INVOICE_ID, response(List.of(line)));

        // Each re-price deletes then re-inserts wholesale, so replays converge on identical rows.
        verify(lineTaxRepository, org.mockito.Mockito.times(2)).deleteByInvoiceId(INVOICE_ID);
        verify(lineTaxRepository, org.mockito.Mockito.times(2)).saveAll(any());
    }

    @Test
    @DisplayName("A non-null response with a null lineItemTaxes list only clears, like a null response")
    void responseWithNullLineItemTaxes_clearsWithoutInserting() {
        TaxCalculationResponse response = TaxCalculationResponse.builder()
                .subtotal(BigDecimal.ZERO)
                .totalTax(BigDecimal.ZERO)
                .total(BigDecimal.ZERO)
                .effectiveTaxRate(BigDecimal.ZERO)
                .jurisdictions(List.of())
                .lineItemTaxes(null)
                .calculatedAt(Instant.parse("2026-07-20T00:00:00Z"))
                .build();

        writer.replace(INVOICE_ID, response);

        verify(lineTaxRepository, org.mockito.Mockito.never()).saveAll(any());
        verify(taxSummaryRepository, org.mockito.Mockito.never()).saveAll(any());
    }

    @Test
    @DisplayName("A line with no jurisdictions list contributes no rows, without disturbing sibling lines")
    void lineWithNullJurisdictions_skipsLineEntirely() {
        LineItemTax untaxedLine = LineItemTax.builder()
                .lineItemId("1")
                .subtotal(new BigDecimal("25.00"))
                .taxAmount(BigDecimal.ZERO)
                .total(new BigDecimal("25.00"))
                .jurisdictions(null)
                .build();
        LineItemTax taxedLine = LineItemTax.builder()
                .lineItemId("2")
                .subtotal(new BigDecimal("100.00"))
                .taxAmount(new BigDecimal("7.25"))
                .total(new BigDecimal("107.25"))
                .jurisdictions(List.of(jurisdiction(TaxJurisdictionType.STATE, "0.0725", "7.25")))
                .build();

        writer.replace(INVOICE_ID, response(List.of(untaxedLine, taxedLine)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<InvoiceLineTax>> lineRows = ArgumentCaptor.forClass(List.class);
        verify(lineTaxRepository).saveAll(lineRows.capture());
        assertThat(lineRows.getValue()).hasSize(1);
        assertThat(lineRows.getValue().get(0).getLineItemId()).isEqualTo("2");
    }

    @Test
    @DisplayName(
            "Jurisdiction rows missing type, code, or rate are each skipped defensively; an all-skipped line saves nothing")
    void incompleteJurisdictionIdentity_skipsEachDefensivelyAndSavesNothingWhenAllInvalid() {
        JurisdictionTax missingType = JurisdictionTax.builder()
                .jurisdictionType(null)
                .code("STATE")
                .rate(new BigDecimal("0.0725"))
                .amount(new BigDecimal("7.25"))
                .build();
        JurisdictionTax missingCode = JurisdictionTax.builder()
                .jurisdictionType(TaxJurisdictionType.COUNTY)
                .code(null)
                .rate(new BigDecimal("0.0102"))
                .amount(new BigDecimal("1.02"))
                .build();
        JurisdictionTax missingRate = JurisdictionTax.builder()
                .jurisdictionType(TaxJurisdictionType.CITY)
                .code("CITY")
                .rate(null)
                .amount(new BigDecimal("0.50"))
                .build();
        LineItemTax line = LineItemTax.builder()
                .lineItemId("1")
                .subtotal(new BigDecimal("100.00"))
                .taxAmount(new BigDecimal("8.77"))
                .total(new BigDecimal("108.77"))
                .jurisdictions(List.of(missingType, missingCode, missingRate))
                .build();

        writer.replace(INVOICE_ID, response(List.of(line)));

        // Every jurisdiction on the line was defensively skipped, so nothing is left to persist —
        // a malformed pos-tax response must not silently write partial/garbage rows.
        verify(lineTaxRepository, org.mockito.Mockito.never()).saveAll(any());
        verify(taxSummaryRepository, org.mockito.Mockito.never()).saveAll(any());
    }

    @Test
    @DisplayName("Missing line subtotal or jurisdiction amount reconciles to zero rather than NPE-ing")
    void nullMoneyValuesOnJurisdiction_defaultToZero() {
        JurisdictionTax jurisdictionWithNullAmount = JurisdictionTax.builder()
                .jurisdictionType(TaxJurisdictionType.STATE)
                .code("STATE")
                .rate(new BigDecimal("0.0725"))
                .amount(null)
                .build();
        LineItemTax lineWithNullSubtotal = LineItemTax.builder()
                .lineItemId("1")
                .subtotal(null)
                .taxAmount(BigDecimal.ZERO)
                .total(BigDecimal.ZERO)
                .jurisdictions(List.of(jurisdictionWithNullAmount))
                .build();

        writer.replace(INVOICE_ID, response(List.of(lineWithNullSubtotal)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<InvoiceLineTax>> lineRows = ArgumentCaptor.forClass(List.class);
        verify(lineTaxRepository).saveAll(lineRows.capture());
        InvoiceLineTax row = lineRows.getValue().get(0);
        assertThat(row.getTaxableBase()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(row.getTaxAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
