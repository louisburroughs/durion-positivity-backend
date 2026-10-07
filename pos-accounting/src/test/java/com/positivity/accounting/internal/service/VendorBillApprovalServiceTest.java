package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.VendorBillCommands;
import com.positivity.accounting.internal.dto.VendorBillResponse;
import com.positivity.accounting.internal.dto.VendorBillReview;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.entity.VendorBillGlPosting;
import com.positivity.accounting.internal.entity.VendorBillMatchCandidate;
import com.positivity.accounting.internal.entity.VendorBillMatchEvidence;
import com.positivity.accounting.internal.enums.MatchConfidence;
import com.positivity.accounting.internal.enums.VendorBillDebitClass;
import com.positivity.accounting.internal.enums.VendorBillPostingDateRule;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.exception.AccountingPeriodHardLockedException;
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
import java.util.EnumSet;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The vendor-bill approval lifecycle (CAP:550 S12, #2509): the transition matrix, the per-action permissions, the
 * justification rule, the actor from the security context and the audit rows. The posting is mocked here
 * (VendorBillPostingServiceTest); the database's row lock and rollback are VendorBillApprovalPostgresIT's.
 */
@DisplayName("VendorBillApprovalService: transitions, permissions and audit (#2509)")
class VendorBillApprovalServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-03T15:00:00Z"), ZoneOffset.UTC);
    private static final UUID BILL_ID = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a01");
    private static final String CLERK_JUSTIFICATION = "Service bill, no delivery";
    private static final String APPROVE = AccountingPermissions.AP_APPROVE;
    private static final String OVER_LIMIT = AccountingPermissions.AP_APPROVE_OVER_LIMIT;
    private static final String REJECT = AccountingPermissions.AP_REJECT;

    private final VendorBillRepository bills = mock();
    private final VendorBillMatchCandidateRepository candidates = mock();
    private final VendorBillMatchEvidenceRepository evidence = mock();
    private final APPaymentAllocationRepository allocations = mock();
    private final AccountingAuditLogRepository auditLogs = mock();
    private final VendorBillPostingService postingService = mock();
    private final VendorBillInvoiceMatcher matcher = mock();
    private final VendorBillDuplicateGuard duplicateGuard = mock();
    private final VendorBillReader reader = mock();
    private VendorBillApprovalServiceImpl service;
    private VendorBill bill;

    @BeforeEach
    void wire() {
        service = new VendorBillApprovalServiceImpl(
                CLOCK,
                bills,
                candidates,
                evidence,
                allocations,
                auditLogs,
                postingService,
                matcher,
                duplicateGuard,
                reader,
                new LedgerCurrency("USD"),
                mock(PlatformTransactionManager.class));
        bill = new VendorBill(BILL_ID);
        bill.setVendorId(UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a02"));
        bill.setBillNumber("INV-1");
        bill.setBillDate(LocalDateTime.of(2026, 10, 1, 0, 0));
        bill.setTotalAmount(new BigDecimal("412.00"));
        when(bills.lockById(BILL_ID)).thenAnswer(inv -> Optional.of(bill));
        when(bills.findById(BILL_ID)).thenAnswer(inv -> Optional.of(bill));
        when(bills.save(any(VendorBill.class))).thenAnswer(inv -> inv.getArgument(0));
        when(evidence.findFirstByVendorBillIdOrderByRecordedAtDescMatchEvidenceIdDesc(BILL_ID))
                .thenReturn(Optional.empty());
        when(reader.read(any(VendorBill.class)))
                .thenAnswer(inv -> VendorBillResponse.builder()
                        .vendorBillId(inv.getArgument(0, VendorBill.class).getVendorBillId())
                        .status(inv.getArgument(0, VendorBill.class).getStatus())
                        .build());
        when(postingService.post(any(), any(), any(), anyString())).thenAnswer(inv -> posting());
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static VendorBillGlPosting posting() {
        VendorBillGlPosting posting = new VendorBillGlPosting();
        posting.setJournalEntryId(UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a10"));
        posting.setPostingDate(LocalDate.of(2026, 10, 1));
        posting.setPostingDateRule(VendorBillPostingDateRule.BILL_DATE);
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

    private VendorBill in(VendorBillStatus status) {
        bill.setStatus(status);
        return bill;
    }

    private AccountingAuditLog onlyAudit() {
        ArgumentCaptor<AccountingAuditLog> row = ArgumentCaptor.forClass(AccountingAuditLog.class);
        verify(auditLogs).save(row.capture());
        return row.getValue();
    }

    private static void assertNoApprovalField(VendorBill bill) {
        assertThat(bill.getApprovedBy()).isNull();
        assertThat(bill.getApprovedAt()).isNull();
        assertThat(bill.getApprovalJustification()).isNull();
    }

    private static VendorBillException.Code codeOf(Throwable thrown) {
        return ((VendorBillException) thrown).getCode();
    }

    @Nested
    @DisplayName("Submit for approval")
    class Submit {

        @ParameterizedTest(name = "from {0}")
        @EnumSource(
                value = VendorBillStatus.class,
                names = {"PENDING_RECEIPT_MATCH", "MATCH_EXCEPTION"})
        @DisplayName("AC3: a clerk's 12-character justification sends the bill to AWAITING_APPROVAL, submittedBy the"
                + " clerk; nothing is posted")
        void sendsForApproval(VendorBillStatus from) {
            signIn("clerk.ana", APPROVE);
            in(from);

            VendorBillResponse response = service.submitForApproval(
                    BILL_ID,
                    new VendorBillCommands.Submit(
                            "No delivery!",
                            new VendorBillReview.Classification(
                                    VendorBillDebitClass.EXPENSE, "expense_shop_supplies")));

            assertThat(response.getStatus()).isEqualTo(VendorBillStatus.AWAITING_APPROVAL);
            assertThat(bill.getSubmittedBy()).isEqualTo("clerk.ana");
            assertThat(bill.getSubmittedAt()).isEqualTo(CLOCK.instant());
            assertThat(bill.getSubmissionJustification()).isEqualTo("No delivery!");
            assertThat(bill.getProposedDebitClass()).isEqualTo(VendorBillDebitClass.EXPENSE);
            assertThat(bill.getProposedExpenseMappingKey()).isEqualTo("EXPENSE_SHOP_SUPPLIES");
            assertNoApprovalField(bill);
            verify(postingService, never()).post(any(), any(), any(), anyString());
            AccountingAuditLog audit = onlyAudit();
            assertThat(audit.getOperation()).isEqualTo("VENDOR_BILL_SUBMIT");
            assertThat(audit.getEntityType()).isEqualTo("VENDOR_BILL");
            assertThat(audit.getUserId()).isEqualTo("clerk.ana");
            assertThat(audit.getNewValue()).contains("tier=OVER_LIMIT", "limit=0", "currencyCode=USD");
        }

        @Test
        @DisplayName("AC3: a 5-character justification is 400 JUSTIFICATION_REQUIRED; nothing changes")
        void shortJustificationIsRefused() {
            signIn("clerk.ana", APPROVE);
            in(VendorBillStatus.PENDING_RECEIPT_MATCH);

            assertThatThrownBy(() -> service.submitForApproval(BILL_ID, new VendorBillCommands.Submit("short", null)))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(VendorBillException.Code.JUSTIFICATION_REQUIRED));
            assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.PENDING_RECEIPT_MATCH);
            verify(bills, never()).save(any());
        }

        @ParameterizedTest(name = "from {0}")
        @EnumSource(
                value = VendorBillStatus.class,
                names = {"CURRENCY_HOLD", "AWAITING_APPROVAL", "APPROVED", "REJECTED", "VOIDED", "PAID"})
        @DisplayName("AC9: CURRENCY_HOLD and every other status is 409 AP_BILL_NOT_APPROVABLE naming the status")
        void otherStatusesAreRefused(VendorBillStatus from) {
            signIn("clerk.ana", APPROVE);
            in(from);

            assertThatThrownBy(() -> service.submitForApproval(
                            BILL_ID, new VendorBillCommands.Submit(CLERK_JUSTIFICATION, null)))
                    .isInstanceOf(VendorBillException.class)
                    .hasMessageContaining(from.name())
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(VendorBillException.Code.AP_BILL_NOT_APPROVABLE));
            assertThat(bill.getStatus()).isEqualTo(from);
        }

        @Test
        @DisplayName("A missing bill is 404 VENDOR_BILL_NOT_FOUND, naming no id")
        void missingBill() {
            signIn("clerk.ana", APPROVE);
            UUID other = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4aff");
            when(bills.lockById(other)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                            service.submitForApproval(other, new VendorBillCommands.Submit(CLERK_JUSTIFICATION, null)))
                    .hasMessage("Vendor bill not found")
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(VendorBillException.Code.VENDOR_BILL_NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("Approve")
    class Approve {

        @Test
        @DisplayName(
                "AC4: a CONTROLLER approves an AWAITING_APPROVAL bill: APPROVED, approvedBy the controller, posted,"
                        + " one VENDOR_BILL_APPROVE audit row")
        void approvesAndPosts() {
            signIn("controller.cfo", OVER_LIMIT, APPROVE, REJECT);
            in(VendorBillStatus.AWAITING_APPROVAL);
            bill.setProposedDebitClass(VendorBillDebitClass.GOODS);

            VendorBillResponse response =
                    service.approve(BILL_ID, new VendorBillCommands.Approve("Checked against delivery", null, null));

            assertThat(response.getStatus()).isEqualTo(VendorBillStatus.APPROVED);
            assertThat(bill.getApprovedBy()).isEqualTo("controller.cfo");
            assertThat(bill.getApprovedAt()).isEqualTo(CLOCK.instant());
            assertThat(bill.getApprovalJustification()).isEqualTo("Checked against delivery");
            ArgumentCaptor<VendorBillPostingService.Classification> classification =
                    ArgumentCaptor.forClass(VendorBillPostingService.Classification.class);
            verify(postingService).post(eq(bill), classification.capture(), eq(null), eq("controller.cfo"));
            assertThat(classification.getValue().debitClass())
                    .as("the classification proposed at submission")
                    .isEqualTo(VendorBillDebitClass.GOODS);
            AccountingAuditLog audit = onlyAudit();
            assertThat(audit.getOperation()).isEqualTo("VENDOR_BILL_APPROVE");
            assertThat(audit.getUserId()).isEqualTo("controller.cfo");
            assertThat(audit.getNewValue()).contains("postingDateRule=BILL_DATE", "journalEntryId=");
        }

        @Test
        @DisplayName("AC4: a second approve is 409 AP_BILL_NOT_APPROVABLE naming APPROVED, and posts nothing")
        void secondApproveIsRefused() {
            signIn("controller.cfo", OVER_LIMIT);
            in(VendorBillStatus.APPROVED);

            assertThatThrownBy(() -> service.approve(BILL_ID, new VendorBillCommands.Approve(null, null, null)))
                    .hasMessageContaining("APPROVED")
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(VendorBillException.Code.AP_BILL_NOT_APPROVABLE));
            verify(postingService, never()).post(any(), any(), any(), anyString());
        }

        @Test
        @DisplayName("AC7/AC10: approve needs accounting:ap:approve_over_limit in this story; ap:approve and ap:pay"
                + " alone are 403")
        void approveNeedsTheOverLimitTier() {
            in(VendorBillStatus.AWAITING_APPROVAL);
            for (String[] held : List.of(
                    new String[] {APPROVE}, new String[] {"accounting:ap:pay"}, new String[] {REJECT, APPROVE})) {
                signIn("clerk.ana", held);
                assertThatThrownBy(() -> service.approve(BILL_ID, new VendorBillCommands.Approve(null, null, null)))
                        .isInstanceOf(AccessDeniedException.class);
            }
            assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.AWAITING_APPROVAL);
        }

        @Test
        @DisplayName("AC13(c): a refused posting rolls the approval back, no approval field, and writes one refusal"
                + " audit row")
        void refusedPostingRollsTheApprovalBack() {
            signIn("controller.cfo", OVER_LIMIT);
            in(VendorBillStatus.AWAITING_APPROVAL);
            when(postingService.post(any(), any(), any(), anyString()))
                    .thenThrow(new AccountingPeriodHardLockedException(LocalDate.of(2026, 11, 1), "hard-locked"));

            assertThatThrownBy(() -> service.approve(BILL_ID, new VendorBillCommands.Approve(null, null, null)))
                    .isInstanceOf(AccountingPeriodHardLockedException.class);

            assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.AWAITING_APPROVAL);
            assertNoApprovalField(bill);
            AccountingAuditLog audit = onlyAudit();
            assertThat(audit.getOperation()).isEqualTo("VENDOR_BILL_APPROVE_REFUSED");
            assertThat(audit.getNewValue()).contains("code=PERIOD_HARD_LOCKED");
        }

        @Test
        @DisplayName("An override justification under 10 characters is 400 JUSTIFICATION_REQUIRED")
        void shortOverrideIsRefused() {
            signIn("controller.cfo", OVER_LIMIT);
            in(VendorBillStatus.AWAITING_APPROVAL);

            assertThatThrownBy(() -> service.approve(BILL_ID, new VendorBillCommands.Approve(null, null, "late")))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(VendorBillException.Code.JUSTIFICATION_REQUIRED));
        }

        @Test
        @DisplayName("A malformed classification is 400 VALIDATION_ERROR")
        void malformedClassification() {
            signIn("controller.cfo", OVER_LIMIT);
            in(VendorBillStatus.AWAITING_APPROVAL);

            for (VendorBillReview.Classification bad : List.of(
                    new VendorBillReview.Classification(VendorBillDebitClass.EXPENSE, null),
                    new VendorBillReview.Classification(VendorBillDebitClass.RECEIPT_MATCHED, null),
                    new VendorBillReview.Classification(VendorBillDebitClass.EXPENSE, "SHOP_SUPPLIES"))) {
                assertThatThrownBy(() -> service.approve(BILL_ID, new VendorBillCommands.Approve(null, bad, null)))
                        .satisfies(e -> assertThat(codeOf(e)).isEqualTo(VendorBillException.Code.VALIDATION_ERROR));
            }
        }

        @Test
        @DisplayName("AC9: a CURRENCY_HOLD bill is never approved")
        void currencyHoldIsNeverApproved() {
            signIn("controller.cfo", OVER_LIMIT);
            in(VendorBillStatus.CURRENCY_HOLD);

            assertThatThrownBy(() -> service.approve(BILL_ID, new VendorBillCommands.Approve(null, null, null)))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(VendorBillException.Code.AP_BILL_NOT_APPROVABLE));
            verify(postingService, never()).post(any(), any(), any(), anyString());
        }
    }

    @Nested
    @DisplayName("Reject")
    class Reject {

        @Test
        @DisplayName("AC5: a clerk holding ap:reject rejects with a 10-character reason: REJECTED with rejectedBy,"
                + " rejectedAt and the reason; nothing posted")
        void rejects() {
            signIn("clerk.ana", REJECT);
            in(VendorBillStatus.AWAITING_APPROVAL);

            service.reject(BILL_ID, new VendorBillCommands.Reject("Wrong vendor"));

            assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.REJECTED);
            assertThat(bill.getRejectedBy()).isEqualTo("clerk.ana");
            assertThat(bill.getRejectedAt()).isEqualTo(CLOCK.instant());
            assertThat(bill.getRejectionReason()).isEqualTo("Wrong vendor");
            assertNoApprovalField(bill);
            verify(postingService, never()).post(any(), any(), any(), anyString());
            assertThat(onlyAudit().getOperation()).isEqualTo("VENDOR_BILL_REJECT");
        }

        @Test
        @DisplayName("AC5: a shorter reason is 400 JUSTIFICATION_REQUIRED")
        void shortReason() {
            signIn("clerk.ana", REJECT);
            in(VendorBillStatus.AWAITING_APPROVAL);

            assertThatThrownBy(() -> service.reject(BILL_ID, new VendorBillCommands.Reject("No")))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(VendorBillException.Code.JUSTIFICATION_REQUIRED));
            assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.AWAITING_APPROVAL);
        }

        @Test
        @DisplayName("An APPROVED bill is never rejected (AW42)")
        void approvedIsNeverRejected() {
            signIn("controller.cfo", REJECT);
            in(VendorBillStatus.APPROVED);

            assertThatThrownBy(() -> service.reject(BILL_ID, new VendorBillCommands.Reject("Changed my mind")))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(VendorBillException.Code.AP_BILL_NOT_APPROVABLE));
        }
    }

    @Nested
    @DisplayName("Resolve a match exception")
    class Resolve {

        static Stream<Arguments> permissionMatrix() {
            return Stream.of(
                    Arguments.of("ACCEPT", new String[] {APPROVE}, false),
                    Arguments.of("ACCEPT", new String[] {REJECT}, false),
                    Arguments.of("ACCEPT", new String[] {OVER_LIMIT}, true),
                    Arguments.of("CORRECT", new String[] {APPROVE}, true),
                    Arguments.of("CORRECT", new String[] {OVER_LIMIT}, true),
                    Arguments.of("CORRECT", new String[] {REJECT}, false),
                    Arguments.of("VOID", new String[] {APPROVE, OVER_LIMIT}, false),
                    Arguments.of("VOID", new String[] {REJECT}, true),
                    Arguments.of("ACCEPT", new String[] {"accounting:ap:pay"}, false));
        }

        @ParameterizedTest(name = "{0} with {1}: allowed={2}")
        @MethodSource("permissionMatrix")
        @DisplayName("AC7: each action is checked against its own permission")
        void eachActionHasItsPermission(String action, String[] held, boolean allowed) {
            signIn("someone", held);
            in(VendorBillStatus.MATCH_EXCEPTION);
            VendorBillCommands.ResolveException command =
                    new VendorBillCommands.ResolveException(action, "Agreed with the vendor", null, null);

            if (allowed) {
                service.resolveException(BILL_ID, command);
            } else {
                assertThatThrownBy(() -> service.resolveException(BILL_ID, command))
                        .isInstanceOf(AccessDeniedException.class);
                assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.MATCH_EXCEPTION);
            }
        }

        @Test
        @DisplayName("ACCEPT is an approval: APPROVED, posted, approved by the caller with the reason as justification")
        void acceptApprovesAndPosts() {
            signIn("controller.cfo", OVER_LIMIT);
            in(VendorBillStatus.MATCH_EXCEPTION);

            service.resolveException(
                    BILL_ID, new VendorBillCommands.ResolveException("accept", "Price rise agreed", null, null));

            assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.APPROVED);
            assertThat(bill.getApprovedBy()).isEqualTo("controller.cfo");
            assertThat(bill.getApprovalJustification()).isEqualTo("Price rise agreed");
            verify(postingService).post(eq(bill), any(), eq(null), eq("controller.cfo"));
            assertThat(onlyAudit().getNewValue()).contains("action=ACCEPT");
        }

        @Test
        @DisplayName("AC7: CORRECT writes no approval or rejection field")
        void correctWritesNoDecisionField() {
            signIn("clerk.ana", APPROVE);
            in(VendorBillStatus.MATCH_EXCEPTION);

            service.resolveException(
                    BILL_ID, new VendorBillCommands.ResolveException("CORRECT", "Recount the delivery", null, null));

            assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.PENDING_RECEIPT_MATCH);
            assertNoApprovalField(bill);
            assertThat(bill.getRejectedBy()).isNull();
            assertThat(bill.getRejectedAt()).isNull();
            verify(postingService, never()).post(any(), any(), any(), anyString());
        }

        @Test
        @DisplayName("VOID voids the bill with the caller as rejectedBy and posts nothing")
        void voidVoids() {
            signIn("clerk.ana", REJECT);
            in(VendorBillStatus.MATCH_EXCEPTION);

            service.resolveException(
                    BILL_ID, new VendorBillCommands.ResolveException("VOID", "Duplicate of INV-0", null, null));

            assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.VOIDED);
            assertThat(bill.getRejectedBy()).isEqualTo("clerk.ana");
            assertThat(bill.getRejectionReason()).isEqualTo("Duplicate of INV-0");
            verify(postingService, never()).post(any(), any(), any(), anyString());
            verify(postingService, never()).reverse(any(), any(), anyString());
        }

        @Test
        @DisplayName("An unknown action is 400 VALIDATION_ERROR; a short reason is 400 JUSTIFICATION_REQUIRED")
        void unknownActionAndShortReason() {
            signIn("controller.cfo", OVER_LIMIT, APPROVE, REJECT);
            in(VendorBillStatus.MATCH_EXCEPTION);

            assertThatThrownBy(() -> service.resolveException(
                            BILL_ID,
                            new VendorBillCommands.ResolveException("APPROVE", "Agreed with vendor", null, null)))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(VendorBillException.Code.VALIDATION_ERROR));
            assertThatThrownBy(() -> service.resolveException(
                            BILL_ID, new VendorBillCommands.ResolveException("CORRECT", "short", null, null)))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(VendorBillException.Code.JUSTIFICATION_REQUIRED));
        }

        @Test
        @DisplayName("A bill not in MATCH_EXCEPTION is 409 AP_BILL_NOT_APPROVABLE")
        void wrongStatus() {
            signIn("controller.cfo", OVER_LIMIT);
            in(VendorBillStatus.AWAITING_APPROVAL);

            assertThatThrownBy(() -> service.resolveException(
                            BILL_ID,
                            new VendorBillCommands.ResolveException("ACCEPT", "Agreed with vendor", null, null)))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(VendorBillException.Code.AP_BILL_NOT_APPROVABLE));
        }
    }

    @Nested
    @DisplayName("Select a match candidate")
    class Select {

        private final UUID invoiceEventId = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a20");
        private final UUID chosenId = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a21");
        private final UUID otherId = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a22");

        private VendorBillMatchCandidate candidate(UUID id, boolean resolved) {
            VendorBillMatchCandidate candidate = new VendorBillMatchCandidate();
            candidate.setCandidateId(id);
            candidate.setInvoiceEventId(invoiceEventId);
            candidate.setVendorBillId(BILL_ID);
            candidate.setMatchScore(65);
            candidate.setAmountPoints(40);
            candidate.setProductPoints(0);
            candidate.setDatePoints(20);
            candidate.setPurchaseOrderPoints(5);
            candidate.setInvoiceReference("INV-77");
            candidate.setInvoiceDate(LocalDateTime.of(2026, 10, 1, 0, 0));
            candidate.setInvoiceLines(List.of());
            candidate.setResolved(resolved);
            candidate.setResolvedBy(resolved ? "clerk.bob" : null);
            return candidate;
        }

        @Test
        @DisplayName("AC8: the selected bill goes to AWAITING_APPROVAL, never APPROVED; the set is resolved; the"
                + " billed amounts and the evidence are kept")
        void selectionSendsForApproval() {
            signIn("clerk.ana", APPROVE);
            in(VendorBillStatus.MATCH_EXCEPTION);
            VendorBillMatchCandidate chosen = candidate(chosenId, false);
            VendorBillMatchCandidate other = candidate(otherId, false);
            when(candidates.findById(chosenId)).thenReturn(Optional.of(chosen));
            when(candidates.lockByInvoiceEventId(invoiceEventId)).thenReturn(List.of(chosen, other));
            when(matcher.applyBilled(eq(bill), any()))
                    .thenReturn(new VendorBillInvoiceMatcher.Comparison(
                            List.of(), true, new BigDecimal("400.00"), new BigDecimal("400.00")));

            service.selectCandidate(chosenId);

            assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.AWAITING_APPROVAL);
            assertThat(bill.getSubmittedBy()).isEqualTo("clerk.ana");
            assertThat(bill.getBillNumber()).isEqualTo("INV-77");
            assertNoApprovalField(bill);
            assertThat(chosen.isResolved()).isTrue();
            assertThat(chosen.isSelected()).isTrue();
            assertThat(other.isResolved()).isTrue();
            assertThat(other.isSelected()).isFalse();
            assertThat(chosen.getResolvedBy()).isEqualTo("clerk.ana");
            verify(matcher)
                    .record(
                            eq(bill),
                            eq(invoiceEventId),
                            eq(VendorBillMatchEvidence.Source.CANDIDATE_SELECTION),
                            eq(MatchConfidence.AMBIGUOUS),
                            eq(new VendorBillInvoiceMatcher.Points(40, 0, 20, 5)),
                            eq("INV-77"),
                            any(),
                            any(),
                            eq("clerk.ana"));
            verify(postingService, never()).post(any(), any(), any(), anyString());
            assertThat(onlyAudit().getOperation()).isEqualTo("VENDOR_BILL_MATCH_CANDIDATE_SELECT");
        }

        @Test
        @DisplayName("A candidate set already resolved is 409 AP_MATCH_CANDIDATE_ALREADY_RESOLVED")
        void alreadyResolved() {
            signIn("clerk.ana", APPROVE);
            VendorBillMatchCandidate chosen = candidate(chosenId, true);
            when(candidates.findById(chosenId)).thenReturn(Optional.of(chosen));
            when(candidates.lockByInvoiceEventId(invoiceEventId)).thenReturn(List.of(chosen));

            assertThatThrownBy(() -> service.selectCandidate(chosenId))
                    .satisfies(e -> assertThat(codeOf(e))
                            .isEqualTo(VendorBillException.Code.AP_MATCH_CANDIDATE_ALREADY_RESOLVED));
            verify(bills, never()).save(any());
        }

        @Test
        @DisplayName("A missing candidate is 404 AP_MATCH_CANDIDATE_NOT_FOUND; ap:reject alone is 403")
        void missingCandidateAndPermission() {
            signIn("clerk.ana", APPROVE);
            when(candidates.findById(chosenId)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.selectCandidate(chosenId))
                    .satisfies(e ->
                            assertThat(codeOf(e)).isEqualTo(VendorBillException.Code.AP_MATCH_CANDIDATE_NOT_FOUND));

            signIn("clerk.ana", REJECT);
            assertThatThrownBy(() -> service.selectCandidate(chosenId)).isInstanceOf(AccessDeniedException.class);
        }
    }

    @Nested
    @DisplayName("Void an approved bill (AW42)")
    class VoidApproved {

        @Test
        @DisplayName("AC13(d): an unpaid approved bill is voided; its entry is reversed; VENDOR_BILL_VOID is audited")
        void voidsAnUnpaidApprovedBill() {
            signIn("controller.cfo", REJECT, OVER_LIMIT);
            in(VendorBillStatus.APPROVED);
            when(allocations.existsByVendorBill_VendorBillId(BILL_ID)).thenReturn(false);
            VendorBillGlPosting reversed = posting();
            reversed.setReversalJournalEntryId(UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a11"));
            reversed.setReversalDate(LocalDate.of(2026, 10, 3));
            when(postingService.reverse(bill, null, "controller.cfo")).thenReturn(reversed);

            service.voidApproved(BILL_ID, new VendorBillCommands.VoidApproved("Billed twice by mistake", null));

            assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.VOIDED);
            assertThat(bill.getRejectedBy()).isEqualTo("controller.cfo");
            assertThat(bill.getRejectionReason()).isEqualTo("Billed twice by mistake");
            AccountingAuditLog audit = onlyAudit();
            assertThat(audit.getOperation()).isEqualTo("VENDOR_BILL_VOID");
            assertThat(audit.getNewValue()).contains("voidDate=2026-10-03");
        }

        @Test
        @DisplayName("AC13(d): a partly paid bill is 409 AP_BILL_NOT_VOIDABLE and reverses nothing")
        void allocatedBillIsNotVoidable() {
            signIn("controller.cfo", REJECT, OVER_LIMIT);
            in(VendorBillStatus.APPROVED);
            when(allocations.existsByVendorBill_VendorBillId(BILL_ID)).thenReturn(true);

            assertThatThrownBy(() ->
                            service.voidApproved(BILL_ID, new VendorBillCommands.VoidApproved("Billed twice", null)))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(VendorBillException.Code.AP_BILL_NOT_VOIDABLE));
            verify(postingService, never()).reverse(any(), any(), anyString());
            assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.APPROVED);
        }

        @Test
        @DisplayName("The void needs ap:reject plus the approval tier; either alone is 403")
        void voidNeedsRejectAndTheTier() {
            in(VendorBillStatus.APPROVED);
            for (String[] held :
                    List.of(new String[] {REJECT}, new String[] {OVER_LIMIT}, new String[] {REJECT, APPROVE})) {
                signIn("someone", held);
                assertThatThrownBy(() -> service.voidApproved(
                                BILL_ID, new VendorBillCommands.VoidApproved("Billed twice by mistake", null)))
                        .isInstanceOf(AccessDeniedException.class);
            }
            verify(postingService, times(0)).reverse(any(), any(), anyString());
        }

        @Test
        @DisplayName("Only an APPROVED bill is voided this way")
        void onlyApproved() {
            signIn("controller.cfo", REJECT, OVER_LIMIT);
            for (VendorBillStatus status : EnumSet.complementOf(EnumSet.of(VendorBillStatus.APPROVED))) {
                in(status);
                assertThatThrownBy(() -> service.voidApproved(
                                BILL_ID, new VendorBillCommands.VoidApproved("Billed twice by mistake", null)))
                        .satisfies(e -> assertThat(codeOf(e)).isEqualTo(VendorBillException.Code.AP_BILL_NOT_VOIDABLE));
            }
        }
    }
}
