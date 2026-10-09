package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.VendorBillReview;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.entity.VendorBillLine;
import com.positivity.accounting.internal.entity.VendorBillMatchEvidence;
import com.positivity.accounting.internal.enums.MatchConfidence;
import com.positivity.accounting.internal.enums.VendorBillAction;
import com.positivity.accounting.internal.enums.VendorBillCheckOutcome;
import com.positivity.accounting.internal.enums.VendorBillStage;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.repository.APPaymentAllocationRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.accounting.internal.repository.VendorBillGlPostingRepository;
import com.positivity.accounting.internal.repository.VendorBillLineRepository;
import com.positivity.accounting.internal.repository.VendorBillMatchCandidateRepository;
import com.positivity.accounting.internal.repository.VendorBillMatchEvidenceRepository;
import com.positivity.accounting.internal.repository.VendorBillReissueRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import com.positivity.accounting.internal.repository.VendorBillTaxRecoveryRepository;
import com.positivity.accounting.internal.repository.VendorBillTaxRepository;
import com.positivity.security.common.GatewaySecurityConstants;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The review read's {@code availableActions} per role and status (P5, AC12, AC9) and its {@code checks} (§5.2), from
 * the grants of #2509: ACCOUNTING_CLERK holds ap:approve and ap:reject, CONTROLLER also ap:approve_over_limit.
 */
@DisplayName("VendorBillReader: availableActions per role and the checks (#2509, P5)")
class VendorBillReaderTest {

