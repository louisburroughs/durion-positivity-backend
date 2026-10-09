package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.client.TaxReferenceClient;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.TaxUseQuote;
import com.positivity.accounting.internal.dto.VendorBillCommands;
import com.positivity.accounting.internal.dto.VendorBillResponse;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.entity.VendorBillGlPosting;
import com.positivity.accounting.internal.enums.TaxOnResaleOverrideSource;
import com.positivity.accounting.internal.enums.VendorBillDebitClass;
import com.positivity.accounting.internal.enums.VendorBillPostingDateRule;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.exception.TaxQuoteRefusedException;
import com.positivity.accounting.internal.exception.TaxServiceUnavailableException;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.repository.APPaymentAllocationRepository;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.VendorBillLineRepository;
import com.positivity.accounting.internal.repository.VendorBillMatchCandidateRepository;
import com.positivity.accounting.internal.repository.VendorBillMatchEvidenceRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.security.common.GatewaySecurityConstants;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * CAP:550 S43 (#2604, AW44) in a person's decision, approve and {@code ACCEPT}: the hold for tax on goods for resale
 * as the last content check after {@code requireClassified}, its two overrides and their storage, the use-tax quote as
 * the posting's first act, pos-tax down (AW49), and the carried S13 fix of the limit message. Tax country {@code ZZ};
 * the rule fixture is HOLD / self-assess, the use tax 17.00 on 200.00 (8.5 %, not tax law). The posting is mocked
 * (VendorBillPurchaseTaxPostgresIT posts for real).
 */
@DisplayName("VendorBillApprovalService: purchase-tax hold and use-tax quote (S43)")
class VendorBillPurchaseTaxDecisionTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T15:00:00Z"), ZoneOffset.UTC);
    private static final UUID BILL_ID = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4e01");
    private static final UUID VENDOR = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4e02");
    private static final LocalDate POSTING_DATE = LocalDate.of(2026, 10, 1);
    private static final String JUSTIFICATION = "Vendor resale certificate pending";
    private static final ApApprovalPolicy.Settings POLICY =
            new ApApprovalPolicy.Settings(new BigDecimal("0.00"), new BigDecimal("0.00"), false, false, "NET30");

    private final VendorBillRepository bills = mock();
    private final VendorBillMatchEvidenceRepository evidence = mock();
    private final AccountingAuditLogRepository auditLogs = mock();
    private final VendorBillPostingService postingService = mock();
    private final VendorBillReader reader = mock();
    private final ApApprovalPolicy policy = mock();
    private final SupplierVendorCopies vendorCopies = mock();
    private final VendorBillLineRepository billLines = mock();
    private final TaxReferenceClient client = mock();
    private VendorBillApprovalServiceImpl service;
    private VendorBill bill;

    @BeforeEach
    void wire() {
        service = new VendorBillApprovalServiceImpl(
                CLOCK,
                bills,
                mock(VendorBillMatchCandidateRepository.class),
                evidence,
                mock(APPaymentAllocationRepository.class),
                auditLogs,
                postingService,
                mock(VendorBillInvoiceMatcher.class),
                mock(VendorBillDuplicateGuard.class),
                reader,
                mock(VendorBillLocks.class),
                new LedgerCurrency("USD"),
                policy,
                mock(ApLockTimeout.class),
                vendorCopies,
                PurchaseTaxFixtures.purchaseTax(client, vendorCopies, billLines, CLOCK),
                mock(PlatformTransactionManager.class));
        when(policy.settings()).thenReturn(POLICY);
        when(policy.forDecision()).thenReturn(POLICY);
        when(client.purchaseRules(any(), any())).thenReturn(PurchaseTaxFixtures.HOLD_AND_SELF_ASSESS);
        when(postingService.postingDate(any()))
                .thenReturn(
                        new VendorBillPostingService.PostingDate(POSTING_DATE, VendorBillPostingDateRule.BILL_DATE));
        when(postingService.post(any(), any(), any(), anyString(), any())).thenAnswer(inv -> posting());
        bill = new VendorBill(BILL_ID);
        bill.setVendorId(VENDOR);
        bill.setBillNumber("INV-43");
        bill.setBillDate(LocalDateTime.of(2026, 10, 1, 0, 0));
        bill.setCurrency("USD");
        bill.setCreatedBy("supplier");
        bill.setStatus(VendorBillStatus.AWAITING_APPROVAL);
        when(bills.lockById(BILL_ID)).thenAnswer(inv -> Optional.of(bill));
        when(bills.save(any(VendorBill.class))).thenAnswer(inv -> inv.getArgument(0));
        when(evidence.findFirstByVendorBillIdOrderByRecordedAtDescMatchEvidenceIdDesc(BILL_ID))
                .thenReturn(Optional.empty());
        when(reader.read(any(VendorBill.class)))
                .thenAnswer(inv -> VendorBillResponse.builder()
                        .vendorBillId(BILL_ID)
                        .status(inv.getArgument(0, VendorBill.class).getStatus())
                        .build());
        signIn("controller.cfo", AccountingPermissions.AP_APPROVE, AccountingPermissions.AP_APPROVE_OVER_LIMIT);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    /** AC 1's bill: an EDI bill classed GOODS, net 400.00 with tax 28.00. */
    private void taxedGoods() {
        bill.setTotalAmount(new BigDecimal("428.00"));
        bill.setNetAmount(new BigDecimal("400.00"));
        bill.setTaxAmount(new BigDecimal("28.00"));
        bill.setStatedLineCount(1);
        bill.setProposedDebitClass(VendorBillDebitClass.GOODS);
    }

    /** AC 4's bill: an EDI bill classed EXPENSE_SHOP_SUPPLIES, net 200.00 with no tax. */
    private void untaxedExpense() {
        bill.setTotalAmount(new BigDecimal("200.00"));
        bill.setNetAmount(new BigDecimal("200.00"));
        bill.setTaxAmount(new BigDecimal("0.00"));
        bill.setStatedLineCount(1);
        bill.setProposedDebitClass(VendorBillDebitClass.EXPENSE);
        bill.setProposedExpenseMappingKey("EXPENSE_SHOP_SUPPLIES");
        when(client.useTax(any()))
                .thenReturn(new TaxUseQuote.Response(
                        new BigDecimal("17.00"), List.of(new TaxUseQuote.LineTax("1", new BigDecimal("17.00")))));
    }

    private VendorBillResponse approve(String taxOnResaleOverrideJustification) {
        return service.approve(
                BILL_ID, new VendorBillCommands.Approve(null, null, null, null, taxOnResaleOverrideJustification));
    }

    @Test
    @DisplayName("AC1: without an override, 422 AP_BILL_TAX_ON_RESALE_GOODS naming the bill and the tax; no approval"
            + " field, no posting, one VENDOR_BILL_APPROVE_REFUSED row")
    void holdRefuses() {
        taxedGoods();

        assertThatThrownBy(() -> approve(null)).isInstanceOfSatisfying(VendorBillException.class, e -> {
            assertThat(e.getCode()).isEqualTo(VendorBillException.Code.AP_BILL_TAX_ON_RESALE_GOODS);
            assertThat(e.getMessage()).contains("INV-43", "28.00 USD");
        });

        assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.AWAITING_APPROVAL);
        assertThat(bill.getApprovedBy()).isNull();
        assertThat(bill.getTaxOnResaleOverride()).isNull();
        verify(postingService, never()).post(any(), any(), any(), anyString(), any());
        verify(client, never()).useTax(any());
        AccountingAuditLog row = onlyAudit();
        assertThat(row.getOperation()).isEqualTo("VENDOR_BILL_APPROVE_REFUSED");
        assertThat(row.getNewValue()).contains("code=AP_BILL_TAX_ON_RESALE_GOODS", "taxAmount=28.00");
    }

    @Test
    @DisplayName("AC1 [M]: the same bill unclassified answers AP_BILL_UNCLASSIFIED and pos-tax is never asked: the"
            + " hold comes after requireClassified")
    void unclassifiedComesFirst() {
        taxedGoods();
        doThrow(new VendorBillException(VendorBillException.Code.AP_BILL_UNCLASSIFIED, "no class"))
                .when(postingService)
                .requirePostable(any(), any(), any());

        assertThatThrownBy(() -> approve(null))
                .isInstanceOfSatisfying(
                        VendorBillException.class,
                        e -> assertThat(e.getCode()).isEqualTo(VendorBillException.Code.AP_BILL_UNCLASSIFIED));
        verify(client, never()).purchaseRules(any(), any());
    }

    @Test
    @DisplayName("AC2: a justification of 10+ characters after trimming approves, override BILL stored with the"
            + " trimmed justification; the audit row names the source, never the text")
    void perBillOverride() {
        taxedGoods();

        approve("   " + JUSTIFICATION + "   ");

        assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.APPROVED);
        assertThat(bill.getTaxOnResaleOverride()).isEqualTo(TaxOnResaleOverrideSource.BILL);
        assertThat(bill.getTaxOnResaleOverrideJustification()).isEqualTo(JUSTIFICATION);
        verify(postingService).post(eq(bill), any(), isNull(), eq("controller.cfo"), isNull());
        AccountingAuditLog row = onlyAudit();
        assertThat(row.getOperation()).isEqualTo("VENDOR_BILL_APPROVE");
        assertThat(row.getNewValue()).contains("taxOnResaleOverride=BILL").doesNotContain(JUSTIFICATION);
        assertThat(row.getJustification()).isNull();
    }

    @Test
    @DisplayName("AC2: the vendor's acceptTaxOnResaleGoods approves without a justification, override VENDOR_SETTING")
    void vendorSettingOverride() {
        taxedGoods();
        when(vendorCopies.acceptsTaxOnResaleGoods(VENDOR)).thenReturn(true);

        approve(null);

        assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.APPROVED);
        assertThat(bill.getTaxOnResaleOverride()).isEqualTo(TaxOnResaleOverrideSource.VENDOR_SETTING);
        assertThat(bill.getTaxOnResaleOverrideJustification()).isNull();
        assertThat(onlyAudit().getNewValue()).contains("taxOnResaleOverride=VENDOR_SETTING");
    }

    @Test
    @DisplayName("AC2: a justification of 9 characters is 400 JUSTIFICATION_REQUIRED naming the field, never the"
            + " text; nothing is written")
    void shortJustification() {
        taxedGoods();

        assertThatThrownBy(() -> approve("123456789")).isInstanceOfSatisfying(VendorBillException.class, e -> {
            assertThat(e.getCode()).isEqualTo(VendorBillException.Code.JUSTIFICATION_REQUIRED);
            assertThat(e.getMessage())
                    .contains("taxOnResaleOverrideJustification")
                    .doesNotContain("123456789");
        });
        verify(postingService, never()).post(any(), any(), any(), anyString(), any());
        verify(auditLogs, never()).save(any());
    }

    @Test
    @DisplayName("A justification sent when the hold does not apply (rule ALLOW) is ignored and not stored")
    void justificationIgnoredWithoutTheHold() {
        taxedGoods();
        when(client.purchaseRules(any(), any())).thenReturn(PurchaseTaxFixtures.OFF);

        approve("short");

        assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.APPROVED);
        assertThat(bill.getTaxOnResaleOverride()).isNull();
        assertThat(bill.getTaxOnResaleOverrideJustification()).isNull();
    }

    @Test
    @DisplayName("AC3: ACCEPT of a qualifying MATCH_EXCEPTION bill without an override is 422"
            + " AP_BILL_TAX_ON_RESALE_GOODS, audited as VENDOR_BILL_MATCH_EXCEPTION_RESOLVE_REFUSED")
    void acceptHolds() {
        taxedGoods();
        bill.setStatus(VendorBillStatus.MATCH_EXCEPTION);

        assertThatThrownBy(() -> service.resolveException(
                        BILL_ID,
                        new VendorBillCommands.ResolveException("ACCEPT", "Price agreed by phone", null, null, null)))
                .isInstanceOfSatisfying(
                        VendorBillException.class,
                        e -> assertThat(e.getCode()).isEqualTo(VendorBillException.Code.AP_BILL_TAX_ON_RESALE_GOODS));
        assertThat(onlyAudit().getOperation()).isEqualTo("VENDOR_BILL_MATCH_EXCEPTION_RESOLVE_REFUSED");

        service.resolveException(
                BILL_ID,
                new VendorBillCommands.ResolveException(
                        "ACCEPT", "Price agreed by phone", null, null, null, JUSTIFICATION));
        assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.APPROVED);
        assertThat(bill.getTaxOnResaleOverride()).isEqualTo(TaxOnResaleOverrideSource.BILL);
    }

    @Test
    @DisplayName("AC4, AC8: an untaxed expense bill is quoted once (USE, ledger currency, posting date, the bill, not"
            + " committable, the configured place) and posted with that answer; the audit names useTaxAmount")
    void useTaxQuotedOnceAndPosted() {
        untaxedExpense();

        approve(null);

        ArgumentCaptor<TaxUseQuote.Request> quote = ArgumentCaptor.forClass(TaxUseQuote.Request.class);
        verify(client).useTax(quote.capture());
        assertThat(quote.getValue().calculationType()).isEqualTo("USE");
        assertThat(quote.getValue().currencyCode()).isEqualTo("USD");
        assertThat(quote.getValue().transactionDate()).isEqualTo("2026-10-01");
        assertThat(quote.getValue().referenceId()).isEqualTo(BILL_ID);
        assertThat(quote.getValue().committable()).isFalse();
        assertThat(quote.getValue().destinationAddress()).isEqualTo(new TaxUseQuote.Address("ZZ", "ZA", "00000"));
        assertThat(quote.getValue().lineItems()).singleElement().satisfies(line -> {
            assertThat(line.lineItemId()).isEqualTo("1");
            assertThat(line.quantity()).isEqualByComparingTo("1");
            assertThat(line.unitPrice()).isEqualByComparingTo("200.00");
        });
        verify(postingService)
                .post(
                        eq(bill),
                        any(),
                        isNull(),
                        eq("controller.cfo"),
                        eq(new VendorBillPostingService.UseTax("EXPENSE_SHOP_SUPPLIES", new BigDecimal("17.00"))));
        assertThat(onlyAudit().getNewValue()).contains("useTaxAmount=17.00");
    }

    @Test
    @DisplayName("AC5 [M]: a bill stating any tax, or a credit note, is never quoted and posts no use tax")
    void noAccrualOnTaxedOrCreditNotes() {
        untaxedExpense();
        bill.setTotalAmount(new BigDecimal("205.00"));
        bill.setTaxAmount(new BigDecimal("5.00"));
        approve(null);
        verify(client, never()).useTax(any());
        verify(postingService).post(eq(bill), any(), isNull(), eq("controller.cfo"), isNull());

        bill.setStatus(VendorBillStatus.AWAITING_APPROVAL);
        bill.setTotalAmount(new BigDecimal("-50.00"));
        bill.setNetAmount(new BigDecimal("-50.00"));
        bill.setTaxAmount(new BigDecimal("0.00"));
        approve(null);
        verify(client, never()).useTax(any());
    }

    @Test
    @DisplayName("AC7 [M]: pos-tax unreachable for the rules is 503 SERVICE_UNAVAILABLE, never read as off: nothing"
            + " posted, no audit row")
    void rulesUnavailable() {
        untaxedExpense();
        when(client.purchaseRules(any(), any())).thenThrow(new TaxServiceUnavailableException("unavailable"));

        assertThatThrownBy(() -> approve(null)).isInstanceOf(TaxServiceUnavailableException.class);

        assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.AWAITING_APPROVAL);
        verify(postingService, never()).post(any(), any(), any(), anyString(), any());
        verify(auditLogs, never()).save(any());
    }

    @Test
    @DisplayName("#2604 ruling 4 amended: a pos-tax configuration 422 on the quote is 422 with that code and"
            + " accounting's own message naming the bill and the settings; nothing posted or audited")
    void relayedConfigurationRefusal() {
        untaxedExpense();
        when(client.useTax(any()))
                .thenThrow(new TaxQuoteRefusedException("TAX_JURISDICTION_NOT_CONFIGURED", "pos-tax said ZZ 00000"));

        assertThatThrownBy(() -> approve(null)).isInstanceOfSatisfying(TaxQuoteRefusedException.class, e -> {
            assertThat(e.getCode()).isEqualTo("TAX_JURISDICTION_NOT_CONFIGURED");
            assertThat(e.getMessage())
                    .contains("INV-43", "accounting.tax.country", "accounting.tax.purchase-place")
                    .doesNotContain("pos-tax said");
        });
        assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.AWAITING_APPROVAL);
        verify(postingService, never()).post(any(), any(), any(), anyString(), any());
        verify(auditLogs, never()).save(any());
    }

    @Test
    @DisplayName("B5: a justification over 1000 characters after trimming is 400 VALIDATION_ERROR naming the field,"
            + " never echoing the text; nothing is written")
    void overlongJustification() {
        taxedGoods();
        String text = "q".repeat(1001);

        assertThatThrownBy(() -> approve(text)).isInstanceOfSatisfying(VendorBillException.class, e -> {
            assertThat(e.getCode()).isEqualTo(VendorBillException.Code.VALIDATION_ERROR);
            assertThat(e.getFieldErrors())
                    .extracting(VendorBillException.FieldError::field)
                    .containsExactly("taxOnResaleOverrideJustification");
            assertThat(e.getMessage()).doesNotContain("qqqq");
        });
        verify(auditLogs, never()).save(any());
        approve("  " + "q".repeat(1000) + "  ");
        assertThat(bill.getTaxOnResaleOverrideJustification()).hasSize(1000);
    }

    @Test
    @DisplayName("AC7: the quote failing is the same 503 with nothing posted or audited")
    void quoteUnavailable() {
        untaxedExpense();
        when(client.useTax(any())).thenThrow(new TaxServiceUnavailableException("unavailable"));

        assertThatThrownBy(() -> approve(null)).isInstanceOf(TaxServiceUnavailableException.class);

        verify(postingService, never()).post(any(), any(), any(), anyString(), any());
        verify(auditLogs, never()).save(any());
    }

    @Test
    @DisplayName("AC11: a bill over the clerk limit is refused with the limit stated in the ledger currency, not the"
            + " bill's")
    void limitMessageInTheLedgerCurrency() {
        untaxedExpense();
        bill.setCurrency("CAD");
        signIn("clerk.ana", AccountingPermissions.AP_APPROVE);

        assertThatThrownBy(() -> approve(null)).isInstanceOfSatisfying(VendorBillException.class, e -> {
            assertThat(e.getCode()).isEqualTo(VendorBillException.Code.AP_APPROVAL_LIMIT_EXCEEDED);
            assertThat(e.getMessage()).contains("200.00 CAD", "limit of 0.00 USD");
        });
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private AccountingAuditLog onlyAudit() {
        ArgumentCaptor<AccountingAuditLog> row = ArgumentCaptor.forClass(AccountingAuditLog.class);
        verify(auditLogs).save(row.capture());
        return row.getValue();
    }

    private static VendorBillGlPosting posting() {
        VendorBillGlPosting posting = new VendorBillGlPosting();
        posting.setJournalEntryId(UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4e10"));
        posting.setPostingDate(POSTING_DATE);
        posting.setPostingDateRule(VendorBillPostingDateRule.BILL_DATE);
        posting.setRoundingAdjustment(new BigDecimal("0.00"));
        return posting;
    }

    private static void signIn(String username, String... authorities) {
        UsernamePasswordAuthenticationToken caller = new UsernamePasswordAuthenticationToken(
                username,
                "n/a",
                Stream.of(authorities).map(SimpleGrantedAuthority::new).toList());
        caller.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, username));
        SecurityContextHolder.getContext().setAuthentication(caller);
    }
}
