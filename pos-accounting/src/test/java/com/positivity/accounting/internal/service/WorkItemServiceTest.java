package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.bankrec.service.BankRecPolicy;
import com.positivity.accounting.internal.bankrec.service.CandidateFinder;
import com.positivity.accounting.internal.dto.PaymentMatchSuggestion;
import com.positivity.accounting.internal.dto.UnappliedPaymentRow;
import com.positivity.accounting.internal.dto.UnappliedPaymentsPage;
import com.positivity.accounting.internal.dto.UndepositedSessionsResponse;
import com.positivity.accounting.internal.dto.UnpaidWalkInSalesResponse;
import com.positivity.accounting.internal.dto.WalkInOpenInvoice;
import com.positivity.accounting.internal.dto.WorkItem;
import com.positivity.accounting.internal.dto.WorkItemAction;
import com.positivity.accounting.internal.dto.WorkItemPostingLine;
import com.positivity.accounting.internal.dto.WorkItemsResponse;
import com.positivity.accounting.internal.entity.APPayment;
import com.positivity.accounting.internal.entity.ExtLocationReplica;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.UndepositedSession;
import com.positivity.accounting.internal.entity.UndepositedSessionDrop;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.entity.VendorBillMatchCandidate;
import com.positivity.accounting.internal.enums.APPaymentStatus;
import com.positivity.accounting.internal.enums.PaymentMethod;
import com.positivity.accounting.internal.enums.TermsSource;
import com.positivity.accounting.internal.enums.UndepositedSessionStatus;
import com.positivity.accounting.internal.enums.VendorBillApproverKind;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.enums.WorkItemArea;
import com.positivity.accounting.internal.enums.WorkItemType;
import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import com.positivity.accounting.internal.exception.WorkItemsCursorInvalidException;
import com.positivity.accounting.internal.repository.APPaymentRepository;
import com.positivity.accounting.internal.repository.ExtLocationReplicaRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.UndepositedSessionDropRepository;
import com.positivity.accounting.internal.repository.VendorBillLineRepository;
import com.positivity.accounting.internal.repository.VendorBillMatchCandidateRepository;
import com.positivity.accounting.internal.repository.VendorBillMatchEvidenceRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.CashAttentionSources.AgedSession;
import com.positivity.accounting.internal.service.EstimatedDueDateService.EstimatedDueDate;
import com.positivity.accounting.internal.service.OpenVendorBills.Open;
import com.positivity.accounting.internal.service.OpenVendorBills.OpenBill;
import com.positivity.security.common.GatewaySecurityConstants;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The to-do list of #2515 (CAP:550 S19; SPEC-accounting-workspace §7.1 "Work items"): every type with a permitted and
 * an unpermitted caller, the blocked actions of P5 (AC 9, AC 10), the counts per area, and the cursor. The four area
 * sources are the real classes over mocked repositories, so the gates and the rules run as they do in production.
 */
