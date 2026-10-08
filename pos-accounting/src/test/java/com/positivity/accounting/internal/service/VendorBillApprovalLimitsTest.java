package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.VendorBillCommands;
import com.positivity.accounting.internal.dto.VendorBillResponse;
import com.positivity.accounting.internal.dto.VendorBillReview;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.entity.VendorBillGlPosting;
import com.positivity.accounting.internal.enums.VendorBillApproverKind;
import com.positivity.accounting.internal.enums.VendorBillDebitClass;
import com.positivity.accounting.internal.enums.VendorBillDifferenceClass;
import com.positivity.accounting.internal.enums.VendorBillPostingDateRule;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.repository.APPaymentAllocationRepository;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Approval limits and separation of duties on the vendor-bill decisions (CAP:550 S13, #2510; §4.3; AW4-AW6, AW45,
 * AW47; ruling 6060589020): the tier against the clerk limit, the creator rule and its exception switch, the guard
 * order, and the real due date. The posting is mocked (VendorBillPostingServiceTest); the row lock and the refusal row
 * surviving the rollback are VendorBillApprovalLimitsPostgresIT's.
 */
@DisplayName("Vendor-bill approval limits and separation of duties (#2510)")
class VendorBillApprovalLimitsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T15:00:00Z"), ZoneOffset.UTC);
    private static final UUID BILL_ID = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4c01");
    private static final String APPROVE = AccountingPermissions.AP_APPROVE;
    private static final String OVER_LIMIT = AccountingPermissions.AP_APPROVE_OVER_LIMIT;
    private static final String REJECT = AccountingPermissions.AP_REJECT;
    private static final String CLERK = "clerk.ana";
    private static final String GM = "gm.gary";

    private final VendorBillRepository bills = mock();
    private final VendorBillMatchEvidenceRepository evidence = mock();
    private final APPaymentAllocationRepository allocations = mock();
    private final AccountingAuditLogRepository auditLogs = mock();
    private final VendorBillPostingService postingService = mock();
    private final VendorBillReader reader = mock();
    private final ApApprovalPolicy policy = mock();
    private final com.positivity.accounting.internal.repository.ExtSupplierVendorRepository vendorCopy = mock();
    private SupplierVendorCopies vendorCopies;
    private VendorBillApprovalServiceImpl service;
    private VendorBill bill;

    @BeforeEach
    void wire() {
        // The real vendor rules over mocked repositories (S24): the copy, the vendor's settings and the bills.
        vendorCopies = new SupplierVendorCopies(
                vendorCopy,
                mock(com.positivity.accounting.internal.repository.ApVendorSettingsRepository.class),
                bills);
        service = new VendorBillApprovalServiceImpl(
                CLOCK,
                bills,
                mock(VendorBillMatchCandidateRepository.class),
                evidence,
                allocations,
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
                mock(PlatformTransactionManager.class));
        bill = new VendorBill(BILL_ID);
        bill.setVendorId(UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4c02"));
        bill.setBillNumber("INV-2510");
        bill.setBillDate(LocalDateTime.of(2026, 10, 1, 0, 0));
        bill.setCreatedBy("receiving.rob");
        bill.setProposedDebitClass(VendorBillDebitClass.GOODS);
        when(bills.lockById(BILL_ID)).thenAnswer(inv -> Optional.of(bill));
        when(bills.save(any(VendorBill.class))).thenAnswer(inv -> inv.getArgument(0));
        when(evidence.findFirstByVendorBillIdOrderByRecordedAtDescMatchEvidenceIdDesc(BILL_ID))
                .thenReturn(Optional.empty());
        when(reader.read(any(VendorBill.class)))
                .thenAnswer(inv -> VendorBillResponse.builder()
                        .vendorBillId(inv.getArgument(0, VendorBill.class).getVendorBillId())
                        .status(inv.getArgument(0, VendorBill.class).getStatus())
                        .build());
        when(postingService.post(any(), any(), any(), anyString())).thenAnswer(inv -> posting());
        limits("2500.00", "0.00", false);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private void limits(String clerk, String auto, boolean allowCreator) {
        ApApprovalPolicy.Settings settings = new ApApprovalPolicy.Settings(
                new BigDecimal(clerk), new BigDecimal(auto), allowCreator, false, "NET30");
        when(policy.settings()).thenReturn(settings);
        when(policy.forDecision()).thenReturn(settings);
    }

    private static VendorBillGlPosting posting() {
        VendorBillGlPosting posting = new VendorBillGlPosting();
        posting.setJournalEntryId(UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4c10"));
        posting.setPostingDate(LocalDate.of(2026, 10, 1));
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

    private void awaiting(String total) {
        bill.setStatus(VendorBillStatus.AWAITING_APPROVAL);
        bill.setTotalAmount(new BigDecimal(total));
    }

    private List<AccountingAuditLog> auditRows() {
        ArgumentCaptor<AccountingAuditLog> rows = ArgumentCaptor.forClass(AccountingAuditLog.class);
        verify(auditLogs, org.mockito.Mockito.atLeast(0)).save(rows.capture());
        return rows.getAllValues();
    }

    private static VendorBillException.Code codeOf(Throwable thrown) {
        return ((VendorBillException) thrown).getCode();
    }

    private static VendorBillCommands.Approve approve(String justification) {
        return new VendorBillCommands.Approve(justification, null, null, null);
    }

    private static VendorBillCommands.ResolveException accept(VendorBillReview.Difference difference) {
        return new VendorBillCommands.ResolveException(
                "ACCEPT",
                "Price agreed with the vendor",
                new VendorBillReview.Classification(VendorBillDebitClass.GOODS, null),
                null,
                difference);
    }

    private static final VendorBillReview.Difference FREIGHT =
            new VendorBillReview.Difference(VendorBillDifferenceClass.FREIGHT, null, "Freight on the invoice");

    @Nested
    @DisplayName("The tier")
    class Tier {

        @Test
        @DisplayName("AC2: at the limit a clerk approves, tier CLERK recorded with the limits in force")
        void clerkApprovesAtTheLimit() {
            signIn(CLERK, APPROVE, REJECT);
            awaiting("2500.00");

            VendorBillResponse response = service.approve(BILL_ID, approve(null));

            assertThat(response.getStatus()).isEqualTo(VendorBillStatus.APPROVED);
            assertThat(bill.getApprovedBy()).isEqualTo(CLERK);
            assertThat(bill.getApprovedByKind()).isEqualTo(VendorBillApproverKind.PERSON);
            assertThat(auditRows()).singleElement().satisfies(row -> {
                assertThat(row.getOperation()).isEqualTo("VENDOR_BILL_APPROVE");
                assertThat(row.getNewValue())
                        .contains("tier=CLERK", "limit=2500.00", "autoLimit=0.00", "exception=NONE");
            });
        }

        @Test
        @DisplayName("AC2: one cent over the limit a clerk is 403 AP_APPROVAL_LIMIT_EXCEEDED naming total and limit,"
                + " audited as VENDOR_BILL_APPROVE_REFUSED; nothing posts")
        void clerkRefusedOneCentOver() {
            signIn(CLERK, APPROVE, REJECT);
            awaiting("2500.01");

            assertThatThrownBy(() -> service.approve(BILL_ID, approve(null)))
                    .isInstanceOfSatisfying(VendorBillException.class, refusal -> {
                        assertThat(refusal.getCode()).isEqualTo(VendorBillException.Code.AP_APPROVAL_LIMIT_EXCEEDED);
                        assertThat(refusal.getCode().status().value()).isEqualTo(403);
                        assertThat(refusal.getMessage()).contains("2500.01 USD", "2500.00 USD");
                        assertThat(refusal.getNextAction()).contains("accounting:ap:approve_over_limit");
                    });
            assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.AWAITING_APPROVAL);
            assertThat(bill.getApprovedBy()).isNull();
            verify(postingService, never()).post(any(), any(), any(), anyString());
            assertThat(auditRows()).singleElement().satisfies(row -> {
                assertThat(row.getOperation()).isEqualTo("VENDOR_BILL_APPROVE_REFUSED");
                assertThat(row.getUserId()).isEqualTo(CLERK);
                assertThat(row.getNewValue())
                        .contains("code=AP_APPROVAL_LIMIT_EXCEEDED", "tier=OVER_LIMIT", "limit=2500.00");
            });
        }

        @Test
        @DisplayName("AC2: a general manager approves the same bill; a credit note of -2500.01 is OVER_LIMIT too")
        void overLimitApproverAndCreditNotes() {
            signIn(GM, APPROVE, OVER_LIMIT, REJECT);
            awaiting("2500.01");
            assertThat(service.approve(BILL_ID, approve(null)).getStatus()).isEqualTo(VendorBillStatus.APPROVED);
            assertThat(auditRows())
                    .singleElement()
                    .satisfies(row -> assertThat(row.getNewValue()).contains("tier=OVER_LIMIT"));

            signIn(CLERK, APPROVE, REJECT);
            awaiting("-2500.01");
            assertThatThrownBy(() -> service.approve(BILL_ID, approve(null)))
                    .satisfies(
                            e -> assertThat(codeOf(e)).isEqualTo(VendorBillException.Code.AP_APPROVAL_LIMIT_EXCEEDED));
            awaiting("-2500.00");
            assertThat(service.approve(BILL_ID, approve(null)).getStatus()).isEqualTo(VendorBillStatus.APPROVED);
        }

        @Test
        @DisplayName("AC1: with no policy rows (limit 0) a clerk's approve of a 10.00 bill is 403")
        void defaultLimitRoutesEveryBillOverIt() {
            limits("0.00", "0.00", false);
            signIn(CLERK, APPROVE, REJECT);
            awaiting("10.00");
            assertThatThrownBy(() -> service.approve(BILL_ID, approve(null)))
                    .satisfies(
                            e -> assertThat(codeOf(e)).isEqualTo(VendorBillException.Code.AP_APPROVAL_LIMIT_EXCEEDED));
        }

        @Test
        @DisplayName("AC3: a clerk's ACCEPT of a 3000.00 MATCH_EXCEPTION bill over a 2500.00 limit is 403")
        void acceptTakesTheLimit() {
            signIn(CLERK, APPROVE, REJECT);
            bill.setStatus(VendorBillStatus.MATCH_EXCEPTION);
            bill.setTotalAmount(new BigDecimal("3000.00"));

            assertThatThrownBy(() -> service.resolveException(BILL_ID, accept(null)))
                    .satisfies(
                            e -> assertThat(codeOf(e)).isEqualTo(VendorBillException.Code.AP_APPROVAL_LIMIT_EXCEEDED));
            assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.MATCH_EXCEPTION);
            assertThat(auditRows())
                    .singleElement()
                    .satisfies(row ->
                            assertThat(row.getOperation()).isEqualTo("VENDOR_BILL_MATCH_EXCEPTION_RESOLVE_REFUSED"));
        }

        @Test
        @DisplayName("AC16: a clerk holding ap:reject and ap:approve voids an over-limit approved bill: 403, audited;"
                + " a controller voids it")
        void voidOfAnApprovedBillTakesTheTier() {
            limits("300.00", "0.00", false);
            bill.setStatus(VendorBillStatus.APPROVED);
            bill.setTotalAmount(new BigDecimal("400.00"));
            signIn(CLERK, APPROVE, REJECT);

            assertThatThrownBy(() ->
                            service.voidBill(BILL_ID, new VendorBillCommands.VoidBill("Billed twice, sorry", null)))
                    .satisfies(
                            e -> assertThat(codeOf(e)).isEqualTo(VendorBillException.Code.AP_APPROVAL_LIMIT_EXCEEDED));
            verify(postingService, never()).reverse(any(), any(), anyString());
            assertThat(auditRows())
                    .singleElement()
                    .satisfies(row -> assertThat(row.getOperation()).isEqualTo("VENDOR_BILL_VOID_REFUSED"));

            VendorBillGlPosting reversed = posting();
            reversed.setReversalJournalEntryId(UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4c11"));
            reversed.setReversalDate(LocalDate.of(2026, 10, 8));
            when(postingService.reverse(bill, null, "controller.cfo")).thenReturn(reversed);
            signIn("controller.cfo", APPROVE, OVER_LIMIT, REJECT);
            service.voidBill(BILL_ID, new VendorBillCommands.VoidBill("Billed twice, sorry", null));
            assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.VOIDED);
        }
    }

    @Nested
    @DisplayName("Creator is not approver")
    class Creator {

        @Test
        @DisplayName("AC4: the creator's approve and ACCEPT are 403 AP_BILL_SELF_APPROVAL, each audited")
        void creatorIsRefused() {
            bill.setCreatedBy(CLERK);
            signIn(CLERK, APPROVE, REJECT);
            awaiting("100.00");
            assertThatThrownBy(() -> service.approve(BILL_ID, approve("Checked against the delivery")))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(VendorBillException.Code.AP_BILL_SELF_APPROVAL));
            bill.setStatus(VendorBillStatus.MATCH_EXCEPTION);
            assertThatThrownBy(() -> service.resolveException(BILL_ID, accept(null)))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(VendorBillException.Code.AP_BILL_SELF_APPROVAL));
            verify(postingService, never()).post(any(), any(), any(), anyString());
            assertThat(auditRows())
                    .extracting(AccountingAuditLog::getOperation)
                    .containsExactly("VENDOR_BILL_APPROVE_REFUSED", "VENDOR_BILL_MATCH_EXCEPTION_RESOLVE_REFUSED");
            assertThat(auditRows())
                    .allSatisfy(row -> assertThat(row.getNewValue()).contains("code=AP_BILL_SELF_APPROVAL"));
        }

        @Test
        @DisplayName("AC4: under AP_ALLOW_CREATOR_APPROVAL the creator approves with a justification, audited as an"
                + " exception use; without one it is 400 JUSTIFICATION_REQUIRED")
        void exceptionSwitch() {
            limits("2500.00", "0.00", true);
            bill.setCreatedBy(CLERK);
            signIn(CLERK, APPROVE, REJECT);
            awaiting("100.00");
            assertThatThrownBy(() -> service.approve(BILL_ID, approve(null)))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(VendorBillException.Code.JUSTIFICATION_REQUIRED));
            assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.AWAITING_APPROVAL);

            service.approve(BILL_ID, approve("Only one person in the office today"));

            assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.APPROVED);
            assertThat(auditRows())
                    .extracting(AccountingAuditLog::getOperation)
                    .containsExactly("VENDOR_BILL_APPROVE", "VENDOR_BILL_SOD_EXCEPTION");
            assertThat(auditRows())
                    .allSatisfy(row -> assertThat(row.getNewValue()).contains("exception=CREATOR_APPROVAL"));
        }

        @Test
        @DisplayName("A bill the system created ('supplier', SYSTEM) never matches a person")
        void systemCreatorNeverMatches() {
            bill.setCreatedBy("supplier");
            signIn(CLERK, APPROVE, REJECT);
            awaiting("100.00");
            assertThat(service.approve(BILL_ID, approve(null)).getStatus()).isEqualTo(VendorBillStatus.APPROVED);
        }

        @Test
        @DisplayName("The creator rule does not apply to the void of an approved bill")
        void voidIgnoresTheCreator() {
            bill.setCreatedBy("controller.cfo");
            bill.setStatus(VendorBillStatus.APPROVED);
            bill.setTotalAmount(new BigDecimal("100.00"));
            VendorBillGlPosting reversed = posting();
            reversed.setReversalJournalEntryId(UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4c12"));
            when(postingService.reverse(bill, null, "controller.cfo")).thenReturn(reversed);
            signIn("controller.cfo", APPROVE, OVER_LIMIT, REJECT);
            service.voidBill(BILL_ID, new VendorBillCommands.VoidBill("Entered against the wrong vendor", null));
            assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.VOIDED);
        }
    }

    @Nested
    @DisplayName("S24: the vendor's creator and the remit-to stamp")
    class VendorRules {

        private static final String CREATOR = "buyer.uma";

        private void vendorCreatedBy(String createdBy, int remitToVersion) {
            com.positivity.accounting.internal.entity.ExtSupplierVendor vendor =
                    new com.positivity.accounting.internal.entity.ExtSupplierVendor();
            vendor.setVendorId(bill.getVendorId());
            vendor.setVendorNumber("V-000123");
            vendor.setDisplayName("Acme Parts");
            vendor.setStatus("ACTIVE");
            vendor.setRemitToVersion(remitToVersion);
            vendor.setCreatedBy(createdBy);
            when(vendorCopy.findById(bill.getVendorId())).thenReturn(Optional.of(vendor));
        }

        @Test
        @DisplayName("AC 8: the vendor's creator may not approve its first bill: 403, reason VENDOR_CREATOR_FIRST_BILL")
        void vendorCreatorFirstBillIsRefused() {
            vendorCreatedBy(CREATOR, 1);
            when(bills.existsByVendorIdAndApprovedAtIsNotNull(bill.getVendorId()))
                    .thenReturn(false);
            signIn(CREATOR, APPROVE, REJECT);
            awaiting("100.00");

            assertThatThrownBy(() -> service.approve(BILL_ID, approve("Checked against the delivery")))
                    .satisfies(e -> {
                        assertThat(codeOf(e)).isEqualTo(VendorBillException.Code.AP_BILL_SELF_APPROVAL);
                        assertThat(e.getMessage()).contains("VENDOR_CREATOR_FIRST_BILL");
                    });
            bill.setStatus(VendorBillStatus.MATCH_EXCEPTION);
            assertThatThrownBy(() -> service.resolveException(BILL_ID, accept(null)))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(VendorBillException.Code.AP_BILL_SELF_APPROVAL));
            verify(postingService, never()).post(any(), any(), any(), anyString());
            assertThat(auditRows())
                    .allSatisfy(row -> assertThat(row.getNewValue()).contains("reason=VENDOR_CREATOR_FIRST_BILL"));
        }

        @Test
        @DisplayName("AC 8: another approver approves the first bill; afterwards the creator approves the second")
        void ruleLiftsOnceABillWasApproved() {
            vendorCreatedBy(CREATOR, 1);
            when(bills.existsByVendorIdAndApprovedAtIsNotNull(bill.getVendorId()))
                    .thenReturn(false);
            signIn(GM, APPROVE, OVER_LIMIT, REJECT);
            awaiting("100.00");
            assertThat(service.approve(BILL_ID, approve(null)).getStatus()).isEqualTo(VendorBillStatus.APPROVED);

            // Approved once (by a person or SYSTEM, voided or not): the rule is lifted for the creator.
            when(bills.existsByVendorIdAndApprovedAtIsNotNull(bill.getVendorId()))
                    .thenReturn(true);
            signIn(CREATOR, APPROVE, REJECT);
            awaiting("100.00");
            assertThat(service.approve(BILL_ID, approve(null)).getStatus()).isEqualTo(VendorBillStatus.APPROVED);
        }

        @Test
        @DisplayName("AC 8: under AP_ALLOW_CREATOR_APPROVAL the vendor's creator approves with a justification,"
                + " audited as an exception use")
        void switchAllowsTheVendorCreator() {
            limits("2500.00", "0.00", true);
            vendorCreatedBy(CREATOR, 1);
            when(bills.existsByVendorIdAndApprovedAtIsNotNull(bill.getVendorId()))
                    .thenReturn(false);
            signIn(CREATOR, APPROVE, REJECT);
            awaiting("100.00");

            service.approve(BILL_ID, approve("Only buyer on shift; vendor verified by phone"));

            assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.APPROVED);
            assertThat(auditRows())
                    .extracting(AccountingAuditLog::getOperation)
                    .containsExactly("VENDOR_BILL_APPROVE", "VENDOR_BILL_SOD_EXCEPTION");
        }

        @Test
        @DisplayName("AC 9: approve and ACCEPT stamp the copy's current remit-to version")
        void approvalsStampTheRemitToVersion() {
            vendorCreatedBy("someone.else", 3);
            signIn(GM, APPROVE, OVER_LIMIT, REJECT);
            awaiting("100.00");
            service.approve(BILL_ID, approve(null));
            assertThat(bill.getApprovedRemitToVersion()).isEqualTo(3);

            vendorCreatedBy("someone.else", 5);
            bill.setStatus(VendorBillStatus.MATCH_EXCEPTION);
            bill.setApprovedRemitToVersion(null);
            service.resolveException(BILL_ID, accept(null));
            assertThat(bill.getApprovedRemitToVersion()).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("Guard order (ruling 1)")
    class GuardOrder {

        @Test
        @DisplayName("AC14: an unmatched goods-receipt bill is 409 AP_BILL_AWAITING_INVOICE before the tier: no limit"
                + " refusal is audited")
        void awaitingInvoiceBeforeTheTier() {
            bill.setOriginEventType(VendorBillReader.ORIGIN_GOODS_RECEIVED);
            bill.setStatus(VendorBillStatus.MATCH_EXCEPTION);
            bill.setTotalAmount(new BigDecimal("3000.00"));
            when(reader.invoiceMatched(bill)).thenReturn(false);
            signIn(CLERK, APPROVE, REJECT);

            assertThatThrownBy(() -> service.resolveException(BILL_ID, accept(null)))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(VendorBillException.Code.AP_BILL_AWAITING_INVOICE));
            verify(auditLogs, never()).save(any());
        }

        @Test
        @DisplayName("AC13 (AW47): the tier compares the gross 1085.00, not the net: with a difference a clerk over a"
                + " 1050.00 limit is 403")
        void tierOnTheGross() {
            limits("1050.00", "0.00", false);
            ediBillInException();
            signIn(CLERK, APPROVE, REJECT);

            assertThatThrownBy(() -> service.resolveException(BILL_ID, accept(FREIGHT)))
                    .satisfies(
                            e -> assertThat(codeOf(e)).isEqualTo(VendorBillException.Code.AP_APPROVAL_LIMIT_EXCEEDED));
        }

        @Test
        @DisplayName("AC13 (AW47): within a 1100.00 limit, no difference is 422 AP_BILL_TOTALS_UNRECONCILED; with"
                + " FREIGHT the bill is APPROVED, tier CLERK")
        void withinTheLimitTheClerkGivesTheDifference() {
            limits("1100.00", "0.00", false);
            ediBillInException();
            signIn(CLERK, APPROVE, REJECT);

            assertThatThrownBy(() -> service.resolveException(BILL_ID, accept(null)))
                    .satisfies(
                            e -> assertThat(codeOf(e)).isEqualTo(VendorBillException.Code.AP_BILL_TOTALS_UNRECONCILED));
            service.resolveException(BILL_ID, accept(FREIGHT));
            assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.APPROVED);
            assertThat(bill.getDifferenceClass()).isEqualTo(VendorBillDifferenceClass.FREIGHT);
            assertThat(auditRows())
                    .last()
                    .satisfies(row -> assertThat(row.getNewValue()).contains("tier=CLERK", "differenceClass=FREIGHT"));
        }

        @Test
        @DisplayName("The 403s come before the 422s: an over-limit clerk on unreconciled totals without a difference is"
                + " 403, not 422; on a 0.00 bill too")
        void identityBeforeContent() {
            limits("1000.00", "0.00", false);
            ediBillInException();
            signIn(CLERK, APPROVE, REJECT);
            assertThatThrownBy(() -> service.resolveException(BILL_ID, accept(null)))
                    .satisfies(
                            e -> assertThat(codeOf(e)).isEqualTo(VendorBillException.Code.AP_APPROVAL_LIMIT_EXCEEDED));

            limits("0.00", "0.00", false);
            awaiting("0.00");
            assertThatThrownBy(() -> service.approve(BILL_ID, approve(null)))
                    .satisfies(
                            e -> assertThat(codeOf(e)).isEqualTo(VendorBillException.Code.AP_APPROVAL_LIMIT_EXCEEDED));
            signIn(GM, APPROVE, OVER_LIMIT);
            assertThatThrownBy(() -> service.approve(BILL_ID, approve(null)))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(VendorBillException.Code.AP_BILL_ZERO_TOTAL));
        }

        @Test
        @DisplayName("#2622 LOW-4: AP_BILL_UNCLASSIFIED is a content check after the 403s and before the posting:"
                + " an over-limit clerk is 403; an approver gets 422, audited as _REFUSED, and nothing posts")
        void unclassifiedIsAContentCheck() {
            awaiting("3000.00");
            org.mockito.Mockito.doThrow(new VendorBillException(
                            VendorBillException.Code.AP_BILL_UNCLASSIFIED, "Bill INV-2510 needs a classification"))
                    .when(postingService)
                    .requirePostable(any(), any(), any());
            signIn(CLERK, APPROVE, REJECT);
            assertThatThrownBy(() -> service.approve(BILL_ID, approve(null)))
                    .satisfies(
                            e -> assertThat(codeOf(e)).isEqualTo(VendorBillException.Code.AP_APPROVAL_LIMIT_EXCEEDED));

            signIn(GM, APPROVE, OVER_LIMIT);
            assertThatThrownBy(() -> service.approve(BILL_ID, approve(null)))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(VendorBillException.Code.AP_BILL_UNCLASSIFIED));
            verify(postingService, never()).post(any(), any(), any(), anyString());
            assertThat(auditRows())
                    .extracting(AccountingAuditLog::getOperation)
                    .containsExactly("VENDOR_BILL_APPROVE_REFUSED", "VENDOR_BILL_APPROVE_REFUSED");
            assertThat(auditRows().getLast().getNewValue()).contains("code=AP_BILL_UNCLASSIFIED");
        }

        private void ediBillInException() {
            bill.setOriginEventType(VendorBillReader.ORIGIN_SUPPLIER_INVOICE);
            bill.setStatus(VendorBillStatus.MATCH_EXCEPTION);
            bill.setTotalAmount(new BigDecimal("1085.00"));
            bill.setNetAmount(new BigDecimal("1000.00"));
            bill.setTaxAmount(new BigDecimal("70.00"));
            bill.setStatedLineCount(1);
        }
    }

    @Nested
    @DisplayName("The real due date (§4.2, AW11)")
    class DueDate {

        @Test
        @DisplayName("AC10/AC12: a clerk sets an AWAITING_APPROVAL bill's due date: stored at the start of the day,"
                + " audited old to new under the caller")
        void setsTheDueDate() {
            awaiting("5000.00");
            bill.setDueDate(LocalDateTime.of(2026, 10, 31, 0, 0));
            signIn(CLERK, APPROVE);

            service.setDueDate(
                    BILL_ID, new VendorBillCommands.SetDueDate(LocalDate.of(2026, 11, 7), "Read off the paper bill"));

            assertThat(bill.getDueDate()).isEqualTo(LocalDateTime.of(2026, 11, 7, 0, 0));
            assertThat(bill.getModifiedBy()).isEqualTo(CLERK);
            assertThat(auditRows()).singleElement().satisfies(row -> {
                assertThat(row.getOperation()).isEqualTo("VENDOR_BILL_DUE_DATE_SET");
                assertThat(row.getUserId()).isEqualTo(CLERK);
                assertThat(row.getOldValue()).isEqualTo("2026-10-31");
                assertThat(row.getNewValue()).contains("dueDate=2026-11-07");
                assertThat(row.getJustification()).isEqualTo("Read off the paper bill");
            });
        }

        @Test
        @DisplayName("AC10: an APPROVED or CURRENCY_HOLD bill is 409 AP_BILL_NOT_APPROVABLE; nothing is written")
        void onlyInReview() {
            signIn(CLERK, APPROVE);
            for (VendorBillStatus status : List.of(VendorBillStatus.APPROVED, VendorBillStatus.CURRENCY_HOLD)) {
                bill.setStatus(status);
                assertThatThrownBy(() -> service.setDueDate(
                                BILL_ID, new VendorBillCommands.SetDueDate(LocalDate.of(2026, 11, 7), null)))
                        .satisfies(
                                e -> assertThat(codeOf(e)).isEqualTo(VendorBillException.Code.AP_BILL_NOT_APPROVABLE));
            }
            verify(bills, never()).save(any());
            verify(auditLogs, never()).save(any());
        }

        @Test
        @DisplayName("A missing dueDate is 400 VALIDATION_ERROR naming the field; ap:reject alone is 403")
        void validation() {
            signIn(CLERK, APPROVE);
            assertThatThrownBy(() -> service.setDueDate(BILL_ID, new VendorBillCommands.SetDueDate(null, null)))
                    .isInstanceOfSatisfying(VendorBillException.class, e -> {
                        assertThat(e.getCode()).isEqualTo(VendorBillException.Code.VALIDATION_ERROR);
                        assertThat(e.getFieldErrors())
                                .extracting(VendorBillException.FieldError::field)
                                .containsExactly("dueDate");
                    });
            signIn("rejecter", REJECT);
            assertThatThrownBy(() -> service.setDueDate(
                            BILL_ID, new VendorBillCommands.SetDueDate(LocalDate.of(2026, 11, 7), null)))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        }
    }
}
