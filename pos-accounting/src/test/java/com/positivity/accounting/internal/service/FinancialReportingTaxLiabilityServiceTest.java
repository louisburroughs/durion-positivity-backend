package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.TaxLiabilityReport;
import com.positivity.accounting.internal.dto.TaxLiabilityRow;
import com.positivity.accounting.internal.entity.CreditMemo;
import com.positivity.accounting.internal.entity.CreditMemoTax;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.ExtInvoiceTax;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.enums.CreditMemoStatus;
import com.positivity.accounting.internal.repository.APPaymentAllocationRepository;
import com.positivity.accounting.internal.repository.AccountingSequenceRepository;
import com.positivity.accounting.internal.repository.CreditMemoRepository;
import com.positivity.accounting.internal.repository.CreditMemoTaxRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceTaxRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.accounting.internal.repository.StatementLineMappingRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link FinancialReportingServiceImpl#generateTaxLiability} (story
 * T8, issue #966): per-jurisdiction aggregation across state/county/city, exempt
 * base + reasons, POSTED credit-memo pro-rata netting, and the GL-drift column.
 */
@ExtendWith(MockitoExtension.class)
class FinancialReportingTaxLiabilityServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-07-01T12:00:00Z");
    private static final LocalDate START = LocalDate.of(2026, 6, 1);
    private static final LocalDate END = LocalDate.of(2026, 6, 30);

    private static final UUID INV1 = UUID.fromString("b1000000-0000-7000-8000-000000000001");
    private static final UUID INV2 = UUID.fromString("b1000000-0000-7000-8000-000000000002");
    private static final UUID TAX_ACCT = UUID.fromString("5eed0acc-0000-4000-8000-000000002200");

    @Mock
    private JournalEntryRepository journalEntryRepository;

    @Mock
    private StatementLineMappingRepository statementLineMappingRepository;

    @Mock
    private AccountingSequenceRepository accountingSequenceRepository;

    @Mock
    private GLAccountRepository glAccountRepository;

    @Mock
    private ExtInvoiceRepository extInvoiceRepository;

    @Mock
    private ExtInvoiceTaxRepository extInvoiceTaxRepository;

    @Mock
    private CreditMemoRepository creditMemoRepository;

    @Mock
    private CreditMemoTaxRepository creditMemoTaxRepository;

    @Mock
    private VendorBillRepository vendorBillRepository;

    @Mock
    private APPaymentAllocationRepository apPaymentAllocationRepository;

    @Mock
    private InvoiceBalanceCalculator invoiceBalanceCalculator;

    private FinancialReportingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FinancialReportingServiceImpl(
                journalEntryRepository,
                statementLineMappingRepository,
                accountingSequenceRepository,
                glAccountRepository,
                extInvoiceRepository,
                extInvoiceTaxRepository,
                creditMemoRepository,
                creditMemoTaxRepository,
                vendorBillRepository,
                apPaymentAllocationRepository,
                invoiceBalanceCalculator,
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("Aggregates per jurisdiction, nets a POSTED credit pro-rata, and reconciles GL drift to zero")
    void aggregatesNetsAndReconciles() {
        ExtInvoice inv1 = invoiceFinalized(INV1);
        ExtInvoice inv2 = invoiceFinalized(INV2);
        when(extInvoiceRepository.findByFinalizedAtBetween(any(), any())).thenReturn(List.of(inv1, inv2));

        // INV1: WA state 65, King county 35, Seattle city 25 (all on 1000 base each), plus a
        // 500 exempt (resale) line on WA. INV2: WA state 130 on 2000 base.
        List<ExtInvoiceTax> master = List.of(
                taxRow(INV1, "STATE", "WA", "1000.00", "65.00", false, null),
                taxRow(INV1, "COUNTY", "KING", "1000.00", "35.00", false, null),
                taxRow(INV1, "CITY", "SEATTLE", "1000.00", "25.00", false, null),
                taxRow(INV1, "STATE", "WA", "500.00", "0.00", true, "RESALE"),
                taxRow(INV2, "STATE", "WA", "2000.00", "130.00", false, null));
        when(extInvoiceTaxRepository.findByInvoiceIdIn(anyCollection())).thenAnswer(call -> {
            Collection<UUID> ids = call.getArgument(0);
            return master.stream().filter(r -> ids.contains(r.getInvoiceId())).toList();
        });

        // One POSTED credit against INV1 reversing 25.00 of tax, posted in-period.
        when(creditMemoRepository.findByStatusNotAndPostedTimestampBetween(eq(CreditMemoStatus.DRAFT), any(), any()))
                .thenReturn(List.of(creditMemo(INV1, "25.00")));

        // GL 2200: Σdebit - Σcredit. Clean ledger -> equals -(report net tax) = -230.00,
        // so credit-normal activity = 230.00 and drift = 0.
        GLAccount taxPayable = new GLAccount();
        taxPayable.setGlAccountId(TAX_ACCT);
        taxPayable.setAccountCode("2200");
        taxPayable.setAccountName("Sales Tax Payable");
        when(glAccountRepository.findByAccountCode("2200")).thenReturn(Optional.of(taxPayable));
        when(journalEntryRepository.sumPostedBalanceForAccount(eq(TAX_ACCT), any(), any()))
                .thenReturn(new BigDecimal("-230.00"));

        TaxLiabilityReport report = service.generateTaxLiability(START, END);

        assertThat(report.getStartDate()).isEqualTo(START);
        assertThat(report.getEndDate()).isEqualTo(END);
        assertThat(report.getGeneratedAt()).isEqualTo(FIXED_NOW);

        // Rows ordered state -> county -> city.
        assertThat(report.getRows())
                .extracting(TaxLiabilityRow::getJurisdictionType, TaxLiabilityRow::getJurisdictionCode)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("STATE", "WA"),
                        org.assertj.core.groups.Tuple.tuple("COUNTY", "KING"),
                        org.assertj.core.groups.Tuple.tuple("CITY", "SEATTLE"));

        TaxLiabilityRow wa = report.getRows().get(0);
        assertThat(wa.getTaxableBase()).isEqualByComparingTo("3000.00"); // 1000 + 2000
        assertThat(wa.getExemptBase()).isEqualByComparingTo("500.00");
        assertThat(wa.getExemptionReasons()).containsExactly("RESALE");
        assertThat(wa.getTaxCollectedGross()).isEqualByComparingTo("195.00"); // 65 + 130
        assertThat(wa.getCreditsNetted()).isEqualByComparingTo("13.00"); // 25 * 65/125
        assertThat(wa.getNetTax()).isEqualByComparingTo("182.00");

        TaxLiabilityRow king = report.getRows().get(1);
        assertThat(king.getTaxCollectedGross()).isEqualByComparingTo("35.00");
        assertThat(king.getCreditsNetted()).isEqualByComparingTo("7.00"); // 25 * 35/125
        assertThat(king.getNetTax()).isEqualByComparingTo("28.00");
        assertThat(king.getExemptBase()).isEqualByComparingTo("0");

        TaxLiabilityRow seattle = report.getRows().get(2);
        assertThat(seattle.getTaxCollectedGross()).isEqualByComparingTo("25.00");
        assertThat(seattle.getCreditsNetted()).isEqualByComparingTo("5.00"); // 25 * 25/125
        assertThat(seattle.getNetTax()).isEqualByComparingTo("20.00");

        assertThat(report.getTotalTaxableBase()).isEqualByComparingTo("5000.00");
        assertThat(report.getTotalExemptBase()).isEqualByComparingTo("500.00");
        assertThat(report.getTotalTaxCollectedGross()).isEqualByComparingTo("255.00");
        assertThat(report.getTotalCreditsNetted()).isEqualByComparingTo("25.00");
        assertThat(report.getTotalNetTax()).isEqualByComparingTo("230.00");

        assertThat(report.getReconciliation().getTaxPayableAccountCode()).isEqualTo("2200");
        assertThat(report.getReconciliation().getGlNetActivity()).isEqualByComparingTo("230.00");
        assertThat(report.getReconciliation().getReportNetTax()).isEqualByComparingTo("230.00");
        assertThat(report.getReconciliation().getDrift()).isEqualByComparingTo("0.00");
        assertThat(report.getReconciliation().getReconciled()).isTrue();
    }

    @Test
    @DisplayName("Empty period yields no rows, zero totals, and a zero-drift reconciliation")
    void emptyPeriod() {
        when(extInvoiceRepository.findByFinalizedAtBetween(any(), any())).thenReturn(List.of());
        when(creditMemoRepository.findByStatusNotAndPostedTimestampBetween(eq(CreditMemoStatus.DRAFT), any(), any()))
                .thenReturn(List.of());
        when(glAccountRepository.findByAccountCode("2200")).thenReturn(Optional.empty());

        TaxLiabilityReport report = service.generateTaxLiability(START, END);

        assertThat(report.getRows()).isEmpty();
        assertThat(report.getTotalNetTax()).isEqualByComparingTo("0");
        assertThat(report.getReconciliation().getGlNetActivity()).isEqualByComparingTo("0");
        assertThat(report.getReconciliation().getDrift()).isEqualByComparingTo("0");
        assertThat(report.getReconciliation().getReconciled()).isTrue();
    }

    @Test
    @DisplayName("Rejects an inverted date range")
    void rejectsInvertedRange() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.generateTaxLiability(END, START))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("#996: a credit's frozen per-jurisdiction breakdown is used verbatim, not the pro-rata split")
    void frozenAttribution_overridesProRata() {
        when(extInvoiceRepository.findByFinalizedAtBetween(any(), any())).thenReturn(List.of(invoiceFinalized(INV1)));

        // Invoice collected 65 STATE / 35 COUNTY: a pro-rata split of 25.00 would be 16.25 / 8.75.
        List<ExtInvoiceTax> master = List.of(
                taxRow(INV1, "STATE", "WA", "1000.00", "65.00", false, null),
                taxRow(INV1, "COUNTY", "KING", "1000.00", "35.00", false, null));
        when(extInvoiceTaxRepository.findByInvoiceIdIn(anyCollection())).thenAnswer(call -> {
            Collection<UUID> ids = call.getArgument(0);
            return master.stream().filter(r -> ids.contains(r.getInvoiceId())).toList();
        });

        CreditMemo credit = creditMemo(INV1, "25.00");
        when(creditMemoRepository.findByStatusNotAndPostedTimestampBetween(eq(CreditMemoStatus.DRAFT), any(), any()))
                .thenReturn(List.of(credit));
        // ...but the credit itself froze a deliberately non-pro-rata 20/5 attribution.
        when(creditMemoTaxRepository.findByCreditMemoIdIn(anyList()))
                .thenReturn(List.of(
                        attribution(credit.getCreditMemoId(), "STATE", "WA", "20.00"),
                        attribution(credit.getCreditMemoId(), "COUNTY", "KING", "5.00")));
        stubTaxPayableActivity("-75.00");

        TaxLiabilityReport report = service.generateTaxLiability(START, END);

        assertThat(report.getRows().get(0).getCreditsNetted()).isEqualByComparingTo("20.00");
        assertThat(report.getRows().get(1).getCreditsNetted()).isEqualByComparingTo("5.00");
        assertThat(report.getTotalNetTax()).isEqualByComparingTo("75.00");
        assertThat(report.getReconciliation().getUnattributedCredits()).isEqualByComparingTo("0");
        assertThat(report.getReconciliation().getDrift()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("#997: a credit that transitioned POSTED -> APPLIED still nets in the period it posted")
    void creditAppliedAfterPosting_stillNets() {
        when(extInvoiceRepository.findByFinalizedAtBetween(any(), any())).thenReturn(List.of(invoiceFinalized(INV1)));
        List<ExtInvoiceTax> master = List.of(taxRow(INV1, "STATE", "WA", "1000.00", "65.00", false, null));
        when(extInvoiceTaxRepository.findByInvoiceIdIn(anyCollection())).thenAnswer(call -> {
            Collection<UUID> ids = call.getArgument(0);
            return master.stream().filter(r -> ids.contains(r.getInvoiceId())).toList();
        });

        // The credit posted Dr 2200 = 25.00 and has since been consumed against another invoice.
        // Its ledger entry is still there, so the report must still count it.
        CreditMemo applied = creditMemo(INV1, "25.00");
        applied.setStatus(CreditMemoStatus.APPLIED);
        when(creditMemoRepository.findByStatusNotAndPostedTimestampBetween(eq(CreditMemoStatus.DRAFT), any(), any()))
                .thenReturn(List.of(applied));
        when(creditMemoTaxRepository.findByCreditMemoIdIn(anyList())).thenReturn(List.of());
        stubTaxPayableActivity("-40.00");

        TaxLiabilityReport report = service.generateTaxLiability(START, END);

        assertThat(report.getTotalCreditsNetted()).isEqualByComparingTo("25.00");
        assertThat(report.getTotalNetTax()).isEqualByComparingTo("40.00");
        assertThat(report.getReconciliation().getDrift()).isEqualByComparingTo("0.00");
        assertThat(report.getReconciliation().getReconciled()).isTrue();
    }

    @Test
    @DisplayName("#996: an unattributable credit is reported as unattributed rather than left as phantom drift")
    void unattributableCredit_isReportedExplicitly() {
        when(extInvoiceRepository.findByFinalizedAtBetween(any(), any())).thenReturn(List.of(invoiceFinalized(INV1)));
        List<ExtInvoiceTax> master = List.of(taxRow(INV1, "STATE", "WA", "1000.00", "65.00", false, null));
        when(extInvoiceTaxRepository.findByInvoiceIdIn(anyCollection())).thenAnswer(call -> {
            Collection<UUID> ids = call.getArgument(0);
            return master.stream().filter(r -> ids.contains(r.getInvoiceId())).toList();
        });

        // Credit against an invoice with no replicated breakdown and no frozen attribution.
        when(creditMemoRepository.findByStatusNotAndPostedTimestampBetween(eq(CreditMemoStatus.DRAFT), any(), any()))
                .thenReturn(List.of(creditMemo(INV2, "12.00")));
        when(creditMemoTaxRepository.findByCreditMemoIdIn(anyList())).thenReturn(List.of());
        stubTaxPayableActivity("-53.00");

        TaxLiabilityReport report = service.generateTaxLiability(START, END);

        assertThat(report.getTotalCreditsNetted()).isEqualByComparingTo("0");
        assertThat(report.getTotalNetTax()).isEqualByComparingTo("65.00");
        // The unattributed 12.00 accounts for the drift exactly, so it is named rather than
        // mysterious — and because it is fully explained, the ledger still reads reconciled
        // (issue #996 AC-3: this edge case must not manufacture phantom drift).
        assertThat(report.getReconciliation().getUnattributedCredits()).isEqualByComparingTo("12.00");
        assertThat(report.getReconciliation().getDrift()).isEqualByComparingTo("12.00");
        assertThat(report.getReconciliation().getReconciled()).isTrue();
    }

    @Test
    @DisplayName("#996: drift beyond the unattributed portion still flags the ledger as unreconciled")
    void unexplainedDrift_stillFlagsUnreconciled() {
        when(extInvoiceRepository.findByFinalizedAtBetween(any(), any())).thenReturn(List.of(invoiceFinalized(INV1)));
        List<ExtInvoiceTax> master = List.of(taxRow(INV1, "STATE", "WA", "1000.00", "65.00", false, null));
        when(extInvoiceTaxRepository.findByInvoiceIdIn(anyCollection())).thenAnswer(call -> {
            Collection<UUID> ids = call.getArgument(0);
            return master.stream().filter(r -> ids.contains(r.getInvoiceId())).toList();
        });
        when(creditMemoRepository.findByStatusNotAndPostedTimestampBetween(eq(CreditMemoStatus.DRAFT), any(), any()))
                .thenReturn(List.of(creditMemo(INV2, "12.00")));
        when(creditMemoTaxRepository.findByCreditMemoIdIn(anyList())).thenReturn(List.of());
        // GL activity is 5.00 short of what even the unattributed credit explains.
        stubTaxPayableActivity("-48.00");

        TaxLiabilityReport report = service.generateTaxLiability(START, END);

        assertThat(report.getReconciliation().getUnattributedCredits()).isEqualByComparingTo("12.00");
        assertThat(report.getReconciliation().getDrift()).isEqualByComparingTo("17.00");
        assertThat(report.getReconciliation().getReconciled()).isFalse();
    }

    // ===== fixtures =====

    private void stubTaxPayableActivity(String postedBalance) {
        GLAccount taxPayable = new GLAccount();
        taxPayable.setGlAccountId(TAX_ACCT);
        taxPayable.setAccountCode("2200");
        taxPayable.setAccountName("Sales Tax Payable");
        when(glAccountRepository.findByAccountCode("2200")).thenReturn(Optional.of(taxPayable));
        when(journalEntryRepository.sumPostedBalanceForAccount(eq(TAX_ACCT), any(), any()))
                .thenReturn(new BigDecimal(postedBalance));
    }

    private static CreditMemoTax attribution(UUID creditMemoId, String type, String code, String reversed) {
        return CreditMemoTax.builder()
                .id(UUID.randomUUID())
                .creditMemoId(creditMemoId)
                .jurisdictionType(type)
                .jurisdictionCode(code)
                .taxAmountReversed(new BigDecimal(reversed))
                .createdAt(FIXED_NOW)
                .build();
    }

    private static ExtInvoice invoiceFinalized(UUID id) {
        return ExtInvoice.builder()
                .invoiceId(id)
                .workorderId(UUID.randomUUID())
                .partyId(UUID.randomUUID().toString())
                .status("FINALIZED")
                .finalizedAt(Instant.parse("2026-06-15T00:00:00Z"))
                .aggregateVersion(1L)
                .updatedAt(FIXED_NOW)
                .build();
    }

    private static ExtInvoiceTax taxRow(
            UUID invoiceId, String type, String code, String base, String tax, boolean exempt, String reason) {
        return ExtInvoiceTax.builder()
                .invoiceId(invoiceId)
                .lineItemId(code + "-line")
                .jurisdictionType(type)
                .jurisdictionCode(code)
                .rate(new BigDecimal("0.065000"))
                .taxableBase(new BigDecimal(base))
                .taxAmount(new BigDecimal(tax))
                .exempt(exempt)
                .exemptionReasonCode(reason)
                .aggregateVersion(1L)
                .updatedAt(FIXED_NOW)
                .build();
    }

    @Test
    @DisplayName("#997 void symmetry: a credit voided in-period restores its reversed tax in the void's period")
    void creditVoidedInPeriod_restoresReversedTax() {
        // Invoice side: 65.00 collected in-period.
        when(extInvoiceRepository.findByFinalizedAtBetween(any(), any())).thenReturn(List.of(invoiceFinalized(INV1)));
        List<ExtInvoiceTax> master = List.of(taxRow(INV1, "STATE", "WA", "1000.00", "65.00", false, null));
        when(extInvoiceTaxRepository.findByInvoiceIdIn(anyCollection())).thenAnswer(call -> {
            Collection<UUID> ids = call.getArgument(0);
            return master.stream().filter(r -> ids.contains(r.getInvoiceId())).toList();
        });

        // The credit posted in a PRIOR period (not selected by the posted-credit query), was
        // voided in THIS period. Its void reversal (Cr 2200 25.00) is on this period's ledger,
        // so the report must restore 25.00 here: creditsNetted -25, net tax 65 + 25 = 90.
        CreditMemo voided = creditMemo(INV1, "25.00");
        voided.setStatus(CreditMemoStatus.VOIDED);
        voided.setPostedTimestamp(Instant.parse("2026-05-15T00:00:00Z"));
        voided.setVoidedTimestamp(Instant.parse("2026-06-20T00:00:00Z"));
        when(creditMemoRepository.findByStatusNotAndPostedTimestampBetween(eq(CreditMemoStatus.DRAFT), any(), any()))
                .thenReturn(List.of());
        when(creditMemoRepository.findByStatusAndVoidedTimestampBetween(eq(CreditMemoStatus.VOIDED), any(), any()))
                .thenReturn(List.of(voided));
        when(creditMemoTaxRepository.findByCreditMemoIdIn(anyList()))
                .thenReturn(List.of(attribution(voided.getCreditMemoId(), "STATE", "WA", "25.00")));
        // Ledger: Cr 2200 65.00 (invoice) + Cr 2200 25.00 (void restoration) = 90.00 net.
        stubTaxPayableActivity("-90.00");

        TaxLiabilityReport report = service.generateTaxLiability(START, END);

        assertThat(report.getRows().get(0).getCreditsNetted()).isEqualByComparingTo("-25.00");
        assertThat(report.getRows().get(0).getNetTax()).isEqualByComparingTo("90.00");
        assertThat(report.getTotalNetTax()).isEqualByComparingTo("90.00");
        assertThat(report.getReconciliation().getUnattributedCredits()).isEqualByComparingTo("0");
        assertThat(report.getReconciliation().getDrift()).isEqualByComparingTo("0.00");
        assertThat(report.getReconciliation().getReconciled()).isTrue();
    }

    @Test
    @DisplayName("#997 void symmetry: posted and voided in the same period nets to zero credit contribution")
    void creditPostedAndVoidedSamePeriod_netsToZero() {
        when(extInvoiceRepository.findByFinalizedAtBetween(any(), any())).thenReturn(List.of(invoiceFinalized(INV1)));
        List<ExtInvoiceTax> master = List.of(taxRow(INV1, "STATE", "WA", "1000.00", "65.00", false, null));
        when(extInvoiceTaxRepository.findByInvoiceIdIn(anyCollection())).thenAnswer(call -> {
            Collection<UUID> ids = call.getArgument(0);
            return master.stream().filter(r -> ids.contains(r.getInvoiceId())).toList();
        });

        CreditMemo voided = creditMemo(INV1, "25.00");
        voided.setStatus(CreditMemoStatus.VOIDED);
        voided.setVoidedTimestamp(Instant.parse("2026-06-25T00:00:00Z"));
        // Selected by BOTH queries: posted in-period (status != DRAFT) and voided in-period.
        when(creditMemoRepository.findByStatusNotAndPostedTimestampBetween(eq(CreditMemoStatus.DRAFT), any(), any()))
                .thenReturn(List.of(voided));
        when(creditMemoRepository.findByStatusAndVoidedTimestampBetween(eq(CreditMemoStatus.VOIDED), any(), any()))
                .thenReturn(List.of(voided));
        when(creditMemoTaxRepository.findByCreditMemoIdIn(anyList()))
                .thenReturn(List.of(attribution(voided.getCreditMemoId(), "STATE", "WA", "25.00")));
        // Ledger: Cr 65 (invoice) + Dr 25 (posting) + Cr 25 (void) = 65 net.
        stubTaxPayableActivity("-65.00");

        TaxLiabilityReport report = service.generateTaxLiability(START, END);

        assertThat(report.getRows().get(0).getCreditsNetted()).isEqualByComparingTo("0.00");
        assertThat(report.getTotalNetTax()).isEqualByComparingTo("65.00");
        assertThat(report.getReconciliation().getDrift()).isEqualByComparingTo("0.00");
    }

    private static CreditMemo creditMemo(UUID originalInvoiceId, String taxReversed) {
        CreditMemo credit = new CreditMemo();
        credit.setCreditMemoId(UUID.randomUUID());
        credit.setOriginalInvoiceId(originalInvoiceId);
        credit.setCustomerId(UUID.randomUUID());
        credit.setCreditAmount(new BigDecimal("100.00"));
        credit.setTaxAmountReversed(new BigDecimal(taxReversed));
        credit.setReasonCode("RETURN");
        credit.setStatus(CreditMemoStatus.POSTED);
        credit.setCreationTimestamp(Instant.parse("2026-06-20T00:00:00Z"));
        credit.setPostedTimestamp(Instant.parse("2026-06-20T00:00:00Z"));
        credit.setCreatedByUserId("t8-test");
        credit.setCurrency("USD");
        return credit;
    }
}
