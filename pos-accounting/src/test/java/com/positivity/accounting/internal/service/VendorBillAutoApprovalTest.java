package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.client.TaxReferenceClient;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.TaxUseQuote;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.entity.VendorBillGlPosting;
import com.positivity.accounting.internal.entity.VendorBillLine;
import com.positivity.accounting.internal.entity.VendorBillMatchEvidence;
import com.positivity.accounting.internal.enums.TaxOnResaleOverrideSource;
import com.positivity.accounting.internal.enums.VendorBillApproverKind;
import com.positivity.accounting.internal.enums.VendorBillPostingDateRule;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.exception.AccountingPeriodClosedException;
import com.positivity.accounting.internal.exception.GLMappingNotConfiguredException;
import com.positivity.accounting.internal.exception.TaxQuoteRefusedException;
import com.positivity.accounting.internal.exception.TaxServiceUnavailableException;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.VendorBillLineRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Automatic approval on a HIGH {@code /match} (CAP:550 S13, #2510; §4.3, §7.1 "System approver"; AW37, AW42; AC1,
 * AC5, AC15): eligible within min(automatic, clerk) limit, approved by SYSTEM and posted; skipped, the match kept, on
 * anything that would need a person or refuse the posting. The match transaction staying committable is
 * VendorBillApprovalLimitsPostgresIT's.
 */
