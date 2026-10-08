package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.internal.dto.VendorBillReview;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.entity.VendorBillLine;
import com.positivity.accounting.internal.entity.VendorBillMatchEvidence;
import com.positivity.accounting.internal.enums.MatchConfidence;
import com.positivity.accounting.internal.enums.VendorBillAction;
import com.positivity.accounting.internal.enums.VendorBillCheckOutcome;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.security.common.GatewaySecurityConstants;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
        return VendorBillReader.availableActions(status, channel, candidates, awaitingInvoice, allocated, posted)
                .stream()
                .map(VendorBillReview.AvailableAction::action)
                .toList();
    }

    @Test
    @DisplayName("AC12: a clerk sees submit, correct and void on an exception, reject on a bill awaiting approval,"
            + " and never approve or accept (every bill is over the default limit)")
    void clerk() {
        signIn(CLERK);
        assertThat(actions(VendorBillStatus.PENDING_RECEIPT_MATCH, false, false))
                .containsExactly(VendorBillAction.SUBMIT_FOR_APPROVAL);
        assertThat(actions(VendorBillStatus.MATCH_EXCEPTION, false, false))
                .containsExactly(
                        VendorBillAction.SUBMIT_FOR_APPROVAL,
                        VendorBillAction.CORRECT_EXCEPTION,
                        VendorBillAction.VOID_EXCEPTION);
        assertThat(actions(VendorBillStatus.AWAITING_APPROVAL, false, false)).containsExactly(VendorBillAction.REJECT);
        assertThat(actions(VendorBillStatus.APPROVED, false, false)).isEmpty();
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
                        VendorBillAction.SELECT_CANDIDATE);
        assertThat(actions(VendorBillStatus.PENDING_RECEIPT_MATCH, RECEIPT, true, true, false, false))
                .containsExactly(VendorBillAction.SELECT_CANDIDATE, VendorBillAction.VOID_UNMATCHED);
    }

    @Test
    @DisplayName("AW44: a goods-receipt bill without its invoice is never sent, approved or accepted; it can be voided"
            + " from PENDING_RECEIPT_MATCH with ap:reject")
    void goodsReceiptAwaitingItsInvoice() {
        signIn(CONTROLLER);
        assertThat(actions(VendorBillStatus.PENDING_RECEIPT_MATCH, RECEIPT, false, true, false, false))
                .containsExactly(VendorBillAction.VOID_UNMATCHED);
        assertThat(actions(VendorBillStatus.MATCH_EXCEPTION, RECEIPT, false, true, false, false))
                .containsExactly(VendorBillAction.CORRECT_EXCEPTION, VendorBillAction.VOID_EXCEPTION);
        assertThat(actions(VendorBillStatus.AWAITING_APPROVAL, RECEIPT, false, true, false, false))
                .containsExactly(VendorBillAction.REJECT);
        assertThat(actions(VendorBillStatus.MATCH_EXCEPTION, RECEIPT, false, false, false, false))
                .as("matched")
                .containsExactly(
                        VendorBillAction.SUBMIT_FOR_APPROVAL,
                        VendorBillAction.ACCEPT_EXCEPTION,
                        VendorBillAction.CORRECT_EXCEPTION,
                        VendorBillAction.VOID_EXCEPTION);
        signIn("accounting:ap:view", "accounting:ap:reject");
        assertThat(actions(VendorBillStatus.PENDING_RECEIPT_MATCH, RECEIPT, false, true, false, false))
                .containsExactly(VendorBillAction.VOID_UNMATCHED);
        assertThat(actions(VendorBillStatus.PENDING_RECEIPT_MATCH, EDI, false, false, false, false))
                .as("an EDI bill is never a receipt placeholder")
                .isEmpty();
    }

    @Test
    @DisplayName("AC12: a controller sees approve and accept too, and the void of an unpaid approved bill")
    void controller() {
        signIn(CONTROLLER);
        assertThat(actions(VendorBillStatus.MATCH_EXCEPTION, false, false))
                .containsExactly(
                        VendorBillAction.SUBMIT_FOR_APPROVAL,
                        VendorBillAction.ACCEPT_EXCEPTION,
                        VendorBillAction.CORRECT_EXCEPTION,
                        VendorBillAction.VOID_EXCEPTION);
        assertThat(actions(VendorBillStatus.AWAITING_APPROVAL, false, false))
                .containsExactly(VendorBillAction.APPROVE, VendorBillAction.REJECT);
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
    @DisplayName("Justification flags follow the commands: approve and select need none")
    void justificationFlags() {
        signIn(CONTROLLER);
        assertThat(VendorBillReader.availableActions(
                        VendorBillStatus.AWAITING_APPROVAL, EDI, false, false, false, false))
                .extracting(VendorBillReview.AvailableAction::justificationRequired)
                .containsExactly(false, true);
        assertThat(VendorBillReader.availableActions(
                        VendorBillStatus.AWAITING_APPROVAL, EDI, false, false, false, false))
                .allSatisfy(action -> {
                    assertThat(action.allowed()).isTrue();
                    assertThat(action.blockedReason()).isNull();
                });
        assertThat(VendorBillReader.availableActions(
                        VendorBillStatus.PENDING_RECEIPT_MATCH, RECEIPT, false, true, false, false))
                .extracting(VendorBillReview.AvailableAction::justificationRequired)
                .containsExactly(true);
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
    @DisplayName("AW46: TOTALS_ADD_UP fails with the difference beyond the tolerance and passes within it")
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
    @DisplayName("AW44(c): OPEN_DELIVERIES_FROM_VENDOR fails with the count and numbers of the vendor's open receipts;"
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
    @DisplayName("AW44: an invoice is matched once a match or selection kept billed lines; an ambiguous match's"
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
}
