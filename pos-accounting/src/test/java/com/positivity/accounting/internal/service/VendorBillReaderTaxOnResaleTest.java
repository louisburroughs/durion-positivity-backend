package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.client.TaxReferenceClient;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.VendorBillReview;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.TaxOnResaleOverrideSource;
import com.positivity.accounting.internal.enums.VendorBillCheckOutcome;
import com.positivity.accounting.internal.enums.VendorBillDebitClass;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.exception.TaxServiceUnavailableException;
import com.positivity.accounting.internal.repository.APPaymentAllocationRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.accounting.internal.repository.VendorBillGlPostingRepository;
import com.positivity.accounting.internal.repository.VendorBillLineRepository;
import com.positivity.accounting.internal.repository.VendorBillMatchCandidateRepository;
import com.positivity.accounting.internal.repository.VendorBillMatchEvidenceRepository;
import com.positivity.accounting.internal.repository.VendorBillReissueRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The bill read's {@code TAX_ON_RESALE_GOODS} check (CAP:550 S43, AW44, ruling 9): FAIL, PASS by the vendor setting or
 * a per-bill override, NOT_APPLICABLE otherwise and, with {@code rulesUnavailable}, when pos-tax gives no rules. Tax
 * country {@code ZZ}, fixture rules, not tax law.
 */
@DisplayName("VendorBillReader: the TAX_ON_RESALE_GOODS check (S43)")
class VendorBillReaderTaxOnResaleTest {

    private static final UUID VENDOR = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4b01");

    private final TaxReferenceClient client = mock();
    private final SupplierVendorCopies vendorCopies = mock();
    private final AccountingCalendarZoneResolver zoneResolver = mock();
    private VendorBillReader reader;
    private VendorBill bill;

    @BeforeEach
    void wire() {
        reader = new VendorBillReader(
                Clock.systemUTC(),
                mock(VendorBillRepository.class),
                mock(VendorBillLineRepository.class),
                mock(VendorBillMatchEvidenceRepository.class),
                mock(VendorBillMatchCandidateRepository.class),
                mock(VendorBillGlPostingRepository.class),
                mock(VendorBillReissueRepository.class),
                mock(APPaymentAllocationRepository.class),
                mock(JournalEntryRepository.class),
                zoneResolver,
                new LedgerCurrency("USD"),
                mock(ApApprovalPolicy.class),
                vendorCopies,
                PurchaseTaxFixtures.purchaseTax(client, vendorCopies, mock(VendorBillLineRepository.class)));
        when(zoneResolver.today()).thenReturn(LocalDate.of(2026, 10, 8));
        when(client.purchaseRules(any(), any())).thenReturn(PurchaseTaxFixtures.HOLD_AND_SELF_ASSESS);
        bill = new VendorBill(UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4b02"));
        bill.setVendorId(VENDOR);
        bill.setBillNumber("INV-43");
        bill.setCurrency("USD");
        bill.setStatus(VendorBillStatus.AWAITING_APPROVAL);
        bill.setTotalAmount(new BigDecimal("428.00"));
        bill.setNetAmount(new BigDecimal("400.00"));
        bill.setTaxAmount(new BigDecimal("28.00"));
        bill.setStatedLineCount(1);
        bill.setProposedDebitClass(VendorBillDebitClass.GOODS);
    }

    private VendorBillReview.Check check() {
        VendorBillReview.Check check = reader.taxOnResaleGoods(bill, List.of());
        assertThat(check.code()).isEqualTo("TAX_ON_RESALE_GOODS");
        return check;
    }

    @Test
    @DisplayName("FAIL with taxAmount and currencyCode while in review, the rule HOLD, the bill qualifying and the"
            + " vendor setting off")
    void fails() {
        assertThat(check().outcome()).isEqualTo(VendorBillCheckOutcome.FAIL);
        assertThat(check().args()).containsEntry("taxAmount", "28.00").containsEntry("currencyCode", "USD");
    }

    @Test
    @DisplayName("PASS acceptedBy VENDOR_SETTING while in review with the vendor setting on")
    void passesByTheVendorSetting() {
        when(vendorCopies.acceptsTaxOnResaleGoods(VENDOR)).thenReturn(true);

        assertThat(check().outcome()).isEqualTo(VendorBillCheckOutcome.PASS);
        assertThat(check().args()).containsEntry("acceptedBy", "VENDOR_SETTING");
    }

    @Test
    @DisplayName("PASS acceptedBy BILL on an approved bill overridden per bill, without asking pos-tax")
    void passesByTheBill() {
        bill.setStatus(VendorBillStatus.APPROVED);
        bill.setTaxOnResaleOverride(TaxOnResaleOverrideSource.BILL);
        bill.setTaxOnResaleOverrideJustification("Vendor resale certificate pending");

        assertThat(check().outcome()).isEqualTo(VendorBillCheckOutcome.PASS);
        assertThat(check().args()).containsEntry("acceptedBy", "BILL").doesNotContainKey("justification");
        verify(client, never()).purchaseRules(any(), any());
    }

    @Test
    @DisplayName("NOT_APPLICABLE for a credit note, a bill without qualifying tax, rule ALLOW, or a status outside"
            + " review")
    void notApplicable() {
        bill.setStatus(VendorBillStatus.APPROVED);
        assertThat(check().outcome()).isEqualTo(VendorBillCheckOutcome.NOT_APPLICABLE);

        bill.setStatus(VendorBillStatus.AWAITING_APPROVAL);
        bill.setTaxAmount(new BigDecimal("0.00"));
        bill.setTotalAmount(new BigDecimal("400.00"));
        assertThat(check().outcome()).isEqualTo(VendorBillCheckOutcome.NOT_APPLICABLE);

        bill.setTotalAmount(new BigDecimal("-53.50"));
        bill.setNetAmount(new BigDecimal("-50.00"));
        bill.setTaxAmount(new BigDecimal("-3.50"));
        assertThat(check().outcome()).isEqualTo(VendorBillCheckOutcome.NOT_APPLICABLE);
        verify(client, never()).purchaseRules(any(), any());

        bill.setTotalAmount(new BigDecimal("428.00"));
        bill.setNetAmount(new BigDecimal("400.00"));
        bill.setTaxAmount(new BigDecimal("28.00"));
        when(client.purchaseRules(any(), any())).thenReturn(PurchaseTaxFixtures.OFF);
        assertThat(check().outcome()).isEqualTo(VendorBillCheckOutcome.NOT_APPLICABLE);
        assertThat(check().args()).isEmpty();
    }

    @Test
    @DisplayName("AC7: NOT_APPLICABLE rulesUnavailable true when pos-tax gives no rules; the read still succeeds")
    void rulesUnavailable() {
        when(client.purchaseRules(any(), any())).thenThrow(new TaxServiceUnavailableException("unavailable"));

        assertThat(check().outcome()).isEqualTo(VendorBillCheckOutcome.NOT_APPLICABLE);
        assertThat(check().args()).containsEntry("rulesUnavailable", "true");
    }
}