    private static final String[] CLERK = {"accounting:ap:view", "accounting:ap:approve", "accounting:ap:reject"};
    private static final String[] CONTROLLER = {
        "accounting:ap:view",
        "accounting:ap:pay",
        "accounting:ap:approve",
        "accounting:ap:reject",
        "accounting:ap:approve_over_limit"
    };
    private static final String[] PAYER = {"accounting:ap:view", "accounting:ap:pay"};

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(String... authorities) {
        UsernamePasswordAuthenticationToken caller = new UsernamePasswordAuthenticationToken(
                "someone",
                "n/a",
                Stream.of(authorities).map(SimpleGrantedAuthority::new).toList());
        caller.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, "someone"));
        SecurityContextHolder.getContext().setAuthentication(caller);
    }

    private static final VendorBillReview.Channel EDI = VendorBillReview.Channel.SUPPLIER_CONNECTION;
    private static final VendorBillReview.Channel RECEIPT = VendorBillReview.Channel.GOODS_RECEIPT;

    /** What the tier blocks for a clerk on an over-limit bill (S13): approve, accept and the void of an approved bill. */
    private static final VendorBillReader.Blocks OVER_LIMIT_BLOCKS =
            new VendorBillReader.Blocks(VendorBillReader.BLOCKED_LIMIT, VendorBillReader.BLOCKED_LIMIT, false);

    private static List<VendorBillAction> actions(VendorBillStatus status, boolean candidates, boolean allocated) {
        return actions(status, EDI, candidates, false, allocated, true);
    }

    private static List<VendorBillAction> actions(
            VendorBillStatus status,
            VendorBillReview.Channel channel,
            boolean candidates,
            boolean awaitingInvoice,
            boolean allocated,
            boolean posted) {
        return VendorBillReader.availableActions(
                        status, channel, candidates, awaitingInvoice, allocated, posted, VendorBillReader.Blocks.NONE)
                .stream()
                .map(VendorBillReview.AvailableAction::action)
                .toList();
    }

    @Test
    @DisplayName(
            "AC11 (S13): a clerk on an over-limit bill sees approve, accept and the void of an approved bill listed"
                + " but not allowed, with blockedReason AP_APPROVAL_LIMIT_EXCEEDED; the due date is listed in review")
    void clerkOverTheLimit() {
        signIn(CLERK);
        assertThat(VendorBillReader.availableActions(
                        VendorBillStatus.PENDING_RECEIPT_MATCH, EDI, false, false, false, false, OVER_LIMIT_BLOCKS))
                .extracting(VendorBillReview.AvailableAction::action, VendorBillReview.AvailableAction::allowed)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(VendorBillAction.SUBMIT_FOR_APPROVAL, true),
                        org.assertj.core.groups.Tuple.tuple(VendorBillAction.SET_DUE_DATE, true));
        assertThat(VendorBillReader.availableActions(
                        VendorBillStatus.MATCH_EXCEPTION, EDI, false, false, false, false, OVER_LIMIT_BLOCKS))
                .extracting(VendorBillReview.AvailableAction::action, VendorBillReview.AvailableAction::blockedReason)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(VendorBillAction.SUBMIT_FOR_APPROVAL, null),
                        org.assertj.core.groups.Tuple.tuple(
                                VendorBillAction.ACCEPT_EXCEPTION, "AP_APPROVAL_LIMIT_EXCEEDED"),
                        org.assertj.core.groups.Tuple.tuple(VendorBillAction.CORRECT_EXCEPTION, null),
                        org.assertj.core.groups.Tuple.tuple(VendorBillAction.VOID_EXCEPTION, null),
                        org.assertj.core.groups.Tuple.tuple(VendorBillAction.SET_DUE_DATE, null));
        assertThat(VendorBillReader.availableActions(
                        VendorBillStatus.AWAITING_APPROVAL, EDI, false, false, false, false, OVER_LIMIT_BLOCKS))
                .extracting(
                        VendorBillReview.AvailableAction::action,
                        VendorBillReview.AvailableAction::allowed,
                        VendorBillReview.AvailableAction::blockedReason)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                VendorBillAction.APPROVE, false, "AP_APPROVAL_LIMIT_EXCEEDED"),
                        org.assertj.core.groups.Tuple.tuple(VendorBillAction.REJECT, true, null),
                        org.assertj.core.groups.Tuple.tuple(VendorBillAction.SET_DUE_DATE, true, null));
        assertThat(VendorBillReader.availableActions(
                        VendorBillStatus.APPROVED, EDI, false, false, false, true, OVER_LIMIT_BLOCKS))
                .as("AC16: VOID_APPROVED listed, blocked by the tier")
                .extracting(
                        VendorBillReview.AvailableAction::action,
                        VendorBillReview.AvailableAction::allowed,
                        VendorBillReview.AvailableAction::blockedReason)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        VendorBillAction.VOID_APPROVED, false, "AP_APPROVAL_LIMIT_EXCEEDED"));
    }

    @Test
    @DisplayName("AB-ambig: while an ambiguous match's candidates are open the bill is picked first: no send or"
            + " accept, the selection, correct and void stay")
    void openCandidatesLimitTheActions() {
        signIn(CONTROLLER);
        assertThat(actions(VendorBillStatus.MATCH_EXCEPTION, RECEIPT, true, true, false, false))
                .containsExactly(
                        VendorBillAction.CORRECT_EXCEPTION,
                        VendorBillAction.VOID_EXCEPTION,
                        VendorBillAction.SELECT_CANDIDATE,
                        VendorBillAction.SET_DUE_DATE);
        assertThat(actions(VendorBillStatus.PENDING_RECEIPT_MATCH, RECEIPT, true, true, false, false))
                .containsExactly(
                        VendorBillAction.SELECT_CANDIDATE,
                        VendorBillAction.VOID_UNMATCHED,
                        VendorBillAction.SET_DUE_DATE);
    }

    @Test
    @DisplayName("AW45: a goods-receipt bill without its invoice is never sent, approved or accepted; it can be voided"
            + " from PENDING_RECEIPT_MATCH with ap:reject")
    void goodsReceiptAwaitingItsInvoice() {
        signIn(CONTROLLER);
        assertThat(actions(VendorBillStatus.PENDING_RECEIPT_MATCH, RECEIPT, false, true, false, false))
                .containsExactly(VendorBillAction.VOID_UNMATCHED, VendorBillAction.SET_DUE_DATE);
        assertThat(actions(VendorBillStatus.MATCH_EXCEPTION, RECEIPT, false, true, false, false))
                .containsExactly(
                        VendorBillAction.CORRECT_EXCEPTION,
                        VendorBillAction.VOID_EXCEPTION,
                        VendorBillAction.SET_DUE_DATE);
        assertThat(actions(VendorBillStatus.AWAITING_APPROVAL, RECEIPT, false, true, false, false))
                .containsExactly(VendorBillAction.REJECT, VendorBillAction.SET_DUE_DATE);
        assertThat(actions(VendorBillStatus.MATCH_EXCEPTION, RECEIPT, false, false, false, false))
                .as("matched")
                .containsExactly(
                        VendorBillAction.SUBMIT_FOR_APPROVAL,
                        VendorBillAction.ACCEPT_EXCEPTION,
                        VendorBillAction.CORRECT_EXCEPTION,
                        VendorBillAction.VOID_EXCEPTION,
                        VendorBillAction.SET_DUE_DATE);
        signIn("accounting:ap:view", "accounting:ap:reject");
        assertThat(actions(VendorBillStatus.PENDING_RECEIPT_MATCH, RECEIPT, false, true, false, false))
                .containsExactly(VendorBillAction.VOID_UNMATCHED);
        assertThat(actions(VendorBillStatus.PENDING_RECEIPT_MATCH, EDI, false, false, false, false))
                .as("an EDI bill is never a receipt placeholder")
                .isEmpty();
    }

    @Test
    @DisplayName("AC12: a controller sees approve and accept, allowed, and the void of an unpaid approved bill")
    void controller() {
        signIn(CONTROLLER);
        assertThat(actions(VendorBillStatus.MATCH_EXCEPTION, false, false))
                .containsExactly(
                        VendorBillAction.SUBMIT_FOR_APPROVAL,
                        VendorBillAction.ACCEPT_EXCEPTION,
                        VendorBillAction.CORRECT_EXCEPTION,
                        VendorBillAction.VOID_EXCEPTION,
                        VendorBillAction.SET_DUE_DATE);
        assertThat(actions(VendorBillStatus.AWAITING_APPROVAL, false, false))
                .containsExactly(VendorBillAction.APPROVE, VendorBillAction.REJECT, VendorBillAction.SET_DUE_DATE);
        assertThat(actions(VendorBillStatus.APPROVED, false, false)).containsExactly(VendorBillAction.VOID_APPROVED);
        assertThat(actions(VendorBillStatus.APPROVED, false, true))
                .as("an approved bill with an allocation is not voidable")
                .isEmpty();
        assertThat(actions(VendorBillStatus.APPROVED, EDI, false, false, false, false))
                .as("L4: an approved bill without its posting is not voidable")
                .isEmpty();
    }

    @Test
    @DisplayName("AC10/AC12: a payer holding only ap:pay and ap:view sees no decision")
    void payerSeesNothing() {
        signIn(PAYER);
        for (VendorBillStatus status : VendorBillStatus.values()) {
            assertThat(actions(status, true, false)).as(status.name()).isEmpty();
            assertThat(actions(status, RECEIPT, false, true, false, false))
                    .as(status.name())
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("AC9: a CURRENCY_HOLD bill lists no action, even for a controller; terminal statuses neither")
    void currencyHoldListsNothing() {
        signIn(CONTROLLER);
        for (VendorBillStatus status : List.of(
                VendorBillStatus.CURRENCY_HOLD,
                VendorBillStatus.REJECTED,
                VendorBillStatus.VOIDED,
                VendorBillStatus.PAID)) {
            assertThat(actions(status, true, false)).as(status.name()).isEmpty();
        }
    }

    @Test
    @DisplayName("Justification flags follow the commands: approve, select and the due date need none, unless the"
            + " creator approves under the exception (S13)")
    void justificationFlags() {
        signIn(CONTROLLER);
        assertThat(VendorBillReader.availableActions(
                        VendorBillStatus.AWAITING_APPROVAL,
                        EDI,
                        false,
                        false,
                        false,
                        false,
                        VendorBillReader.Blocks.NONE))
                .extracting(VendorBillReview.AvailableAction::justificationRequired)
                .containsExactly(false, true, false);
        assertThat(VendorBillReader.availableActions(
                        VendorBillStatus.AWAITING_APPROVAL,
                        EDI,
                        false,
                        false,
                        false,
                        false,
                        VendorBillReader.Blocks.NONE))
                .allSatisfy(action -> {
                    assertThat(action.allowed()).isTrue();
                    assertThat(action.blockedReason()).isNull();
                });
        assertThat(VendorBillReader.availableActions(
                        VendorBillStatus.PENDING_RECEIPT_MATCH,
                        RECEIPT,
                        false,
                        true,
                        false,
                        false,
                        VendorBillReader.Blocks.NONE))
                .extracting(VendorBillReview.AvailableAction::justificationRequired)
                .containsExactly(true, false);
        assertThat(VendorBillReader.availableActions(
                        VendorBillStatus.AWAITING_APPROVAL,
                        EDI,
                        false,
                        false,
                        false,
                        false,
                        new VendorBillReader.Blocks(null, null, true)))
                .as("the creator exception applies: APPROVE needs a justification")
                .first()
                .satisfies(action -> {
                    assertThat(action.action()).isEqualTo(VendorBillAction.APPROVE);
                    assertThat(action.justificationRequired()).isTrue();
                    assertThat(action.allowed()).isTrue();
                });
    }

    private static ApApprovalPolicy.Settings policy(String clerk, String auto, boolean creator) {
        return new ApApprovalPolicy.Settings(new BigDecimal(clerk), new BigDecimal(auto), creator, false, "NET30");
    }

    private static VendorBill billOf(String total, String createdBy) {
        VendorBill bill = new VendorBill(UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a09"));
        bill.setTotalAmount(new BigDecimal(total));
        bill.setCreatedBy(createdBy);
        return bill;
    }

    @Test
    @DisplayName("AC11 (S13): the tier blocks a clerk over the limit, never a controller; the creator rule blocks the"
            + " creator; the tier wins when both apply; the switch turns the creator block into a justification")
    void blocks() {
        signIn(CLERK);
        assertThat(VendorBillReader.blocks(billOf("2500.01", "other"), policy("2500.00", "0", false)))
                .isEqualTo(
                        new VendorBillReader.Blocks("AP_APPROVAL_LIMIT_EXCEEDED", "AP_APPROVAL_LIMIT_EXCEEDED", false));
        assertThat(VendorBillReader.blocks(billOf("2500.00", "other"), policy("2500.00", "0", false)))
                .isEqualTo(VendorBillReader.Blocks.NONE);
        assertThat(VendorBillReader.blocks(billOf("100.00", "someone"), policy("2500.00", "0", false)))
                .isEqualTo(new VendorBillReader.Blocks("AP_BILL_SELF_APPROVAL", null, false));
        assertThat(VendorBillReader.blocks(billOf("3000.00", "someone"), policy("2500.00", "0", false)))
                .as("the tier wins")
                .isEqualTo(
                        new VendorBillReader.Blocks("AP_APPROVAL_LIMIT_EXCEEDED", "AP_APPROVAL_LIMIT_EXCEEDED", false));
        assertThat(VendorBillReader.blocks(billOf("100.00", "someone"), policy("2500.00", "0", true)))
                .isEqualTo(new VendorBillReader.Blocks(null, null, true));
        signIn(CONTROLLER);
        assertThat(VendorBillReader.blocks(billOf("2500.01", "other"), policy("2500.00", "0", false)))
                .isEqualTo(VendorBillReader.Blocks.NONE);
    }

    @Test
    @DisplayName("S24 rule 9: the vendor's creator on its first bill is blocked like the bill's creator; the switch"
            + " turns it into a justification; the tier still wins")
    void blocksTheVendorCreator() {
        signIn(CLERK);
        assertThat(VendorBillReader.blocks(billOf("100.00", "other"), policy("2500.00", "0", false), true))
                .isEqualTo(new VendorBillReader.Blocks("AP_BILL_SELF_APPROVAL", null, false));
        assertThat(VendorBillReader.blocks(billOf("100.00", "other"), policy("2500.00", "0", true), true))
                .isEqualTo(new VendorBillReader.Blocks(null, null, true));
        assertThat(VendorBillReader.blocks(billOf("3000.00", "other"), policy("2500.00", "0", false), true))
                .isEqualTo(
                        new VendorBillReader.Blocks("AP_APPROVAL_LIMIT_EXCEEDED", "AP_APPROVAL_LIMIT_EXCEEDED", false));
        assertThat(VendorBillReader.blocks(billOf("100.00", "other"), policy("2500.00", "0", false), false))
                .isEqualTo(VendorBillReader.Blocks.NONE);
    }

    @Test
    @DisplayName("S13: WITHIN_CLERK_LIMIT passes within the limit and fails over it in review; not applicable outside")
    void withinClerkLimit() {
        VendorBillReview.Check within = VendorBillReader.withinClerkLimit(
                VendorBillStatus.AWAITING_APPROVAL, new BigDecimal("-2500.00"), policy("2500.00", "0", false), "USD");
        assertThat(within.code()).isEqualTo("WITHIN_CLERK_LIMIT");
        assertThat(within.outcome()).isEqualTo(VendorBillCheckOutcome.PASS);
        assertThat(within.args())
                .containsEntry("totalAmount", "-2500.00")
                .containsEntry("clerkLimit", "2500.00")
                .containsEntry("currencyCode", "USD");
        assertThat(VendorBillReader.withinClerkLimit(
                                VendorBillStatus.MATCH_EXCEPTION,
                                new BigDecimal("2500.01"),
                                policy("2500.00", "0", false),
                                "USD")
                        .outcome())
                .isEqualTo(VendorBillCheckOutcome.FAIL);
        assertThat(VendorBillReader.withinClerkLimit(
                                VendorBillStatus.APPROVED,
                                new BigDecimal("10.00"),
                                policy("2500.00", "0", false),
                                "USD")
                        .outcome())
                .isEqualTo(VendorBillCheckOutcome.NOT_APPLICABLE);
        assertThat(VendorBillReader.withinClerkLimit(
                                VendorBillStatus.CURRENCY_HOLD,
                                new BigDecimal("10.00"),
                                policy("2500.00", "0", false),
                                "USD")
                        .outcome())
                .isEqualTo(VendorBillCheckOutcome.NOT_APPLICABLE);
    }

    private static VendorBillMatchEvidence evidence(MatchConfidence confidence) {
        VendorBillMatchEvidence evidence = new VendorBillMatchEvidence();
        evidence.setSource(VendorBillMatchEvidence.Source.MATCH);
        evidence.setInvoiceReference("INV-1");
        evidence.setScore(95);
        evidence.setConfidence(confidence);
        evidence.setReceivedTotal(new BigDecimal("400.00"));
        evidence.setBilledTotal(new BigDecimal("412.00"));
        evidence.setCurrencyCode("USD");
        evidence.setWithinTolerance(true);
        return evidence;
    }

    private static List<org.assertj.core.groups.Tuple> codes(List<VendorBillReview.Check> checks) {
        return checks.stream()
                .map(c -> org.assertj.core.groups.Tuple.tuple(c.code(), c.outcome()))
                .toList();
    }

    @Test
    @DisplayName("AC12: checks[] carries MATCHED_TO_DELIVERY and WITHIN_PRICE_TOLERANCE from the latest evidence")
    void checksFromEvidence() {
        VendorBillMatchEvidence evidence = evidence(MatchConfidence.HIGH_CONFIDENCE);

        assertThat(VendorBillReader.checks(RECEIPT, evidence, false, null, null))
                .extracting(VendorBillReview.Check::code, VendorBillReview.Check::outcome)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("MATCHED_TO_DELIVERY", VendorBillCheckOutcome.PASS),
                        org.assertj.core.groups.Tuple.tuple("WITHIN_PRICE_TOLERANCE", VendorBillCheckOutcome.PASS));
        evidence.setWithinTolerance(false);
        assertThat(VendorBillReader.checks(RECEIPT, evidence, false, null, null)
                        .get(1)
                        .outcome())
                .isEqualTo(VendorBillCheckOutcome.FAIL);
    }

    @Test
    @DisplayName("B-M3: a MEDIUM match passes MATCHED_TO_DELIVERY with its confidence; open candidates fail it with"
            + " PICK_A_MATCH")
    void mediumPassesAndOpenCandidatesPick() {
        assertThat(VendorBillReader.checks(RECEIPT, evidence(MatchConfidence.MEDIUM_CONFIDENCE), false, null, null)
                        .get(0))
                .satisfies(check -> {
                    assertThat(check.outcome()).isEqualTo(VendorBillCheckOutcome.PASS);
                    assertThat(check.args()).containsEntry("confidence", "MEDIUM_CONFIDENCE");
                });
        assertThat(VendorBillReader.checks(RECEIPT, null, true, null, null))
                .extracting(VendorBillReview.Check::code, VendorBillReview.Check::outcome)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("MATCHED_TO_DELIVERY", VendorBillCheckOutcome.FAIL),
                        org.assertj.core.groups.Tuple.tuple(
                                "WITHIN_PRICE_TOLERANCE", VendorBillCheckOutcome.NOT_APPLICABLE));
        assertThat(VendorBillReader.checks(RECEIPT, null, true, null, null)
                        .get(0)
                        .args())
                .containsEntry("reason", "PICK_A_MATCH");
        assertThat(VendorBillReader.checks(RECEIPT, null, false, null, null)
                        .get(0)
                        .args())
                .containsEntry("reason", "INVOICE_NOT_MATCHED");
    }

    @Test
    @DisplayName("An EDI bill never matched to a delivery fails MATCHED_TO_DELIVERY; tolerance does not apply")
    void checksWithoutEvidence() {
        assertThat(VendorBillReader.checks(EDI, null, false, null, null))
                .extracting(VendorBillReview.Check::code, VendorBillReview.Check::outcome)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("MATCHED_TO_DELIVERY", VendorBillCheckOutcome.FAIL),
                        org.assertj.core.groups.Tuple.tuple(
                                "WITHIN_PRICE_TOLERANCE", VendorBillCheckOutcome.NOT_APPLICABLE));
        assertThat(VendorBillReader.checks(EDI, null, false, null, null).get(0).args())
                .containsEntry("reason", "NO_DELIVERY_RECORDED");
    }

    private static VendorBill ediBill(String gross, String net, String tax) {
        VendorBill bill = new VendorBill(UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a01"));
        bill.setTotalAmount(new BigDecimal(gross));
        bill.setNetAmount(new BigDecimal(net));
        bill.setTaxAmount(new BigDecimal(tax));
        bill.setStatedLineCount(1);
        return bill;
    }

    @Test
    @DisplayName("AW47: TOTALS_ADD_UP fails with the difference beyond the tolerance and passes within it")
    void totalsAddUp() {
        VendorBillTotals apart =
                VendorBillTotals.of(ediBill("1085.00", "1000.00", "70.00")).orElseThrow();
        VendorBillReview.Check check =
                VendorBillReader.checks(EDI, null, false, apart, null).get(2);
        assertThat(check.code()).isEqualTo("TOTALS_ADD_UP");
        assertThat(check.outcome()).isEqualTo(VendorBillCheckOutcome.FAIL);
        assertThat(check.args()).containsEntry("difference", "15.00").containsEntry("tolerance", "0.01");
        assertThat(apart.explanation())
                .isEqualTo("The vendor's totals don't add up: net 1000.00 + tax 70.00 ≠ total 1085.00");

        VendorBillTotals rounded =
                VendorBillTotals.of(ediBill("1070.01", "1000.00", "70.00")).orElseThrow();
        assertThat(VendorBillReader.checks(EDI, null, false, rounded, null)
                        .get(2)
                        .outcome())
                .isEqualTo(VendorBillCheckOutcome.PASS);
        assertThat(codes(VendorBillReader.checks(RECEIPT, null, false, null, null)))
                .as("a goods-receipt bill has no header totals to check")
                .hasSize(2);
    }

    @Test
    @DisplayName("AW45(c): OPEN_DELIVERIES_FROM_VENDOR fails with the count and numbers of the vendor's open receipts;"
            + " none open passes")
    void openDeliveriesFromVendor() {
        VendorBill open = new VendorBill(UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a05"));
        open.setBillNumber("BILL_A1B2C3D4_20260928_0000001");

        VendorBillReview.Check check =
                VendorBillReader.checks(EDI, null, false, null, List.of(open)).get(2);
        assertThat(check.code()).isEqualTo("OPEN_DELIVERIES_FROM_VENDOR");
        assertThat(check.outcome()).isEqualTo(VendorBillCheckOutcome.FAIL);
        assertThat(check.args())
                .containsEntry("count", "1")
                .containsEntry("billNumbers", "BILL_A1B2C3D4_20260928_0000001");
        assertThat(VendorBillReader.checks(EDI, null, false, null, List.of())
                        .get(2)
                        .outcome())
                .isEqualTo(VendorBillCheckOutcome.PASS);
    }

    @Test
    @DisplayName("AW45: an invoice is matched once a match or selection kept billed lines; an ambiguous match's"
            + " scoring or a CORRECT is no match")
    void invoiceMatched() {
        VendorBillLine billed = new VendorBillLine();
        billed.setQuantity(new BigDecimal("4"));
        billed.setBilledQuantity(new BigDecimal("4"));
        VendorBillLine received = new VendorBillLine();
        received.setQuantity(new BigDecimal("4"));

        assertThat(VendorBillReader.invoiceMatched(evidence(MatchConfidence.HIGH_CONFIDENCE), List.of(billed)))
                .isTrue();
        assertThat(VendorBillReader.invoiceMatched(evidence(MatchConfidence.AMBIGUOUS), List.of(billed)))
                .isFalse();
        assertThat(VendorBillReader.invoiceMatched(evidence(MatchConfidence.HIGH_CONFIDENCE), List.of(received)))
                .as("after CORRECT the billed values are cleared")
                .isFalse();
        assertThat(VendorBillReader.invoiceMatched(null, List.of(billed))).isFalse();
        VendorBillMatchEvidence selection = evidence(MatchConfidence.AMBIGUOUS);
        selection.setSource(VendorBillMatchEvidence.Source.CANDIDATE_SELECTION);
        assertThat(VendorBillReader.invoiceMatched(selection, List.of(billed))).isTrue();
    }

    @Test
    @DisplayName("AC11 (S13): a stage row carries the live requiredTier in review and none outside it; one policy"
            + " snapshot per page")
    void stageRowTier() {
        VendorBillRepository bills = mock();
        APPaymentAllocationRepository allocations = mock();
        ApApprovalPolicy approvalPolicy = mock();
        VendorBillReader reader = new VendorBillReader(
                Clock.systemUTC(),
                bills,
                mock(VendorBillLineRepository.class),
                mock(VendorBillMatchEvidenceRepository.class),
                mock(VendorBillMatchCandidateRepository.class),
                mock(VendorBillGlPostingRepository.class),
                mock(VendorBillReissueRepository.class),
                allocations,
                mock(JournalEntryRepository.class),
                mock(AccountingCalendarZoneResolver.class),
                new LedgerCurrency("USD"),
                approvalPolicy,
                mock(SupplierVendorCopies.class),
                mock(VendorBillTaxRepository.class),
                mock(VendorBillTaxRecoveryRepository.class),
                mock(GLMappingResolver.class),
                mock(GLAccountRepository.class),
                mock(VendorBillPurchaseTax.class),
                mock(ActorDisplayNames.class));
        VendorBill over = billOf("3000.00", "clerk.ana");
        over.setStatus(VendorBillStatus.AWAITING_APPROVAL);
        over.setBillNumber("INV-OVER");
        over.setBillDate(java.time.LocalDateTime.of(2026, 10, 1, 0, 0));
        VendorBill within = new VendorBill(UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a0a"));
        within.setTotalAmount(new BigDecimal("100.00"));
        within.setStatus(VendorBillStatus.AWAITING_APPROVAL);
        within.setBillNumber("INV-WITHIN");
        within.setBillDate(java.time.LocalDateTime.of(2026, 10, 1, 0, 0));
        when(bills.findByStatusIn(any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(over, within)));
        when(allocations.sumAllocatedAmountByVendorBillIdIn(any())).thenReturn(List.of());
        when(approvalPolicy.settings()).thenReturn(policy("2500.00", "0", false));

        assertThat(reader.byStage(VendorBillStage.APPROVE, 0, 20).getContent())
                .extracting(VendorBillReview.StageRow::billNumber, VendorBillReview.StageRow::requiredTier)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("INV-OVER", VendorBillReview.RequiredTier.OVER_LIMIT),
                        org.assertj.core.groups.Tuple.tuple("INV-WITHIN", VendorBillReview.RequiredTier.CLERK));
        verify(approvalPolicy, times(1)).settings();

        VendorBill approved = billOf("100.00", "clerk.ana");
        approved.setStatus(VendorBillStatus.APPROVED);
        approved.setBillNumber("INV-PAY");
        approved.setBillDate(java.time.LocalDateTime.of(2026, 10, 1, 0, 0));
        when(bills.findByStatusAndOpenAmountGreaterThan(any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(approved)));
        assertThat(reader.byStage(VendorBillStage.PAY, 0, 20)
                        .getContent()
                        .get(0)
                        .requiredTier())
                .as("ruling 8: null outside the review statuses")
                .isNull();
    }

    @Test
    @DisplayName("#2615: VENDOR_AP_HOLD is FAIL {vendorNumber, reason, since} while held, PASS otherwise, and"
            + " NOT_APPLICABLE on REJECTED, VOIDED and an APPROVED bill with nothing open (the hold is not read)")
    void vendorApHoldCheck() {
        java.time.Instant since = java.time.Instant.parse("2026-10-08T09:00:00Z");
        java.util.Optional<SupplierVendorCopies.ApHold> held = java.util.Optional.of(
                new SupplierVendorCopies.ApHold("V-000123", "Disputed delivery 4471, awaiting credit", since));

        for (VendorBillStatus status : List.of(
                VendorBillStatus.PENDING_RECEIPT_MATCH,
                VendorBillStatus.MATCH_EXCEPTION,
                VendorBillStatus.AWAITING_APPROVAL,
                VendorBillStatus.CURRENCY_HOLD)) {
            VendorBillReview.Check fail = VendorBillReader.vendorApHold(status, new BigDecimal("10.00"), () -> held);
            assertThat(fail.code()).isEqualTo("VENDOR_AP_HOLD");
            assertThat(fail.outcome()).as("%s", status).isEqualTo(VendorBillCheckOutcome.FAIL);
            assertThat(fail.args())
                    .containsEntry("vendorNumber", "V-000123")
                    .containsEntry("reason", "Disputed delivery 4471, awaiting credit")
                    .containsEntry("since", since.toString());
        }
        assertThat(VendorBillReader.vendorApHold(VendorBillStatus.APPROVED, new BigDecimal("0.01"), () -> held)
                        .outcome())
                .isEqualTo(VendorBillCheckOutcome.FAIL);
        assertThat(VendorBillReader.vendorApHold(
                                VendorBillStatus.AWAITING_APPROVAL, new BigDecimal("10.00"), java.util.Optional::empty)
                        .outcome())
                .isEqualTo(VendorBillCheckOutcome.PASS);
        java.util.function.Supplier<java.util.Optional<SupplierVendorCopies.ApHold>> unread = () -> {
            throw new AssertionError("the hold is not read when the check does not apply");
        };
        for (VendorBillStatus status :
                List.of(VendorBillStatus.REJECTED, VendorBillStatus.VOIDED, VendorBillStatus.PAID)) {
            assertThat(VendorBillReader.vendorApHold(status, new BigDecimal("10.00"), unread)
                            .outcome())
                    .isEqualTo(VendorBillCheckOutcome.NOT_APPLICABLE);
        }
        assertThat(VendorBillReader.vendorApHold(VendorBillStatus.APPROVED, BigDecimal.ZERO, unread)
                        .outcome())
                .isEqualTo(VendorBillCheckOutcome.NOT_APPLICABLE);
    }

    @Test
    @DisplayName("#2615 AC13: stage rows carry vendorApHold from one settings query per page")
    void stageRowHold() {
        VendorBillRepository bills = mock();
        APPaymentAllocationRepository allocations = mock();
        ApApprovalPolicy approvalPolicy = mock();
        SupplierVendorCopies copies = mock();
        VendorBillReader reader = new VendorBillReader(
                Clock.systemUTC(),
                bills,
                mock(VendorBillLineRepository.class),
                mock(VendorBillMatchEvidenceRepository.class),
                mock(VendorBillMatchCandidateRepository.class),
                mock(VendorBillGlPostingRepository.class),
                mock(VendorBillReissueRepository.class),
                allocations,
                mock(JournalEntryRepository.class),
                mock(AccountingCalendarZoneResolver.class),
                new LedgerCurrency("USD"),
                approvalPolicy,
                copies,
                mock(VendorBillTaxRepository.class),
                mock(VendorBillTaxRecoveryRepository.class),
                mock(GLMappingResolver.class),
                mock(GLAccountRepository.class),
                mock(VendorBillPurchaseTax.class),
                mock(ActorDisplayNames.class));
        UUID heldVendor = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a71");
        UUID freeVendor = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a72");
        VendorBill heldBill = billOf("100.00", "clerk.ana");
        heldBill.setVendorId(heldVendor);
        heldBill.setStatus(VendorBillStatus.APPROVED);
        heldBill.setBillNumber("INV-HELD");
        heldBill.setBillDate(java.time.LocalDateTime.of(2026, 10, 1, 0, 0));
        VendorBill freeBill = new VendorBill(UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a73"));
        freeBill.setVendorId(freeVendor);
        freeBill.setTotalAmount(new BigDecimal("50.00"));
        freeBill.setStatus(VendorBillStatus.APPROVED);
        freeBill.setBillNumber("INV-FREE");
        freeBill.setBillDate(java.time.LocalDateTime.of(2026, 10, 1, 0, 0));
        when(bills.findByStatusAndOpenAmountGreaterThan(any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(heldBill, freeBill)));
        when(allocations.sumAllocatedAmountByVendorBillIdIn(any())).thenReturn(List.of());
        when(approvalPolicy.settings()).thenReturn(policy("2500.00", "0", false));
        when(copies.heldVendors(java.util.Set.of(heldVendor, freeVendor)))
                .thenReturn(java.util.Map.of(heldVendor, "Disputed delivery 4471, awaiting credit"));

        assertThat(reader.byStage(VendorBillStage.PAY, 0, 20).getContent())
                .extracting(VendorBillReview.StageRow::billNumber, VendorBillReview.StageRow::vendorApHold)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("INV-HELD", true),
                        org.assertj.core.groups.Tuple.tuple("INV-FREE", false));
        verify(copies, times(1)).heldVendors(any());
    }

    // ---- CAP:550 S32d item 10: the bill read's tax by type and recovery (review of #2664 B4) ---------------------

    private final VendorBillTaxRepository readTaxes = mock(VendorBillTaxRepository.class);
    private final VendorBillTaxRecoveryRepository readRecoveries = mock(VendorBillTaxRecoveryRepository.class);
    private final GLMappingResolver readResolver = mock(GLMappingResolver.class);
    private final GLAccountRepository readAccounts = mock(GLAccountRepository.class);
    private final VendorBillGlPostingRepository readPostings = mock(VendorBillGlPostingRepository.class);
    private final ActorDisplayNames readNames = mock(ActorDisplayNames.class);

    private VendorBillReader taxReader() {
        ApApprovalPolicy approvalPolicy = mock();
        when(approvalPolicy.settings()).thenReturn(policy("2500.00", "0", false));
        return new VendorBillReader(
                Clock.systemUTC(),
                mock(VendorBillRepository.class),
                mock(VendorBillLineRepository.class),
                mock(VendorBillMatchEvidenceRepository.class),
                mock(VendorBillMatchCandidateRepository.class),
                readPostings,
                mock(VendorBillReissueRepository.class),
                mock(APPaymentAllocationRepository.class),
                mock(JournalEntryRepository.class),
                mock(AccountingCalendarZoneResolver.class),
                new LedgerCurrency("CAD"),
                approvalPolicy,
                mock(SupplierVendorCopies.class),
                readTaxes,
                readRecoveries,
                readResolver,
                readAccounts,
                PurchaseTaxFixtures.off(),
                readNames);
    }

    private VendorBill postedBill(UUID id, String net, String tax, String gross) {
        VendorBill bill = new VendorBill(id);
        bill.setTotalAmount(new BigDecimal(gross));
        bill.setNetAmount(new BigDecimal(net));
        bill.setTaxAmount(new BigDecimal(tax));
        bill.setCurrency("CAD");
        bill.setCreatedBy("clerk.ana");
        bill.setStatus(VendorBillStatus.APPROVED);
        bill.setBillNumber("INV-" + id.toString().substring(32));
        bill.setBillDate(java.time.LocalDateTime.of(2026, 10, 1, 0, 0));
        com.positivity.accounting.internal.entity.VendorBillGlPosting posting =
                new com.positivity.accounting.internal.entity.VendorBillGlPosting();
        posting.setVendorBillId(id);
        posting.setPostingDate(java.time.LocalDate.of(2026, 10, 1));
        when(readPostings.findByVendorBillId(id)).thenReturn(java.util.Optional.of(posting));
        return bill;
    }

    private static com.positivity.accounting.internal.entity.VendorBillTax statedTax(
            UUID billId, String type, String amount) {
        com.positivity.accounting.internal.entity.VendorBillTax tax =
                new com.positivity.accounting.internal.entity.VendorBillTax();
        tax.setVendorBillId(billId);
        tax.setTaxType(type);
        tax.setAmount(new BigDecimal(amount));
        tax.setSource(com.positivity.accounting.internal.entity.VendorBillTax.Source.DOCUMENT);
        return tax;
    }

    private static com.positivity.accounting.internal.entity.VendorBillTaxRecovery recovery(
            UUID billId,
            String type,
            String regime,
            String stated,
            String recovered,
            String mappingKey,
            String withheld) {
        com.positivity.accounting.internal.entity.VendorBillTaxRecovery row =
                new com.positivity.accounting.internal.entity.VendorBillTaxRecovery();
        row.setVendorBillId(billId);
        row.setTaxType(type);
        row.setRegime(regime);
        row.setStatedAmount(new BigDecimal(stated));
        row.setRecoveredAmount(new BigDecimal(recovered));
        row.setMappingKey(mappingKey);
        row.setRecoveryWithheldReason(withheld);
        return row;
    }

    @Test
    @DisplayName("S32d AC 8 / B4: a split bill reads its net, its tax by type and the recovery by account")
    void splitAndRecoveredBillRead() {
        UUID billId = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4b01");
        VendorBillReader reader = taxReader();
        VendorBill bill = postedBill(billId, "1000.00", "120.00", "1120.00");
        when(readTaxes.findByVendorBillIdOrderByTaxType(billId))
                .thenReturn(List.of(statedTax(billId, "GST", "50.00"), statedTax(billId, "PST", "70.00")));
        when(readRecoveries.findByVendorBillIdOrderByTaxTypeAsc(billId))
                .thenReturn(List.of(
                        recovery(billId, "GST", "GST_HST", "50.00", "50.00", "TAX_RECOVERABLE_GST_HST", null),
                        recovery(billId, "PST", null, "70.00", "0.00", null, "NOT_RECOVERABLE")));
        UUID account1250 = UUID.randomUUID();
        when(readResolver.resolveGLAccount(
                        VendorBillPostingService.POSTING_CATEGORY,
                        "TAX_RECOVERABLE_GST_HST",
                        java.time.LocalDate.of(2026, 10, 1).atStartOfDay()))
                .thenReturn(account1250);
        com.positivity.accounting.internal.entity.GLAccount gl =
                new com.positivity.accounting.internal.entity.GLAccount(account1250);
        gl.setAccountCode("1250");
        gl.setAccountName("GST/HST Recoverable");
        when(readAccounts.findById(account1250)).thenReturn(java.util.Optional.of(gl));

        com.positivity.accounting.internal.dto.VendorBillResponse read = reader.read(bill);

        assertThat(read.getNetAmount()).isEqualByComparingTo("1000.00");
        assertThat(read.getTaxByType())
                .extracting(VendorBillReview.TaxByType::taxType, VendorBillReview.TaxByType::amount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("GST", new BigDecimal("50.00")),
                        org.assertj.core.groups.Tuple.tuple("PST", new BigDecimal("70.00")));
        assertThat(read.getInputTaxRecovery())
                .extracting(
                        VendorBillReview.InputTaxRecovery::taxType,
                        VendorBillReview.InputTaxRecovery::recoveredAmount,
                        VendorBillReview.InputTaxRecovery::accountCode,
                        VendorBillReview.InputTaxRecovery::recoveryWithheldReason)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("GST", new BigDecimal("50.00"), "1250", null),
                        org.assertj.core.groups.Tuple.tuple("PST", new BigDecimal("0.00"), null, "NOT_RECOVERABLE"));
    }

    @Test
    @DisplayName("S32d AC 10 / B4: an unsplit bill reads no tax by type and TAX_SPLIT_MISSING")
    void unsplitBillRead() {
        UUID billId = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4b02");
        VendorBillReader reader = taxReader();
        VendorBill bill = postedBill(billId, "1000.00", "120.00", "1120.00");
        when(readTaxes.findByVendorBillIdOrderByTaxType(billId)).thenReturn(List.of());
        when(readRecoveries.findByVendorBillIdOrderByTaxTypeAsc(billId))
                .thenReturn(List.of(recovery(billId, null, null, "120.00", "0.00", null, "TAX_SPLIT_MISSING")));

        com.positivity.accounting.internal.dto.VendorBillResponse read = reader.read(bill);

        assertThat(read.getNetAmount()).isEqualByComparingTo("1000.00");
        assertThat(read.getTaxByType()).isEmpty();
        assertThat(read.getInputTaxRecovery()).singleElement().satisfies(row -> {
            assertThat(row.taxType()).isNull();
            assertThat(row.statedAmount()).isEqualByComparingTo("120.00");
            assertThat(row.recoveredAmount()).isZero();
            assertThat(row.accountCode()).isNull();
            assertThat(row.recoveryWithheldReason()).isEqualTo("TAX_SPLIT_MISSING");
        });
    }

    @Test
    @DisplayName("S32d AC 11 / B4: a bill without the supplier's registration reads SUPPLIER_REGISTRATION_MISSING")
    void missingEvidenceBillRead() {
        UUID billId = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4b03");
        VendorBillReader reader = taxReader();
        VendorBill bill = postedBill(billId, "142.86", "7.14", "150.00");
        when(readTaxes.findByVendorBillIdOrderByTaxType(billId)).thenReturn(List.of(statedTax(billId, "GST", "7.14")));
        when(readRecoveries.findByVendorBillIdOrderByTaxTypeAsc(billId))
                .thenReturn(List.of(
                        recovery(billId, "GST", "GST_HST", "7.14", "0.00", null, "SUPPLIER_REGISTRATION_MISSING")));

        com.positivity.accounting.internal.dto.VendorBillResponse read = reader.read(bill);

        assertThat(read.getNetAmount()).isEqualByComparingTo("142.86");
        assertThat(read.getTaxByType())
                .extracting(VendorBillReview.TaxByType::taxType)
                .containsExactly("GST");
        assertThat(read.getInputTaxRecovery()).singleElement().satisfies(row -> {
            assertThat(row.regime()).isEqualTo("GST_HST");
            assertThat(row.recoveredAmount()).isZero();
            assertThat(row.recoveryWithheldReason()).isEqualTo("SUPPLIER_REGISTRATION_MISSING");
        });
    }

    // ---- AP reads #2670: the actors' display names ------------------------------------------------------------

    @Test
    @DisplayName("#2670 AC 6 and AC 7: the bill read serves approvedByName and createdByName from one lookup; SYSTEM"
            + " and an unknown actor serve null, never the username")
    void actorNames() {
        UUID billId = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4b04");
        VendorBillReader reader = taxReader();
        VendorBill bill = postedBill(billId, "100.00", "0.00", "100.00");
        bill.setSubmittedAt(java.time.Instant.parse("2026-10-01T09:00:00Z"));
        bill.setSubmittedBy("SYSTEM");
        bill.setApprovedAt(java.time.Instant.parse("2026-10-01T10:00:00Z"));
        bill.setApprovedBy("controller.cfo");
        bill.setApprovedByKind(com.positivity.accounting.internal.enums.VendorBillApproverKind.PERSON);
        when(readTaxes.findByVendorBillIdOrderByTaxType(billId)).thenReturn(List.of());
        when(readRecoveries.findByVendorBillIdOrderByTaxTypeAsc(billId)).thenReturn(List.of());
        when(readNames.namesOf(org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(java.util.Map.of("controller.cfo", "Dana Reyes"));

        com.positivity.accounting.internal.dto.VendorBillResponse read = reader.read(bill);

        assertThat(read.getApproval().approvedBy()).isEqualTo("controller.cfo");
        assertThat(read.getApproval().approvedByName()).isEqualTo("Dana Reyes");
        assertThat(read.getApproval().submittedBy()).isEqualTo("SYSTEM");
        assertThat(read.getApproval().submittedByName()).isNull();
        assertThat(read.getCreatedBy()).isEqualTo("clerk.ana");
        assertThat(read.getCreatedByName())
                .as("clerk.ana is not linked: null, never the username")
                .isNull();
        assertThat(read.getApproval().toString()).doesNotContain("Dana Reyes");
        assertThat(read.toString()).doesNotContain("Dana Reyes");
        org.mockito.Mockito.verify(readNames, org.mockito.Mockito.times(1))
                .namesOf(org.mockito.ArgumentMatchers.anyCollection());
    }
}