@DisplayName("WorkItemService (#2515)")
class WorkItemServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-17T15:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 17);
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static final String[] APPROVER = {
        AccountingPermissions.AP_APPROVE, AccountingPermissions.AP_APPROVE_OVER_LIMIT, AccountingPermissions.AP_VIEW
    };
    private static final String[] CLERK = {AccountingPermissions.AP_APPROVE, AccountingPermissions.AP_VIEW};
    private static final String[] PAYER = {AccountingPermissions.AP_PAY, AccountingPermissions.AP_VIEW};

    private final VendorBillRepository vendorBills = mock();
    private final VendorBillMatchCandidateRepository candidates = mock();
    private final VendorBillMatchEvidenceRepository evidence = mock();
    private final VendorBillLineRepository billLines = mock();
    private final ApApprovalPolicy policy = mock();
    private final SupplierVendorCopies vendorCopies = mock();
    private final OpenVendorBills openBills = mock();
    private final BillApprovalPreview approvalPreview = mock();
    private final AccountingCalendarZoneResolver zoneResolver = mock();
    private final ReceivablesWorklistService receivables = mock();
    private final UnpaidWalkInSalesService walkInSales = mock();
    private final APPaymentRepository apPayments = mock();
    private final GLAccountRepository glAccounts = mock();
    private final GLMappingResolver glMappingResolver = mock();
    private final AccountingPeriodGate periodGate = mock();
    private final CashAttentionSources attentionSources = mock();
    private final CashAndPayablesSettings settings = mock();
    private final DepositService depositService = mock();
    private final UndepositedSessionDropRepository drops = mock();
    private final ExtLocationReplicaRepository locations = mock();
    private final BankReconciliationRepository reconciliations = mock();
    private final BankTransactionRepository transactions = mock();
    private final CandidateFinder candidateFinder = mock();
    private final BankRecPolicy bankRecPolicy = mock();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private final List<VendorBill> reviewBills = new ArrayList<>();
    private final List<OpenBill> approvedBills = new ArrayList<>();
    private final List<AgedSession> undeposited = new ArrayList<>();
    private WorkItemServiceImpl service;

    @BeforeEach
    void setUp() {
        when(zoneResolver.today()).thenReturn(TODAY);
        when(zoneResolver.postingDate(any(Instant.class)))
                .thenAnswer(invocation -> LocalDate.ofInstant(invocation.getArgument(0), ZoneOffset.UTC));
        when(vendorBills.findByStatusIn(any())).thenAnswer(invocation -> List.copyOf(reviewBills));
        when(vendorBills.findAllById(any())).thenAnswer(invocation -> {
            Iterable<UUID> ids = invocation.getArgument(0);
            List<UUID> wanted = new ArrayList<>();
            ids.forEach(wanted::add);
            return reviewBills.stream()
                    .filter(bill -> wanted.contains(bill.getVendorBillId()))
                    .toList();
        });
        when(candidates.findByVendorBill_VendorBillIdInAndResolvedFalse(any())).thenReturn(List.of());
        when(policy.settings()).thenReturn(policySettings(false));
        when(openBills.load()).thenAnswer(invocation -> new Open(List.copyOf(approvedBills), List.of()));
        when(approvalPreview.preview(any())).thenReturn(List.of());
        when(receivables.listUnappliedPayments(isNull(), anyInt(), anyInt()))
                .thenReturn(UnappliedPaymentsPage.builder()
                        .items(List.of())
                        .totalPages(0)
                        .build());
        when(walkInSales.read())
                .thenReturn(UnpaidWalkInSalesResponse.builder()
                        .openInvoices(List.of())
                        .build());
        when(apPayments.findByStatusOrderByPaymentDateAscPaymentIdAsc(any())).thenReturn(List.of());
        when(attentionSources.undepositedSessions()).thenAnswer(invocation -> List.copyOf(undeposited));
        when(settings.undepositedCashAgedDays()).thenReturn(3);
        when(drops.findByUndepositedSessionIdInOrderByOccurredAtAscMovementIdAsc(any()))
                .thenReturn(List.of());
        when(depositService.undeposited(any(), any()))
                .thenReturn(new UndepositedSessionsResponse(TODAY, "USD", List.of(), null));
        when(locations.findAllById(any())).thenReturn(List.of());
        when(reconciliations.findByStatus(any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(bankRecPolicy.allowSelfApproval()).thenReturn(false);

        @SuppressWarnings("unchecked")
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registry = mock(ObjectProvider.class);
        when(registry.getIfAvailable()).thenReturn(meters);
        service = new WorkItemServiceImpl(
                new BillWorkItemSource(
                        vendorBills,
                        candidates,
                        evidence,
                        billLines,
                        policy,
                        vendorCopies,
                        openBills,
                        approvalPreview,
                        zoneResolver),
                new PaymentWorkItemSource(
                        receivables, walkInSales, apPayments, glAccounts, glMappingResolver, periodGate, zoneResolver),
                new CashWorkItemSource(attentionSources, settings, depositService, drops, locations),
                new BankWorkItemSource(reconciliations, transactions, candidateFinder, bankRecPolicy, zoneResolver),
                new WorkItemPaging(),
                zoneResolver,
                Clock.fixed(NOW, ZoneOffset.UTC),
                registry);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(String user, String... authorities) {
        UsernamePasswordAuthenticationToken caller = new UsernamePasswordAuthenticationToken(
                user,
                "n/a",
                Stream.of(authorities).map(SimpleGrantedAuthority::new).toList());
        caller.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, user));
        SecurityContextHolder.getContext().setAuthentication(caller);
    }

    private static ApApprovalPolicy.Settings policySettings(boolean allowApproverPayment) {
        return new ApApprovalPolicy.Settings(
                new BigDecimal("2500.00"), BigDecimal.ZERO.setScale(2), false, allowApproverPayment, "NET30");
    }

    private WorkItemsResponse list() {
        return service.list(null, null, null);
    }

    private static List<WorkItemType> types(WorkItemsResponse response) {
        return response.items().stream().map(WorkItem::type).toList();
    }

    private static WorkItem only(WorkItemsResponse response, WorkItemType type) {
        List<WorkItem> matching =
                response.items().stream().filter(item -> item.type() == type).toList();
        assertThat(matching).as("items of %s", type).hasSize(1);
        return matching.getFirst();
    }

    // ---- fixtures ---------------------------------------------------------------------------------------------

    private VendorBill reviewBill(VendorBillStatus status, String total, String createdBy) {
        VendorBill bill = new VendorBill(UUID.randomUUID());
        bill.setVendorId(UUID.randomUUID());
        bill.setVendorName("Acme Tires");
        bill.setBillNumber("INV-" + (reviewBills.size() + 1));
        bill.setPurchaseOrderNumber("PO-7");
        bill.setStatus(status);
        bill.setTotalAmount(new BigDecimal(total));
        bill.setBillDate(LocalDateTime.of(2026, 10, 1, 9, 0));
        bill.setCreatedAt(Instant.parse("2026-10-05T10:00:00Z"));
        bill.setSubmittedAt(Instant.parse("2026-10-06T10:00:00Z"));
        bill.setCreatedBy(createdBy);
        reviewBills.add(bill);
        return bill;
    }

    private OpenBill approvedBill(String number, LocalDate due, String open, String approvedBy) {
        VendorBill bill = new VendorBill(UUID.randomUUID());
        bill.setVendorId(UUID.randomUUID());
        bill.setVendorName("Acme Tires");
        bill.setBillNumber(number);
        bill.setStatus(VendorBillStatus.APPROVED);
        bill.setTotalAmount(new BigDecimal(open));
        bill.setApprovedBy(approvedBy);
        bill.setApprovedByKind(VendorBillApproverKind.PERSON);
        OpenBill openBill = new OpenBill(bill, new BigDecimal(open), due, null);
        approvedBills.add(openBill);
        return openBill;
    }

    private static UndepositedSession session(
            UUID location, String terminal, String drops, String expected, String clearingNet) {
        UndepositedSession session = new UndepositedSession();
        session.setUndepositedSessionId(UUID.randomUUID());
        session.setSessionId(UUID.randomUUID());
        session.setLocationId(location);
        session.setTerminalId(terminal);
        session.setClosedAt(Instant.parse("2026-10-10T12:00:00Z"));
        session.setDepositAmount(new BigDecimal(drops));
        session.setExpectedCash(new BigDecimal(expected));
        session.setClearingNet(new BigDecimal(clearingNet));
        session.setStatus(UndepositedSessionStatus.UNDEPOSITED);
        return session;
    }

    private AgedSession undepositedSession(UndepositedSession session, LocalDate businessDate) {
        boolean balanced = session.getDepositAmount()
                        .subtract(session.getExpectedCash())
                        .subtract(session.getClearingNet())
                        .signum()
                == 0;
        AgedSession aged = new AgedSession(session, businessDate, balanced);
        undeposited.add(aged);
        return aged;
    }

    private static GLAccount account(String code, String name) {
        GLAccount account = new GLAccount();
        account.setGlAccountId(UUID.randomUUID());
        account.setAccountCode(code);
        account.setAccountName(name);
        return account;
    }

    // ---- bills: AC 9 ------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "AC9: an OVER_LIMIT bill awaiting approval is the approver's alone; the clerk sees only CLERK-tier bills")
    void awaitingApprovalByTier() {
        VendorBill overLimit = reviewBill(VendorBillStatus.AWAITING_APPROVAL, "3000.00", "bob");
        VendorBill small = reviewBill(VendorBillStatus.AWAITING_APPROVAL, "100.00", "bob");

        signIn("alice", APPROVER);
        assertThat(list().items())
                .extracting(WorkItem::itemId)
                .containsExactlyInAnyOrder(
                        "BILL_AWAITING_APPROVAL:" + overLimit.getVendorBillId(),
                        "BILL_AWAITING_APPROVAL:" + small.getVendorBillId());

        signIn("carol", CLERK);
        WorkItemsResponse clerk = list();
        assertThat(clerk.items())
                .extracting(WorkItem::itemId)
                .containsExactly("BILL_AWAITING_APPROVAL:" + small.getVendorBillId());
        assertThat(clerk.items().getFirst().reasonCode()).isEqualTo("CLERK");
    }

    @Test
    @DisplayName(
            "AC9: the creator clerk sees APPROVE allowed:false with AP_BILL_SELF_APPROVAL; another clerk may approve")
    void creatorCannotApprove() {
        reviewBill(VendorBillStatus.AWAITING_APPROVAL, "100.00", "carol");

        signIn("carol", CLERK);
        WorkItem mine = only(list(), WorkItemType.BILL_AWAITING_APPROVAL);
        assertThat(mine.actions())
                .filteredOn(action -> action.action().equals("APPROVE"))
                .singleElement()
                .satisfies(approve -> {
                    assertThat(approve.allowed()).isFalse();
                    assertThat(approve.blockedReason()).isEqualTo("AP_BILL_SELF_APPROVAL");
                });

        signIn("dave", CLERK);
        WorkItem theirs = only(list(), WorkItemType.BILL_AWAITING_APPROVAL);
        assertThat(theirs.actions())
                .filteredOn(action -> action.action().equals("APPROVE"))
                .singleElement()
                .satisfies(approve -> assertThat(approve.allowed()).isTrue());
    }

    @Test
    @DisplayName("the approval item previews the entry the approval would post; none when the approve is blocked")
    void approvalPreview() {
        VendorBill bill = reviewBill(VendorBillStatus.AWAITING_APPROVAL, "100.00", "carol");
        List<WorkItemPostingLine> entry = List.of(
                new WorkItemPostingLine("5100", "Supplies", WorkItemPostingLine.Side.DEBIT, new BigDecimal("100.00")),
                new WorkItemPostingLine(
                        "2000", "Accounts Payable", WorkItemPostingLine.Side.CREDIT, new BigDecimal("100.00")));
        when(approvalPreview.preview(bill)).thenReturn(entry);

        signIn("dave", CLERK);
        assertThat(only(list(), WorkItemType.BILL_AWAITING_APPROVAL).postingPreview())
                .isEqualTo(entry);
    }

    @Test
    @DisplayName("an awaiting-approval bill is hidden from a caller who cannot approve")
    void awaitingApprovalNeedsApprovePermission() {
        reviewBill(VendorBillStatus.AWAITING_APPROVAL, "100.00", "bob");

        signIn("pat", PAYER);
        assertThat(types(list())).doesNotContain(WorkItemType.BILL_AWAITING_APPROVAL);
    }

    // ---- bills: match, pick, wait ----------------------------------------------------------------------------

    @Test
    @DisplayName(
            "match exception, pick a match and waiting on delivery are told apart; each needs the approve permissions")
    void matchItems() {
        VendorBill exception = reviewBill(VendorBillStatus.MATCH_EXCEPTION, "100.00", "bob");
        VendorBill pick = reviewBill(VendorBillStatus.MATCH_EXCEPTION, "100.00", "bob");
        VendorBill pendingPick = reviewBill(VendorBillStatus.PENDING_RECEIPT_MATCH, "100.00", "bob");
        VendorBill waiting = reviewBill(VendorBillStatus.PENDING_RECEIPT_MATCH, "100.00", "bob");
        VendorBillMatchCandidate candidate = new VendorBillMatchCandidate();
        candidate.setVendorBill(pick);
        VendorBillMatchCandidate pendingCandidate = new VendorBillMatchCandidate();
        pendingCandidate.setVendorBill(pendingPick);
        when(candidates.findByVendorBill_VendorBillIdInAndResolvedFalse(any()))
                .thenReturn(List.of(candidate, pendingCandidate));

        signIn("carol", CLERK);
        WorkItemsResponse response = list();

        assertThat(response.items())
                .extracting(WorkItem::itemId)
                .containsExactlyInAnyOrder(
                        "BILL_MATCH_EXCEPTION:" + exception.getVendorBillId(),
                        "BILL_PICK_MATCH:" + pick.getVendorBillId(),
                        "BILL_PICK_MATCH:" + pendingPick.getVendorBillId(),
                        "BILL_WAITING_ON_DELIVERY:" + waiting.getVendorBillId());
        assertThat(only(response, WorkItemType.BILL_WAITING_ON_DELIVERY).actions())
                .extracting(WorkItemAction::action)
                .containsExactly("SUBMIT_FOR_APPROVAL");
        assertThat(response.items().stream()
                        .filter(item -> item.type() == WorkItemType.BILL_PICK_MATCH)
                        .flatMap(item -> item.actions().stream()))
                .extracting(WorkItemAction::action)
                .containsOnly("SELECT_CANDIDATE");

        signIn("pat", PAYER);
        assertThat(list().items()).isEmpty();
    }

    @Test
    @DisplayName("a match exception over the caller's tier lists ACCEPT_EXCEPTION blocked AP_APPROVAL_LIMIT_EXCEEDED")
    void acceptExceptionOverTheTier() {
        reviewBill(VendorBillStatus.MATCH_EXCEPTION, "3000.00", "bob");

        signIn("carol", CLERK);
        assertThat(only(list(), WorkItemType.BILL_MATCH_EXCEPTION).actions())
                .filteredOn(action -> action.action().equals("ACCEPT_EXCEPTION"))
                .singleElement()
                .satisfies(accept -> {
                    assertThat(accept.allowed()).isFalse();
                    assertThat(accept.blockedReason()).isEqualTo("AP_APPROVAL_LIMIT_EXCEEDED");
                });

        signIn("alice", APPROVER);
        assertThat(only(list(), WorkItemType.BILL_MATCH_EXCEPTION).actions())
                .filteredOn(action -> action.action().equals("ACCEPT_EXCEPTION"))
                .singleElement()
                .satisfies(accept -> assertThat(accept.allowed()).isTrue());
    }

    @Test
    @DisplayName("a goods-receipt bill still waiting for its invoice offers no SUBMIT_FOR_APPROVAL")
    void waitingForTheInvoice() {
        VendorBill bill = reviewBill(VendorBillStatus.PENDING_RECEIPT_MATCH, "100.00", "bob");
        bill.setOriginEventType(VendorBillReader.ORIGIN_GOODS_RECEIVED);

        signIn("carol", CLERK);
        WorkItem item = only(list(), WorkItemType.BILL_WAITING_ON_DELIVERY);

        assertThat(item.actions()).isEmpty();
        assertThat(item.reasonCode()).isEqualTo(VendorBillReader.REASON_INVOICE_NOT_MATCHED);
    }

    @Test
    @DisplayName("currency-held, rejected, voided and paid bills produce nothing")
    void otherStatusesProduceNothing() {
        reviewBill(VendorBillStatus.CURRENCY_HOLD, "100.00", "bob");
        reviewBill(VendorBillStatus.REJECTED, "100.00", "bob");
        reviewBill(VendorBillStatus.VOIDED, "100.00", "bob");
        reviewBill(VendorBillStatus.PAID, "100.00", "bob");

        signIn("alice", APPROVER);
        assertThat(list().items()).isEmpty();
    }

    // ---- bills due soon ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("AC9: BILLS_DUE_SOON lists overdue and next-7-day approved bills only with accounting:ap:pay")
    void dueSoonNeedsPay() {
        OpenBill overdue = approvedBill("OLD-1", TODAY.minusDays(3), "300.00", "someone");
        OpenBill soon = approvedBill("SOON-1", TODAY.plusDays(7), "200.00", "someone");
        approvedBill("LATER-1", TODAY.plusDays(8), "500.00", "someone");
        approvedBill("PAID-1", TODAY.plusDays(1), "0.00", "someone");

        signIn("pat", PAYER);
        WorkItemsResponse response = list();
        assertThat(response.items())
                .extracting(WorkItem::itemId)
                .containsExactly(
                        "BILLS_DUE_SOON:" + overdue.bill().getVendorBillId(),
                        "BILLS_DUE_SOON:" + soon.bill().getVendorBillId());
        assertThat(response.items().getFirst().reasonCode()).isEqualTo("OVERDUE");
        assertThat(response.items().getLast().reasonCode()).isEqualTo("DUE_SOON");
        assertThat(response.items().getFirst().amount()).isEqualByComparingTo("300.00");

        signIn("alice", APPROVER);
        assertThat(types(list())).doesNotContain(WorkItemType.BILLS_DUE_SOON);
    }

    @Test
    @DisplayName("an estimated due date is flagged with its terms source in the references")
    void dueSoonEstimate() {
        VendorBill bill = new VendorBill(UUID.randomUUID());
        bill.setVendorName("Acme Tires");
        bill.setBillNumber("EST-1");
        bill.setStatus(VendorBillStatus.APPROVED);
        approvedBills.add(new OpenBill(
                bill,
                new BigDecimal("150.00"),
                null,
                new EstimatedDueDate(TODAY.plusDays(2), TermsSource.TENANT_DEFAULT)));

        signIn("pat", PAYER);
        WorkItem item = only(list(), WorkItemType.BILLS_DUE_SOON);

        assertThat(item.references()).containsEntry("estimated", "true").containsEntry("termsSource", "TENANT_DEFAULT");
    }

    @Test
    @DisplayName(
            "PAY on a bill the payer approved is blocked AP_PAYMENT_SELF_APPROVED_BILL unless the tenant allows it")
    void payBlockedForTheApprover() {
        approvedBill("MINE-1", TODAY.plusDays(1), "100.00", "pat");
        approvedBill("THEIRS-1", TODAY.plusDays(2), "100.00", "someone");

        signIn("pat", PAYER);
        List<WorkItem> items = list().items();
        assertThat(items.getFirst().actions()).singleElement().satisfies(pay -> {
            assertThat(pay.action()).isEqualTo("PAY");
            assertThat(pay.allowed()).isFalse();
            assertThat(pay.blockedReason()).isEqualTo("AP_PAYMENT_SELF_APPROVED_BILL");
        });
        assertThat(items.getLast().actions())
                .singleElement()
                .satisfies(pay -> assertThat(pay.allowed()).isTrue());

        when(policy.settings()).thenReturn(policySettings(true));
        assertThat(list().items().getFirst().actions())
                .singleElement()
                .satisfies(pay -> assertThat(pay.allowed()).isTrue());
    }

    // ---- payments ---------------------------------------------------------------------------------------------

    private static UnappliedPaymentRow unapplied(String reference, String leftOver, boolean suggested) {
        return UnappliedPaymentRow.builder()
                .paymentId(UUID.randomUUID())
                .customerId(UUID.randomUUID())
                .customerDisplayName("Pat Customer")
                .customerReference(reference)
                .paymentMethod("CARD")
                .receivedAt(Instant.parse("2026-10-12T10:00:00Z"))
                .unappliedAmount(new BigDecimal("120.00"))
                .suggestion(
                        suggested
                                ? PaymentMatchSuggestion.builder()
                                        .leftOver(new BigDecimal(leftOver))
                                        .build()
                                : null)
                .build();
    }

    @Test
    @DisplayName(
            "PAYMENT_TO_MATCH: OVERPAID when the suggestion leaves money over, SUGGESTED_MATCH else; none without a suggestion")
    void paymentsToMatch() {
        UnappliedPaymentRow over = unapplied("REF-1", "5.00", true);
        UnappliedPaymentRow exact = unapplied("REF-2", "0.00", true);
        UnappliedPaymentRow none = unapplied("REF-3", "0.00", false);
        when(receivables.listUnappliedPayments(isNull(), eq(0), anyInt()))
                .thenReturn(UnappliedPaymentsPage.builder()
                        .items(List.of(over, exact, none))
                        .totalPages(1)
                        .build());

        signIn("sam", AccountingPermissions.PAYMENT_APPLY);
        WorkItemsResponse response = list();

        assertThat(response.items()).hasSize(2);
        assertThat(response.items())
                .extracting(WorkItem::reasonCode)
                .containsExactlyInAnyOrder("OVERPAID", "SUGGESTED_MATCH");
        WorkItem overpaid = response.items().stream()
                .filter(item -> item.reasonCode().equals("OVERPAID"))
                .findFirst()
                .orElseThrow();
        assertThat(overpaid.references())
                .containsEntry("customer", "Pat Customer")
                .containsEntry("paymentReference", "REF-1");
        assertThat(overpaid.actions())
                .singleElement()
                .satisfies(match -> assertThat(match.action()).isEqualTo("MATCH"));
        assertThat(overpaid.occurredOn()).isEqualTo(LocalDate.of(2026, 10, 12));

        signIn("pat", PAYER);
        assertThat(list().items()).isEmpty();
    }

    @Test
    @DisplayName("PAYMENT_TO_MATCH reads every page of the worklist")
    void paymentsToMatchPages() {
        UnappliedPaymentRow first = unapplied("REF-1", "0.00", true);
        UnappliedPaymentRow second = unapplied("REF-2", "0.00", true);
        when(receivables.listUnappliedPayments(isNull(), eq(0), anyInt()))
                .thenReturn(UnappliedPaymentsPage.builder()
                        .items(List.of(first))
                        .totalPages(2)
                        .build());
        when(receivables.listUnappliedPayments(isNull(), eq(1), anyInt()))
                .thenReturn(UnappliedPaymentsPage.builder()
                        .items(List.of(second))
                        .totalPages(2)
                        .build());

        signIn("sam", AccountingPermissions.PAYMENT_APPLY);

        assertThat(list().items()).hasSize(2);
    }

    @Test
    @DisplayName(
            "UNPAID_WALK_IN_SALE: one per open invoice whose business day ended, only for the statements permission")
    void unpaidWalkInSales() {
        UUID ended = UUID.randomUUID();
        when(walkInSales.read())
                .thenReturn(UnpaidWalkInSalesResponse.builder()
                        .openInvoices(List.of(
                                WalkInOpenInvoice.builder()
                                        .invoiceId(ended)
                                        .invoiceNumber("INV-9")
                                        .locationCode("MAIN")
                                        .saleDate(LocalDate.of(2026, 10, 15))
                                        .balanceDue(new BigDecimal("86.00"))
                                        .businessDayEnded(true)
                                        .build(),
                                WalkInOpenInvoice.builder()
                                        .invoiceId(UUID.randomUUID())
                                        .invoiceNumber("INV-10")
                                        .saleDate(TODAY)
                                        .balanceDue(new BigDecimal("20.00"))
                                        .businessDayEnded(false)
                                        .build()))
                        .build());

        signIn("rae", AccountingPermissions.REPORTING_VIEW_FINANCIAL_STATEMENTS);
        WorkItem item = only(list(), WorkItemType.UNPAID_WALK_IN_SALE);
        assertThat(item.itemId()).isEqualTo("UNPAID_WALK_IN_SALE:" + ended);
        assertThat(item.area()).isEqualTo(WorkItemArea.PAYMENTS);
        assertThat(item.amount()).isEqualByComparingTo("86.00");
        assertThat(item.actions()).isEmpty();
        assertThat(item.references()).containsEntry("invoiceNumber", "INV-9").containsEntry("location", "MAIN");

        signIn("sam", AccountingPermissions.PAYMENT_APPLY);
        assertThat(types(list())).doesNotContain(WorkItemType.UNPAID_WALK_IN_SALE);
    }

    private APPayment failedPayment(LocalDate date) {
        APPayment payment = new APPayment();
        payment.setPaymentId(UUID.randomUUID());
        payment.setPaymentRef("PAY-77");
        payment.setVendorId(UUID.randomUUID());
        payment.setVendorName("Acme Tires");
        payment.setGrossAmount(new BigDecimal("640.00"));
        payment.setFeeAmount(BigDecimal.ZERO);
        payment.setStatus(APPaymentStatus.GL_POST_FAILED);
        payment.setPaymentMethod(PaymentMethod.ACH);
        payment.setPaymentDate(date);
        payment.setBankAccountId(UUID.randomUUID());
        payment.setGlPostError("PERIOD_CLOSED");
        when(apPayments.findByStatusOrderByPaymentDateAscPaymentIdAsc(APPaymentStatus.GL_POST_FAILED))
                .thenReturn(List.of(payment));
        when(apPayments.findAllById(any())).thenReturn(List.of(payment));
        return payment;
    }

    @Test
    @DisplayName("AP_PAYMENT_NOT_POSTED: RETRY blocked PERIOD_CLOSED without period:override, allowed with it")
    void notPostedPeriodClosed() {
        APPayment payment = failedPayment(LocalDate.of(2026, 9, 28));
        when(periodGate.isPostingBlocked(payment.getPaymentDate())).thenReturn(true);
        when(periodGate.isHardLocked(payment.getPaymentDate())).thenReturn(false);

        signIn("jo", AccountingPermissions.JE_POST);
        WorkItem blocked = only(list(), WorkItemType.AP_PAYMENT_NOT_POSTED);
        assertThat(blocked.actions()).singleElement().satisfies(retry -> {
            assertThat(retry.action()).isEqualTo("RETRY");
            assertThat(retry.allowed()).isFalse();
            assertThat(retry.blockedReason()).isEqualTo("PERIOD_CLOSED");
        });
        assertThat(blocked.reasonCode()).isEqualTo("PERIOD_CLOSED");
        assertThat(blocked.occurredOn()).isEqualTo(LocalDate.of(2026, 9, 28));

        signIn("jo", AccountingPermissions.JE_POST, AccountingPermissions.PERIOD_OVERRIDE);
        assertThat(only(list(), WorkItemType.AP_PAYMENT_NOT_POSTED).actions())
                .singleElement()
                .satisfies(retry -> assertThat(retry.allowed()).isTrue());
    }

    @Test
    @DisplayName("AP_PAYMENT_NOT_POSTED: RETRY is allowed in an open period; it previews Dr 2000 / Cr the bank account")
    void notPostedPreview() {
        APPayment payment = failedPayment(TODAY.minusDays(1));
        when(periodGate.isPostingBlocked(any())).thenReturn(false);
        GLAccount payable = account("2000", "Accounts Payable");
        GLAccount bank = account("1000", "Operating Account");
        when(glMappingResolver.findGLAccount(
                        eq(APPaymentPreGatewayChecks.POSTING_CATEGORY),
                        eq(APPaymentPreGatewayChecks.ACCOUNTS_PAYABLE_KEY),
                        any()))
                .thenReturn(Optional.of(payable.getGlAccountId()));
        when(glAccounts.findById(payable.getGlAccountId())).thenReturn(Optional.of(payable));
        when(glAccounts.findById(payment.getBankAccountId())).thenReturn(Optional.of(bank));

        signIn("jo", AccountingPermissions.JE_POST);
        WorkItem item = only(list(), WorkItemType.AP_PAYMENT_NOT_POSTED);

        assertThat(item.actions())
                .singleElement()
                .satisfies(retry -> assertThat(retry.allowed()).isTrue());
        assertThat(item.postingPreview())
                .containsExactly(
                        new WorkItemPostingLine(
                                "2000", "Accounts Payable", WorkItemPostingLine.Side.DEBIT, new BigDecimal("640.00")),
                        new WorkItemPostingLine(
                                "1000",
                                "Operating Account",
                                WorkItemPostingLine.Side.CREDIT,
                                new BigDecimal("640.00")));
        assertThat(item.references()).containsEntry("paymentReference", "PAY-77");
    }

    @Test
    @DisplayName("AP_PAYMENT_NOT_POSTED needs accounting:je:post")
    void notPostedNeedsJePost() {
        failedPayment(TODAY.minusDays(1));

        signIn("sam", AccountingPermissions.PAYMENT_APPLY);

        assertThat(types(list())).doesNotContain(WorkItemType.AP_PAYMENT_NOT_POSTED);
    }

    // ---- cash -------------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "CASH_TO_DEPOSIT groups the balanced sessions by location with the oldest date, bags and the deposit preview")
    void cashToDeposit() {
        UUID main = UUID.randomUUID();
        UUID annex = UUID.randomUUID();
        UndepositedSession first = session(main, "T1", "1197.00", "1240.00", "-43.00");
        UndepositedSession second = session(main, "T2", "100.00", "100.00", "0.00");
        UndepositedSession elsewhere = session(annex, "T9", "50.00", "50.00", "0.00");
        undepositedSession(first, LocalDate.of(2026, 10, 15));
        undepositedSession(second, LocalDate.of(2026, 10, 13));
        undepositedSession(elsewhere, LocalDate.of(2026, 10, 16));
        UndepositedSessionDrop drop = new UndepositedSessionDrop();
        drop.setUndepositedSessionId(first.getUndepositedSessionId());
        drop.setBagNumber("BAG-4471");
        when(drops.findByUndepositedSessionIdInOrderByOccurredAtAscMovementIdAsc(any()))
                .thenReturn(List.of(drop));
        ExtLocationReplica location = new ExtLocationReplica();
        location.setLocationId(main);
        location.setCode("MAIN");
        location.setName("Main Street");
        when(locations.findAllById(any())).thenReturn(List.of(location));
        UndepositedSessionsResponse.PreviewLine bankLine = new UndepositedSessionsResponse.PreviewLine(
                UUID.randomUUID(),
                "1000",
                "Operating Account",
                UndepositedSessionsResponse.Side.DEBIT,
                new BigDecimal("1297.00"));
        when(depositService.undeposited(eq(List.of(first.getSessionId(), second.getSessionId())), isNull()))
                .thenReturn(new UndepositedSessionsResponse(
                        TODAY,
                        "USD",
                        List.of(),
                        new UndepositedSessionsResponse.Selection(
                                List.of(first.getSessionId(), second.getSessionId()),
                                new BigDecimal("1297.00"),
                                new BigDecimal("1340.00"),
                                new BigDecimal("-43.00"),
                                BigDecimal.ZERO,
                                true,
                                List.of(bankLine))));

        signIn("dee", AccountingPermissions.DEPOSIT_CREATE);
        WorkItemsResponse response = list();

        WorkItem mainItem = response.items().stream()
                .filter(item -> item.itemId().equals("CASH_TO_DEPOSIT:" + main))
                .findFirst()
                .orElseThrow();
        assertThat(response.items()).hasSize(2);
        assertThat(mainItem.area()).isEqualTo(WorkItemArea.CASH);
        assertThat(mainItem.amount()).isEqualByComparingTo("1297.00");
        assertThat(mainItem.occurredOn()).isEqualTo(LocalDate.of(2026, 10, 13));
        assertThat(mainItem.references())
                .containsEntry("location", "MAIN")
                .containsEntry("locationName", "Main Street")
                .containsEntry("terminalCount", "2")
                .containsEntry("bagNumbers", "BAG-4471");
        assertThat(mainItem.actions()).singleElement().satisfies(deposit -> {
            assertThat(deposit.action()).isEqualTo("RECORD_DEPOSIT");
            assertThat(deposit.allowed()).isTrue();
        });
        assertThat(mainItem.postingPreview())
                .containsExactly(new WorkItemPostingLine(
                        "1000", "Operating Account", WorkItemPostingLine.Side.DEBIT, new BigDecimal("1297.00")));

        signIn("sam", AccountingPermissions.PAYMENT_APPLY);
        assertThat(list().items()).isEmpty();
    }

    @Test
    @DisplayName("UNDEPOSITED_SESSION_UNBALANCED: only an unbalanced session older than the threshold; no actions")
    void unbalancedSession() {
        UUID main = UUID.randomUUID();
        UndepositedSession agedUnbalanced = session(main, "T1", "100.00", "140.00", "0.00");
        UndepositedSession freshUnbalanced = session(main, "T2", "100.00", "140.00", "0.00");
        UndepositedSession agedBalanced = session(main, "T3", "100.00", "100.00", "0.00");
        undepositedSession(agedUnbalanced, TODAY.minusDays(4));
        undepositedSession(freshUnbalanced, TODAY.minusDays(3));
        undepositedSession(agedBalanced, TODAY.minusDays(4));

        signIn("dee", AccountingPermissions.DEPOSIT_CREATE);
        WorkItemsResponse response = list();

        WorkItem item = only(response, WorkItemType.UNDEPOSITED_SESSION_UNBALANCED);
        assertThat(item.itemId()).isEqualTo("UNDEPOSITED_SESSION_UNBALANCED:" + agedUnbalanced.getSessionId());
        assertThat(item.reasonCode()).isEqualTo("UNBALANCED");
        assertThat(item.actions()).isEmpty();
        assertThat(item.occurredOn()).isEqualTo(TODAY.minusDays(4));
        assertThat(types(response)).containsOnlyOnce(WorkItemType.CASH_TO_DEPOSIT);
    }

    @Test
    @DisplayName(
            "a card-only session is written settled, never undeposited, so the source is only asked for UNDEPOSITED")
    void cardOnlySessionsNeverAppear() {
        com.positivity.accounting.internal.repository.UndepositedSessionRepository repository = mock();
        UndepositedSession cardOnly = session(UUID.randomUUID(), "T1", "0.00", "0.00", "0.00");
        cardOnly.setStatus(UndepositedSessionStatus.NOTHING_TO_DEPOSIT);
        when(repository.findByStatusOrderByClosedAtAscSessionIdAsc(UndepositedSessionStatus.UNDEPOSITED))
                .thenReturn(List.of());
        when(repository.findByStatusOrderByClosedAtAscSessionIdAsc(UndepositedSessionStatus.NOTHING_TO_DEPOSIT))
                .thenReturn(List.of(cardOnly));
        CashAttentionSources real = new CashAttentionSources(
                repository, mock(), zoneResolver, new com.positivity.accounting.internal.config.LedgerCurrency("USD"));

        assertThat(real.undepositedSessions()).isEmpty();
        assertThat(real.agedUndepositedSessions(TODAY, 3)).isEmpty();
    }

    // ---- bank -------------------------------------------------------------------------------------------------

    private BankReconciliation reconciliation(ReconciliationStatus status, String submittedBy) {
        BankReconciliation reconciliation = new BankReconciliation();
        reconciliation.setReconciliationId(UUID.randomUUID());
        reconciliation.setStatementId(UUID.randomUUID());
        reconciliation.setAccountCode("1000");
        reconciliation.setAccountName("Operating Account");
        reconciliation.setStatementEndDate(LocalDate.of(2026, 9, 30));
        reconciliation.setStatementClosingBalance(new BigDecimal("12400.00"));
        reconciliation.setStatus(status);
        reconciliation.setSubmittedBy(submittedBy);
        reconciliation.setSubmittedAt(Instant.parse("2026-10-14T10:00:00Z"));
        when(reconciliations.findByStatus(eq(status), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(reconciliation)));
        return reconciliation;
    }

    private static BankTransaction line(UUID statementId, String signed, LocalDate date, String description) {
        BankTransaction row = new BankTransaction();
        row.setBankTransactionId(UUID.randomUUID());
        row.setStatementId(statementId);
        row.setGlAccountId(UUID.randomUUID());
        row.setSignedAmount(new BigDecimal(signed));
        row.setTransactionDate(date);
        row.setDescription(description);
        row.setStatus(BankTransactionStatus.UNMATCHED);
        return row;
    }

    @Test
    @DisplayName(
            "BANK_LINE_UNMATCHED: CARD_DEPOSIT_CANDIDATE for a credit with a ledger candidate, NOT_IN_BOOKS otherwise")
    void unmatchedLines() {
        BankReconciliation reconciliation = reconciliation(ReconciliationStatus.IN_PROGRESS, null);
        BankTransaction withCandidate =
                line(reconciliation.getStatementId(), "1197.00", LocalDate.of(2026, 9, 12), "VISA SETTLE");
        BankTransaction credit = line(reconciliation.getStatementId(), "20.00", LocalDate.of(2026, 9, 14), "INTEREST");
        BankTransaction debit = line(reconciliation.getStatementId(), "-35.00", LocalDate.of(2026, 9, 15), "BANK FEE");
        when(transactions.findByStatementIdInAndStatusOrderByTransactionDateAscBankTransactionIdAsc(
                        any(), eq(BankTransactionStatus.UNMATCHED)))
                .thenReturn(List.of(withCandidate, credit, debit));
        when(candidateFinder.rankedForEach(eq(reconciliation), anyList()))
                .thenReturn(Map.of(
                        withCandidate.getBankTransactionId(),
                        List.of(mock(CandidateFinder.ScoredLine.class)),
                        credit.getBankTransactionId(),
                        List.of(),
                        debit.getBankTransactionId(),
                        List.of(mock(CandidateFinder.ScoredLine.class))));

        signIn("ray", AccountingPermissions.RECONCILIATION_ADJUST);
        WorkItemsResponse response = list();

        assertThat(response.items()).hasSize(3);
        assertThat(response.items())
                .extracting(WorkItem::reasonCode)
                .containsExactly("CARD_DEPOSIT_CANDIDATE", "NOT_IN_BOOKS", "NOT_IN_BOOKS");
        WorkItem first = response.items().getFirst();
        assertThat(first.area()).isEqualTo(WorkItemArea.BANK);
        assertThat(first.occurredOn()).isEqualTo(LocalDate.of(2026, 9, 12));
        assertThat(first.actions()).isEmpty();
        assertThat(first.references())
                .containsEntry("statementMonth", "2026-09")
                .containsEntry("account", "1000 Operating Account");

        signIn("sam", AccountingPermissions.PAYMENT_APPLY);
        assertThat(list().items()).isEmpty();
    }

    @Test
    @DisplayName(
            "AC10: the preparer sees APPROVE allowed:false with RECONCILIATION_SELF_APPROVAL; another approver may approve")
    void preparerCannotApprove() {
        BankReconciliation reconciliation = reconciliation(ReconciliationStatus.SUBMITTED, "prep");

        signIn("prep", AccountingPermissions.RECONCILIATION_APPROVE);
        WorkItem mine = only(list(), WorkItemType.RECONCILIATION_AWAITING_APPROVAL);
        assertThat(mine.actions()).singleElement().satisfies(approve -> {
            assertThat(approve.action()).isEqualTo("APPROVE");
            assertThat(approve.allowed()).isFalse();
            assertThat(approve.blockedReason()).isEqualTo("RECONCILIATION_SELF_APPROVAL");
        });
        assertThat(mine.itemId()).isEqualTo("RECONCILIATION_AWAITING_APPROVAL:" + reconciliation.getReconciliationId());
        assertThat(mine.occurredOn()).isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(mine.references()).containsEntry("statementMonth", "2026-09");

        signIn("boss", AccountingPermissions.RECONCILIATION_APPROVE);
        assertThat(only(list(), WorkItemType.RECONCILIATION_AWAITING_APPROVAL).actions())
                .singleElement()
                .satisfies(approve -> assertThat(approve.allowed()).isTrue());
    }

    @Test
    @DisplayName("AC10: the preparer may approve when the tenant allows self-approval")
    void selfApprovalAllowed() {
        reconciliation(ReconciliationStatus.SUBMITTED, "prep");
        when(bankRecPolicy.allowSelfApproval()).thenReturn(true);

        signIn("prep", AccountingPermissions.RECONCILIATION_APPROVE);

        assertThat(only(list(), WorkItemType.RECONCILIATION_AWAITING_APPROVAL).actions())
                .singleElement()
                .satisfies(approve -> assertThat(approve.allowed()).isTrue());
    }

    @Test
    @DisplayName("a submitted reconciliation is hidden from a caller without accounting:reconciliation:approve")
    void reconciliationNeedsApprove() {
        reconciliation(ReconciliationStatus.SUBMITTED, "prep");

        signIn("ray", AccountingPermissions.RECONCILIATION_ADJUST);

        assertThat(types(list())).doesNotContain(WorkItemType.RECONCILIATION_AWAITING_APPROVAL);
    }

    // ---- counts, references, gauge ---------------------------------------------------------------------------

    private void aMixedList() {
        reviewBill(VendorBillStatus.AWAITING_APPROVAL, "100.00", "bob");
        reviewBill(VendorBillStatus.PENDING_RECEIPT_MATCH, "100.00", "bob");
        approvedBill("SOON-1", TODAY.plusDays(2), "200.00", "someone");
        when(receivables.listUnappliedPayments(isNull(), eq(0), anyInt()))
                .thenReturn(UnappliedPaymentsPage.builder()
                        .items(List.of(unapplied("REF-1", "0.00", true)))
                        .totalPages(1)
                        .build());
        reconciliation(ReconciliationStatus.SUBMITTED, "prep");
        undepositedSession(session(UUID.randomUUID(), "T1", "50.00", "50.00", "0.00"), TODAY.minusDays(1));
    }

    private static final String[] EVERYTHING = {
        AccountingPermissions.AP_APPROVE,
        AccountingPermissions.AP_APPROVE_OVER_LIMIT,
        AccountingPermissions.AP_PAY,
        AccountingPermissions.PAYMENT_APPLY,
        AccountingPermissions.DEPOSIT_CREATE,
        AccountingPermissions.RECONCILIATION_APPROVE
    };

    @Test
    @DisplayName(
            "countsByArea counts every area the caller may see, before the area filter, and the filter narrows the list")
    void countsByArea() {
        aMixedList();

        signIn("alice", EVERYTHING);
        WorkItemsResponse all = list();
        WorkItemsResponse bills = service.list(WorkItemArea.BILLS, null, null);

        assertThat(all.countsByArea())
                .containsEntry(WorkItemArea.BILLS, 3)
                .containsEntry(WorkItemArea.PAYMENTS, 1)
                .containsEntry(WorkItemArea.BANK, 1)
                .containsEntry(WorkItemArea.CASH, 1);
        assertThat(all.items()).hasSize(6);
        assertThat(bills.items())
                .hasSize(3)
                .allSatisfy(item -> assertThat(item.area()).isEqualTo(WorkItemArea.BILLS));
        assertThat(bills.countsByArea()).isEqualTo(all.countsByArea());
        assertThat(all.asOf()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("countsByArea follows the caller's permissions: a payer counts only bills, zero elsewhere")
    void countsFollowPermissions() {
        aMixedList();

        signIn("pat", PAYER);
        WorkItemsResponse response = list();

        assertThat(response.countsByArea())
                .containsEntry(WorkItemArea.BILLS, 1)
                .containsEntry(WorkItemArea.PAYMENTS, 0)
                .containsEntry(WorkItemArea.BANK, 0)
                .containsEntry(WorkItemArea.CASH, 0);
    }

    @Test
    @DisplayName("no reference is an identifier as text (ADR-0064); identifiers are in routing")
    void noIdentifierTextInReferences() {
        aMixedList();
        failedPayment(TODAY.minusDays(1));

        signIn("alice", concat(EVERYTHING, AccountingPermissions.JE_POST));
        WorkItemsResponse response = list();

        assertThat(response.items()).hasSizeGreaterThan(5);
        for (WorkItem item : response.items()) {
            assertThat(item.references().values())
                    .as("references of %s", item.itemId())
                    .noneMatch(value -> UUID_TEXT.matcher(value).find());
            assertThat(item.routing()).as("routing of %s", item.itemId()).isNotEmpty();
        }
    }

    @Test
    @DisplayName("the gauge accounting.work_items.count{type} holds the count the read showed its caller")
    void gaugePerType() {
        aMixedList();

        signIn("alice", EVERYTHING);
        list();

        assertThat(meters.get(WorkItemServiceImpl.GAUGE)
                        .tag("tenant", "unbound")
                        .tag("type", "BILL_AWAITING_APPROVAL")
                        .gauge()
                        .value())
                .isEqualTo(1.0);
        assertThat(meters.get(WorkItemServiceImpl.GAUGE)
                        .tag("type", "BANK_LINE_UNMATCHED")
                        .gauge()
                        .value())
                .isEqualTo(0.0);

        signIn("pat", PAYER);
        list();
        assertThat(meters.get(WorkItemServiceImpl.GAUGE)
                        .tag("type", "BILL_AWAITING_APPROVAL")
                        .gauge()
                        .value())
                .isEqualTo(0.0);
    }

    private static String[] concat(String[] first, String... more) {
        return Stream.concat(Stream.of(first), Stream.of(more)).toArray(String[]::new);
    }

    // ---- cursor and limit -------------------------------------------------------------------------------------

    private void fiveDueBills() {
        for (int day = 1; day <= 5; day++) {
            approvedBill("DUE-" + day, TODAY.plusDays(day % 7), "10.00", "someone");
        }
    }

    @Test
    @DisplayName("cursor round trip: oldest first, three pages of two, two, one; nextCursor null on the last")
    void cursorRoundTrip() {
        fiveDueBills();
        signIn("pat", PAYER);

        WorkItemsResponse first = service.list(null, null, 2);
        WorkItemsResponse second = service.list(null, first.nextCursor(), 2);
        WorkItemsResponse third = service.list(null, second.nextCursor(), 2);

        assertThat(first.items()).hasSize(2);
        assertThat(second.items()).hasSize(2);
        assertThat(third.items()).hasSize(1);
        assertThat(third.nextCursor()).isNull();
        List<WorkItem> all = new ArrayList<>(first.items());
        all.addAll(second.items());
        all.addAll(third.items());
        assertThat(all).extracting(WorkItem::occurredOn).isSorted();
        assertThat(all).extracting(WorkItem::itemId).doesNotHaveDuplicates().hasSize(5);
        assertThat(first.nextCursor()).isNotBlank().doesNotContain("=", "+", "/");
    }

    @Test
    @DisplayName("a page resumes after the cursor's key even when that item has vanished")
    void vanishedCursorItem() {
        fiveDueBills();
        signIn("pat", PAYER);
        WorkItemsResponse first = service.list(null, null, 2);
        String lastServed = first.items().getLast().itemId();
        approvedBills.removeIf(
                open -> lastServed.endsWith(open.bill().getVendorBillId().toString()));

        WorkItemsResponse second = service.list(null, first.nextCursor(), 2);

        assertThat(second.items()).hasSize(2);
        assertThat(second.items()).extracting(WorkItem::itemId).doesNotContain(lastServed);
        assertThat(second.items().getFirst().occurredOn())
                .isAfterOrEqualTo(first.items().getLast().occurredOn());
    }

    @Test
    @DisplayName("a cursor that does not decode to area|date|item is WORK_ITEMS_CURSOR_INVALID")
    void malformedCursor() {
        fiveDueBills();
        signIn("pat", PAYER);
        java.util.function.Function<String, String> token = text -> java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        for (String bad : List.of(
                "not a cursor!",
                token.apply("ALL|2026-10-17"),
                token.apply("ALL|not-a-date|BILLS_DUE_SOON:x"),
                token.apply("ALL|2026-10-17|"),
                token.apply("garbage"))) {
            assertThatThrownBy(() -> service.list(null, bad, 2))
                    .as(bad)
                    .isInstanceOf(WorkItemsCursorInvalidException.class);
        }
        assertThat(service.list(null, token.apply("ALL|2026-10-17|BILLS_DUE_SOON:0"), 2)
                        .items())
                .isNotEmpty();
    }

    @Test
    @DisplayName("a cursor issued for one area is refused for another, and for no area")
    void wrongAreaCursor() {
        fiveDueBills();
        signIn("pat", PAYER);
        String cursor = service.list(WorkItemArea.BILLS, null, 2).nextCursor();

        assertThatThrownBy(() -> service.list(WorkItemArea.PAYMENTS, cursor, 2))
                .isInstanceOf(WorkItemsCursorInvalidException.class);
        assertThatThrownBy(() -> service.list(null, cursor, 2)).isInstanceOf(WorkItemsCursorInvalidException.class);
        assertThat(service.list(WorkItemArea.BILLS, cursor, 2).items()).isNotEmpty();
    }

    @Test
    @DisplayName("limit defaults to 50; below 1 or above 200 is VALIDATION_ERROR on the limit field")
    void limits() {
        signIn("pat", PAYER);

        assertThat(service.list(null, null, 200).items()).isEmpty();
        assertThat(service.list(null, null, 1).items()).isEmpty();
        assertThatThrownBy(() -> service.list(null, null, 201))
                .isInstanceOfSatisfying(
                        InvalidRequestParameterException.class,
                        e -> assertThat(e.getField()).isEqualTo("limit"));
        assertThatThrownBy(() -> service.list(null, null, 0)).isInstanceOf(InvalidRequestParameterException.class);
        assertThatThrownBy(() -> service.list(null, null, -5)).isInstanceOf(InvalidRequestParameterException.class);
    }

    @Test
    @DisplayName("without an item permission nothing is listed")
    void nothingWithoutPermissions() {
        aMixedList();

        signIn("nobody", AccountingPermissions.COA_VIEW);
        WorkItemsResponse response = list();

        assertThat(response.items()).isEmpty();
        assertThat(response.countsByArea().values()).containsOnly(0);
    }

    // ---- review findings of #2752 -----------------------------------------------------------------------------

    @Test
    @DisplayName("PAY is blocked VENDOR_ON_AP_HOLD for a vendor on AP hold, before the self-approval rule")
    void payBlockedByApHold() {
        OpenBill held = approvedBill("HELD-1", TODAY.plusDays(1), "100.00", "pat");
        approvedBill("FREE-1", TODAY.plusDays(2), "100.00", "someone");
        when(vendorCopies.heldVendors(any())).thenReturn(Map.of(held.bill().getVendorId(), "confidential"));

        signIn("pat", PAYER);
        List<WorkItem> items = list().items();

        assertThat(items.getFirst().actions()).singleElement().satisfies(pay -> {
            assertThat(pay.allowed()).isFalse();
            assertThat(pay.blockedReason()).isEqualTo("VENDOR_ON_AP_HOLD");
        });
        assertThat(items.getLast().actions())
                .singleElement()
                .satisfies(pay -> assertThat(pay.allowed()).isTrue());
    }

    @Test
    @DisplayName("a hard-locked payment date blocks RETRY with PERIOD_HARD_LOCKED unless the caller may override")
    void hardLockedRetry() {
        APPayment payment = failedPayment(LocalDate.of(2026, 8, 20));
        when(periodGate.isHardLocked(payment.getPaymentDate())).thenReturn(true);
        when(periodGate.isPostingBlocked(payment.getPaymentDate())).thenReturn(true);

        signIn("jo", AccountingPermissions.JE_POST);
        assertThat(only(list(), WorkItemType.AP_PAYMENT_NOT_POSTED).actions())
                .singleElement()
                .satisfies(retry -> {
                    assertThat(retry.allowed()).isFalse();
                    assertThat(retry.blockedReason()).isEqualTo("PERIOD_HARD_LOCKED");
                });

        signIn("jo", AccountingPermissions.JE_POST, AccountingPermissions.PERIOD_OVERRIDE);
        assertThat(only(list(), WorkItemType.AP_PAYMENT_NOT_POSTED).actions())
                .singleElement()
                .satisfies(retry -> assertThat(retry.allowed()).isTrue());
    }

    @Test
    @DisplayName(
            "the retry preview of a hard-locked payment maps its accounts on today, the date the deferred entry posts")
    void hardLockedPreviewDate() {
        APPayment payment = failedPayment(LocalDate.of(2026, 8, 20));
        when(periodGate.isHardLocked(payment.getPaymentDate())).thenReturn(true);
        GLAccount payable = account("2000", "Accounts Payable");
        GLAccount bank = account("1000", "Operating Account");
        when(glMappingResolver.findGLAccount(any(), any(), eq(TODAY.atStartOfDay())))
                .thenReturn(Optional.of(payable.getGlAccountId()));
        when(glAccounts.findById(payable.getGlAccountId())).thenReturn(Optional.of(payable));
        when(glAccounts.findById(payment.getBankAccountId())).thenReturn(Optional.of(bank));

        signIn("jo", AccountingPermissions.JE_POST, AccountingPermissions.PERIOD_OVERRIDE);

        assertThat(only(list(), WorkItemType.AP_PAYMENT_NOT_POSTED).postingPreview())
                .hasSize(2);
    }

    @Test
    @DisplayName("only the returned page is completed: one preview per page bill, one creator-first read")
    void onlyThePageIsCompleted() {
        for (int i = 0; i < 6; i++) {
            VendorBill bill = reviewBill(VendorBillStatus.AWAITING_APPROVAL, "100.00", "bob");
            bill.setSubmittedAt(Instant.parse("2026-10-06T10:00:00Z").plusSeconds(i * 86400L));
        }

        signIn("alice", APPROVER);
        WorkItemsResponse page = service.list(null, null, 2);

        assertThat(page.items()).hasSize(2);
        assertThat(page.countsByArea()).containsEntry(WorkItemArea.BILLS, 6);
        org.mockito.Mockito.verify(approvalPreview, org.mockito.Mockito.times(2)).preview(any());
        org.mockito.Mockito.verify(vendorCopies, org.mockito.Mockito.times(1)).creatorsFirstBillVendors(any(), any());
    }

    @Test
    @DisplayName("the match evidence and lines of a page of goods-receipt bills are read once each, not per bill")
    void evidenceIsBatched() {
        for (int i = 0; i < 6; i++) {
            VendorBill bill = reviewBill(VendorBillStatus.PENDING_RECEIPT_MATCH, "100.00", "bob");
            bill.setOriginEventType(VendorBillReader.ORIGIN_GOODS_RECEIVED);
        }

        signIn("carol", CLERK);
        WorkItemsResponse page = service.list(null, null, 6);

        assertThat(page.items()).hasSize(6);
        org.mockito.Mockito.verify(evidence, org.mockito.Mockito.times(1)).findByVendorBillIdIn(any());
        org.mockito.Mockito.verify(billLines, org.mockito.Mockito.times(1)).findByVendorBill_VendorBillIdIn(any());
        org.mockito.Mockito.verify(evidence, org.mockito.Mockito.never())
                .findFirstByVendorBillIdOrderByRecordedAtDescMatchEvidenceIdDesc(any());
    }

    @Test
    @DisplayName("an invoice already matched to a goods-receipt bill lets it be sent for approval")
    void matchedReceiptBillCanBeSent() {
        VendorBill bill = reviewBill(VendorBillStatus.PENDING_RECEIPT_MATCH, "100.00", "bob");
        bill.setOriginEventType(VendorBillReader.ORIGIN_GOODS_RECEIVED);
        com.positivity.accounting.internal.entity.VendorBillMatchEvidence row =
                mock(com.positivity.accounting.internal.entity.VendorBillMatchEvidence.class);
        when(row.getVendorBillId()).thenReturn(bill.getVendorBillId());
        when(row.getSource())
                .thenReturn(
                        com.positivity.accounting.internal.entity.VendorBillMatchEvidence.Source.CANDIDATE_SELECTION);
        when(row.getRecordedAt()).thenReturn(NOW);
        when(row.getMatchEvidenceId()).thenReturn(UUID.randomUUID());
        when(evidence.findByVendorBillIdIn(any())).thenReturn(List.of(row));
        com.positivity.accounting.internal.entity.VendorBillLine line =
                mock(com.positivity.accounting.internal.entity.VendorBillLine.class);
        when(line.getVendorBill()).thenReturn(bill);
        when(line.getLineNumber()).thenReturn(1);
        when(line.getBilledQuantity()).thenReturn(BigDecimal.ONE);
        when(line.getBilledUnitPrice()).thenReturn(new BigDecimal("100.00"));
        when(billLines.findByVendorBill_VendorBillIdIn(any())).thenReturn(List.of(line));

        signIn("carol", CLERK);
        WorkItem item = only(list(), WorkItemType.BILL_WAITING_ON_DELIVERY);

        assertThat(item.actions()).extracting(WorkItemAction::action).containsExactly("SUBMIT_FOR_APPROVAL");
    }
}