@DisplayName("VendorBillAutoApproval: eligibility, approval and skips (#2510)")
class VendorBillAutoApprovalTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T15:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate INVOICE_DATE = LocalDate.of(2026, 10, 2);

    private final ApApprovalPolicy policy = mock();
    private final VendorBillPostingService postingService = mock();
    private final AccountingPeriodGate periodGate = mock();
    private final VendorBillRepository bills = mock();
    private final VendorBillLineRepository billLines = mock();
    private final AccountingAuditLogRepository auditLogs = mock();
    private final SupplierVendorCopies vendorCopies = mock();
    private VendorBillAutoApproval autoApproval;
    private VendorBill bill;
    private VendorBillMatchEvidence evidence;

    @BeforeEach
    void wire() {
        autoApproval = new VendorBillAutoApproval(
                CLOCK,
                policy,
                postingService,
                periodGate,
                bills,
                billLines,
                auditLogs,
                new LedgerCurrency("USD"),
                vendorCopies,
                PurchaseTaxFixtures.off(),
                mock(PlatformTransactionManager.class));
        bill = new VendorBill(UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4d01"));
        bill.setBillNumber("INV-7");
        bill.setBillDate(INVOICE_DATE.atStartOfDay());
        bill.setStatus(VendorBillStatus.AWAITING_APPROVAL);
        bill.setSubmittedBy("SYSTEM");
        bill.setOriginEventType(VendorBillReader.ORIGIN_GOODS_RECEIVED);
        evidence = new VendorBillMatchEvidence();
        evidence.setScore(95);
        limits("300.00", "500.00");
        when(postingService.postingDate(any()))
                .thenReturn(
                        new VendorBillPostingService.PostingDate(INVOICE_DATE, VendorBillPostingDateRule.BILL_DATE));
        when(postingService.post(any(), any(), any(), anyString(), any())).thenAnswer(inv -> posting());
        when(bills.save(any(VendorBill.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void limits(String clerk, String auto) {
        when(policy.forDecision())
                .thenReturn(new ApApprovalPolicy.Settings(
                        new BigDecimal(clerk), new BigDecimal(auto), false, false, "NET30"));
    }

    private static VendorBillGlPosting posting() {
        VendorBillGlPosting posting = new VendorBillGlPosting();
        posting.setJournalEntryId(UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4d10"));
        posting.setPostingDate(INVOICE_DATE);
        posting.setPostingDateRule(VendorBillPostingDateRule.BILL_DATE);
        posting.setRoundingAdjustment(new BigDecimal("0.00"));
        return posting;
    }

    private void billed(String total, boolean stocked) {
        bill.setTotalAmount(new BigDecimal(total));
        VendorBillLine line = new VendorBillLine();
        line.setInventoryItem(stocked);
        line.setQuantity(new BigDecimal("1"));
        line.setUnitPrice(new BigDecimal(total));
        line.setBilledQuantity(new BigDecimal("1"));
        line.setBilledUnitPrice(new BigDecimal(total));
        when(billLines.findByVendorBill_VendorBillIdOrderByLineNumber(bill.getVendorBillId()))
                .thenReturn(List.of(line));
    }

    private AccountingAuditLog onlyAudit() {
        ArgumentCaptor<AccountingAuditLog> row = ArgumentCaptor.forClass(AccountingAuditLog.class);
        verify(auditLogs).save(row.capture());
        return row.getValue();
    }

    @Test
    @DisplayName(
            "AC5: 250.00 of stocked lines within min(500.00, 300.00) is APPROVED by SYSTEM, posted with no person's"
                    + " input, and the audit shows the 300.00 limit in force")
    void approvesWithinTheLimit() {
        billed("250.00", true);

        assertThat(autoApproval.approveIfEligible(bill, evidence, 95)).isTrue();

        assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.APPROVED);
        assertThat(bill.getApprovedBy()).isEqualTo("SYSTEM");
        assertThat(bill.getApprovedByKind()).isEqualTo(VendorBillApproverKind.SYSTEM);
        assertThat(bill.getApprovedAt()).isEqualTo(CLOCK.instant());
        assertThat(bill.getApprovalJustification())
                .isEqualTo("Approved automatically: match score 95 (strong >= 70), total 250.00 <= automatic limit"
                        + " 300.00");
        verify(postingService)
                .post(
                        eq(bill),
                        eq(new VendorBillPostingService.Classification(null, null)),
                        eq(null),
                        eq("SYSTEM"),
                        any());
        AccountingAuditLog audit = onlyAudit();
        assertThat(audit.getOperation()).isEqualTo("VENDOR_BILL_AUTO_APPROVE");
        assertThat(audit.getUserId()).isEqualTo("SYSTEM");
        assertThat(audit.getNewValue())
                .contains("limit=300.00", "autoLimit=500.00", "limitApplied=300.00", "matchScore=95", "postingDate=");
    }

    @Test
    @DisplayName("S24 AC 9: the automatic approval stamps the vendor's current remit-to version")
    void stampsTheRemitToVersion() {
        billed("250.00", true);
        bill.setVendorId(UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4d02"));
        when(vendorCopies.remitToVersion(bill.getVendorId())).thenReturn(4);

        assertThat(autoApproval.approveIfEligible(bill, evidence, 95)).isTrue();

        assertThat(bill.getApprovedRemitToVersion()).isEqualTo(4);
    }

    @Test
    @DisplayName("S24 ruling 1: a non-stock line is approved automatically once its vendor has a default key")
    void vendorDefaultKeyClassesNonStockLines() {
        limits("500.00", "500.00");
        billed("200.00", false);
        bill.setVendorId(UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4d02"));
        VendorBillPostingService.Classification defaults =
                new VendorBillPostingService.Classification(null, "EXPENSE_SHOP_SUPPLIES");
        when(vendorCopies.apDefaults(bill.getVendorId())).thenReturn(defaults);

        assertThat(autoApproval.approveIfEligible(bill, evidence, 95)).isTrue();

        verify(postingService).post(eq(bill), eq(defaults), eq(null), eq("SYSTEM"), any());
    }

    @Test
    @DisplayName("AC5: 400.00 over min(500.00, 300.00) stays AWAITING_APPROVAL: no posting, no row")
    void overTheAppliedLimitIsNotEligible() {
        billed("400.00", true);

        assertThat(autoApproval.approveIfEligible(bill, evidence, 95)).isFalse();

        assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.AWAITING_APPROVAL);
        assertThat(bill.getApprovedBy()).isNull();
        verify(postingService, never()).post(any(), any(), any(), anyString(), any());
        verify(auditLogs, never()).save(any());
    }

    @Test
    @DisplayName("AC1: with no policy rows (automatic limit 0) a 10.00 bill is never approved automatically")
    void defaultIsOff() {
        limits("0.00", "0.00");
        billed("10.00", true);
        assertThat(autoApproval.approveIfEligible(bill, evidence, 95)).isFalse();
        verify(postingService, never()).post(any(), any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("AC15: a non-stock line with no classification skips with AP_BILL_UNCLASSIFIED: AWAITING_APPROVAL,"
            + " submitted by SYSTEM, no approval field, no entry")
    void unclassifiedSkips() {
        limits("500.00", "500.00");
        billed("200.00", false);

        assertThat(autoApproval.approveIfEligible(bill, evidence, 95)).isFalse();

        assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.AWAITING_APPROVAL);
        assertThat(bill.getSubmittedBy()).isEqualTo("SYSTEM");
        assertThat(bill.getApprovedBy()).isNull();
        assertThat(bill.getApprovedByKind()).isNull();
        verify(postingService, never()).post(any(), any(), any(), anyString(), any());
        AccountingAuditLog audit = onlyAudit();
        assertThat(audit.getOperation()).isEqualTo("VENDOR_BILL_AUTO_APPROVE_SKIPPED");
        assertThat(audit.getNewValue()).contains("code=AP_BILL_UNCLASSIFIED");
    }

    @Test
    @DisplayName("AC15: the posting date's period CLOSED skips with PERIOD_CLOSED; hard-locked with PERIOD_HARD_LOCKED")
    void closedPeriodSkips() {
        limits("500.00", "500.00");
        billed("200.00", true);
        LocalDate today = LocalDate.of(2026, 10, 8);
        when(postingService.postingDate(any()))
                .thenReturn(new VendorBillPostingService.PostingDate(
                        today, VendorBillPostingDateRule.APPROVAL_DATE_BILL_PERIOD_NOT_OPEN));
        when(periodGate.isPostingBlocked(today)).thenReturn(true);

        assertThat(autoApproval.approveIfEligible(bill, evidence, 95)).isFalse();
        assertThat(onlyAudit().getNewValue()).contains("code=PERIOD_CLOSED");

        when(periodGate.isHardLocked(today)).thenReturn(true);
        assertThat(autoApproval.precheck(bill).refusal())
                .get()
                .satisfies(
                        e -> assertThat(VendorBillApprovalServiceImpl.codeOf(e)).isEqualTo("PERIOD_HARD_LOCKED"));
        verify(postingService, never()).post(any(), any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("A missing mapping skips with GL_MAPPING_NOT_CONFIGURED; the posting is never attempted")
    void missingMappingSkips() {
        limits("500.00", "500.00");
        billed("200.00", true);
        doThrow(new GLMappingNotConfiguredException("no mapping", "VENDOR_BILL", "GOODS_RECEIVED_NOT_BILLED", "map it"))
                .when(postingService)
                .requireMapped(any(), any(), any());

        assertThat(autoApproval.approveIfEligible(bill, evidence, 95)).isFalse();

        assertThat(onlyAudit().getNewValue()).contains("code=GL_MAPPING_NOT_CONFIGURED");
        verify(postingService, never()).post(any(), any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("A 0.00 total skips with AP_BILL_ZERO_TOTAL")
    void zeroTotalSkips() {
        limits("500.00", "500.00");
        bill.setTotalAmount(new BigDecimal("0.00"));
        when(billLines.findByVendorBill_VendorBillIdOrderByLineNumber(bill.getVendorBillId()))
                .thenReturn(List.of());
        // 0.00 is within any limit above 0; the skip is the content guard's.
        assertThat(autoApproval.approveIfEligible(bill, evidence, 95)).isFalse();
        assertThat(onlyAudit().getNewValue()).contains("code=AP_BILL_ZERO_TOTAL");
    }

    @Test
    @DisplayName("The pre-check passes: refusal() is empty and writes nothing")
    void precheckPasses() {
        billed("250.00", true);
        assertThat(autoApproval.precheck(bill).refusal()).isEmpty();
        verify(auditLogs, never()).save(any());
        LocalDateTime unchanged = bill.getBillDate();
        assertThat(unchanged).isEqualTo(INVOICE_DATE.atStartOfDay());
    }

    @Test
    @DisplayName("Ruling 6063520413 item 3: the pre-check passes, then the posting refuses (PERIOD_CLOSED in the race):"
            + " the exception leaves approveIfEligible uncaught, the bill is not APPROVED and no row is saved")
    void raceRefusalPropagates() {
        billed("250.00", true);
        AccountingPeriodClosedException closed = new AccountingPeriodClosedException("2026-10", "closed meanwhile");
        when(postingService.post(any(), any(), any(), anyString(), any())).thenThrow(closed);

        assertThatThrownBy(() -> autoApproval.approveIfEligible(bill, evidence, 95))
                .isSameAs(closed);

        assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.AWAITING_APPROVAL);
        assertThat(bill.getApprovedBy()).isNull();
        assertThat(bill.getApprovedByKind()).isNull();
        verify(bills, never()).save(any());
        verify(auditLogs, never()).save(any());
    }

    @Test
    @DisplayName("#2615 AC4 [M]: a HIGH /match of a held vendor's 200.00 stocked bill is still APPROVED by SYSTEM; the"
            + " hold stops payment only")
    void heldVendorIsStillApproved() {
        billed("200.00", true);
        when(vendorCopies.apHold(any()))
                .thenReturn(Optional.of(new SupplierVendorCopies.ApHold(
                        "V-002615", "Disputed delivery 4471, awaiting credit", CLOCK.instant())));
        when(vendorCopies.heldVendors(any())).thenReturn(Map.of(UUID.randomUUID(), "held"));
        doThrow(new VendorBillException(VendorBillException.Code.VENDOR_ON_AP_HOLD, "Vendor V-002615 is on AP hold"))
                .when(vendorCopies)
                .requireNotOnHold(any(), any());

        assertThat(autoApproval.approveIfEligible(bill, evidence, 95)).isTrue();

        assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.APPROVED);
        assertThat(bill.getApprovedBy()).isEqualTo("SYSTEM");
    }

    @Nested
    @DisplayName("S43 (#2604, AW44): purchase tax in automatic approval; tax country ZZ, rule fixture HOLD /"
            + " self-assess, use tax 8.5 % (not tax law)")
    class PurchaseTax {

        private final TaxReferenceClient client = mock();
        private final UUID vendor = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4d02");

        @BeforeEach
        void wirePurchaseTax() {
            autoApproval = new VendorBillAutoApproval(
                    CLOCK,
                    policy,
                    postingService,
                    periodGate,
                    bills,
                    billLines,
                    auditLogs,
                    new LedgerCurrency("USD"),
                    vendorCopies,
                    PurchaseTaxFixtures.purchaseTax(client, vendorCopies, billLines, CLOCK),
                    mock(PlatformTransactionManager.class));
            when(client.purchaseRules(any(), any())).thenReturn(PurchaseTaxFixtures.HOLD_AND_SELF_ASSESS);
            bill.setVendorId(vendor);
            limits("500.00", "500.00");
        }

        /** A goods-receipt bill of one stocked line billed at 400.00 with a stated tax of 28.00. */
        private void taxedGoods() {
            billed("428.00", true);
            VendorBillLine line = billLines
                    .findByVendorBill_VendorBillIdOrderByLineNumber(bill.getVendorBillId())
                    .getFirst();
            line.setUnitPrice(new BigDecimal("400.00"));
            line.setBilledUnitPrice(new BigDecimal("400.00"));
            bill.setTaxAmount(new BigDecimal("28.00"));
        }

        /** A goods-receipt bill of one non-stock line of 200.00, no tax, its vendor's key shop supplies. */
        private void untaxedExpense() {
            billed("200.00", false);
            when(vendorCopies.apDefaults(vendor))
                    .thenReturn(new VendorBillPostingService.Classification(null, "EXPENSE_SHOP_SUPPLIES"));
            when(client.useTax(any()))
                    .thenReturn(new TaxUseQuote.Response(
                            new BigDecimal("17.00"), List.of(new TaxUseQuote.LineTax("1", new BigDecimal("17.00")))));
        }

        @Test
        @DisplayName("AC3: a stated tax prorated onto a RECEIPT_MATCHED line within the limit leaves the bill"
                + " AWAITING_APPROVAL with a skip row coded AP_BILL_TAX_ON_RESALE_GOODS, before the pre-check")
        void holdSkips() {
            taxedGoods();

            assertThat(autoApproval.approveIfEligible(bill, evidence, 95)).isFalse();

            assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.AWAITING_APPROVAL);
            assertThat(bill.getApprovedBy()).isNull();
            assertThat(onlyAudit().getNewValue()).contains("code=AP_BILL_TAX_ON_RESALE_GOODS");
            verify(postingService, never()).requireMapped(any(), any(), any());
            verify(postingService, never()).post(any(), any(), any(), anyString(), any());
        }

        @Test
        @DisplayName("AC3: with the vendor's acceptTaxOnResaleGoods on, it is approved by SYSTEM, override"
                + " VENDOR_SETTING")
        void vendorSettingApproves() {
            taxedGoods();
            when(vendorCopies.acceptsTaxOnResaleGoods(vendor)).thenReturn(true);

            assertThat(autoApproval.approveIfEligible(bill, evidence, 95)).isTrue();

            assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.APPROVED);
            assertThat(bill.getApprovedBy()).isEqualTo("SYSTEM");
            assertThat(bill.getTaxOnResaleOverride()).isEqualTo(TaxOnResaleOverrideSource.VENDOR_SETTING);
            assertThat(onlyAudit().getNewValue()).contains("taxOnResaleOverride=VENDOR_SETTING");
        }

        @Test
        @DisplayName("AC8: an accruing bill asks pos-tax USE exactly once; the pre-check's legs and the posting"
                + " carry the same 17.00 to USE_TAX_PAYABLE")
        void oneQuote() {
            untaxedExpense();
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<VendorBillPostingService.Leg>> legs = ArgumentCaptor.forClass(List.class);
            ArgumentCaptor<VendorBillPostingService.UseTax> posted =
                    ArgumentCaptor.forClass(VendorBillPostingService.UseTax.class);

            assertThat(autoApproval.approveIfEligible(bill, evidence, 95)).isTrue();

            verify(client).useTax(any());
            verify(postingService).requireMapped(eq(bill), legs.capture(), eq(INVOICE_DATE));
            verify(postingService).post(eq(bill), any(), eq(null), eq("SYSTEM"), posted.capture());
            assertThat(legs.getValue())
                    .contains(
                            new VendorBillPostingService.Leg("EXPENSE_SHOP_SUPPLIES", new BigDecimal("217.00")),
                            new VendorBillPostingService.Leg("USE_TAX_PAYABLE", new BigDecimal("-17.00")));
            assertThat(posted.getValue())
                    .isEqualTo(new VendorBillPostingService.UseTax("EXPENSE_SHOP_SUPPLIES", new BigDecimal("17.00")));
            assertThat(onlyAudit().getNewValue()).contains("useTaxAmount=17.00");
        }

        @Test
        @DisplayName("AC7 [M]: pos-tax unreachable for the rules skips with SERVICE_UNAVAILABLE, never read as off;"
                + " the quote failing skips the same way")
        void posTaxDownSkips() {
            untaxedExpense();
            when(client.purchaseRules(any(), any())).thenThrow(new TaxServiceUnavailableException("unavailable"));

            assertThat(autoApproval.approveIfEligible(bill, evidence, 95)).isFalse();

            assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.AWAITING_APPROVAL);
            assertThat(onlyAudit().getNewValue()).contains("code=SERVICE_UNAVAILABLE");
            verify(postingService, never()).post(any(), any(), any(), anyString(), any());

            org.mockito.Mockito.doReturn(PurchaseTaxFixtures.HOLD_AND_SELF_ASSESS)
                    .when(client)
                    .purchaseRules(any(), any());
            doThrow(new TaxServiceUnavailableException("unavailable"))
                    .when(client)
                    .useTax(any());
            assertThat(autoApproval.precheck(bill).refusal())
                    .get()
                    .satisfies(e ->
                            assertThat(VendorBillApprovalServiceImpl.codeOf(e)).isEqualTo("SERVICE_UNAVAILABLE"));
        }

        @Test
        @DisplayName("#2604 ruling 4 amended: a relayed pos-tax 422 on the quote skips with that code: the bill stays"
                + " AWAITING_APPROVAL, one VENDOR_BILL_AUTO_APPROVE_SKIPPED row, nothing posted")
        void relayedCodeSkips() {
            untaxedExpense();
            doThrow(new TaxQuoteRefusedException("TAX_JURISDICTION_NOT_CONFIGURED", "no rates"))
                    .when(client)
                    .useTax(any());

            assertThat(autoApproval.approveIfEligible(bill, evidence, 95)).isFalse();

            assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.AWAITING_APPROVAL);
            assertThat(bill.getApprovedBy()).isNull();
            AccountingAuditLog row = onlyAudit();
            assertThat(row.getOperation()).isEqualTo("VENDOR_BILL_AUTO_APPROVE_SKIPPED");
            assertThat(row.getNewValue())
                    .contains("code=TAX_JURISDICTION_NOT_CONFIGURED", "INV-7")
                    .doesNotContain("no rates");
            verify(postingService, never()).post(any(), any(), any(), anyString(), any());
            verify(postingService, never()).requireMapped(any(), any(), any());
        }

        @Test
        @DisplayName("AC9: no USE_TAX_PAYABLE mapping skips with GL_MAPPING_NOT_CONFIGURED")
        void useTaxMappingMissingSkips() {
            untaxedExpense();
            doThrow(new GLMappingNotConfiguredException("no mapping", "VENDOR_BILL", "USE_TAX_PAYABLE", "map it"))
                    .when(postingService)
                    .requireMapped(any(), any(), any());

            assertThat(autoApproval.approveIfEligible(bill, evidence, 95)).isFalse();

            assertThat(onlyAudit().getNewValue()).contains("code=GL_MAPPING_NOT_CONFIGURED");
        }

        @Test
        @DisplayName("Rules switched off (configured false): the taxed goods bill is approved and nothing is quoted")
        void rulesOff() {
            taxedGoods();
            when(client.purchaseRules(any(), any())).thenReturn(PurchaseTaxFixtures.OFF);

            assertThat(autoApproval.approveIfEligible(bill, evidence, 95)).isTrue();

            assertThat(bill.getTaxOnResaleOverride()).isNull();
            verify(client, never()).useTax(any());
        }
    }
}
