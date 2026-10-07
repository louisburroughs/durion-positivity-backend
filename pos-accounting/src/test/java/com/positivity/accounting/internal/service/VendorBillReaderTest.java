package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.internal.dto.VendorBillReview;
import com.positivity.accounting.internal.entity.VendorBillMatchEvidence;
import com.positivity.accounting.internal.enums.MatchConfidence;
import com.positivity.accounting.internal.enums.VendorBillAction;
import com.positivity.accounting.internal.enums.VendorBillCheckOutcome;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.security.common.GatewaySecurityConstants;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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

    private static List<VendorBillAction> actions(VendorBillStatus status, boolean candidates, boolean allocated) {
        return VendorBillReader.availableActions(status, candidates, allocated).stream()
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
        assertThat(actions(VendorBillStatus.MATCH_EXCEPTION, true, false))
                .containsExactly(
                        VendorBillAction.SUBMIT_FOR_APPROVAL,
                        VendorBillAction.CORRECT_EXCEPTION,
                        VendorBillAction.VOID_EXCEPTION,
                        VendorBillAction.SELECT_CANDIDATE);
        assertThat(actions(VendorBillStatus.AWAITING_APPROVAL, false, false)).containsExactly(VendorBillAction.REJECT);
        assertThat(actions(VendorBillStatus.APPROVED, false, false)).isEmpty();
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
    }

    @Test
    @DisplayName("AC10/AC12: a payer holding only ap:pay and ap:view sees no decision")
    void payerSeesNothing() {
        signIn(PAYER);
        for (VendorBillStatus status : VendorBillStatus.values()) {
            assertThat(actions(status, true, false)).as(status.name()).isEmpty();
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
        assertThat(VendorBillReader.availableActions(VendorBillStatus.AWAITING_APPROVAL, false, false))
                .extracting(VendorBillReview.AvailableAction::justificationRequired)
                .containsExactly(false, true);
        assertThat(VendorBillReader.availableActions(VendorBillStatus.AWAITING_APPROVAL, false, false))
                .allSatisfy(action -> {
                    assertThat(action.allowed()).isTrue();
                    assertThat(action.blockedReason()).isNull();
                });
    }

    @Test
    @DisplayName("AC12: checks[] carries MATCHED_TO_DELIVERY and WITHIN_PRICE_TOLERANCE from the latest evidence")
    void checksFromEvidence() {
        VendorBillMatchEvidence evidence = new VendorBillMatchEvidence();
        evidence.setInvoiceReference("INV-1");
        evidence.setScore(95);
        evidence.setConfidence(MatchConfidence.HIGH_CONFIDENCE);
        evidence.setReceivedTotal(new BigDecimal("400.00"));
        evidence.setBilledTotal(new BigDecimal("412.00"));
        evidence.setCurrencyCode("USD");
        evidence.setWithinTolerance(true);

        assertThat(VendorBillReader.checks(VendorBillReview.Channel.GOODS_RECEIPT, evidence))
                .extracting(VendorBillReview.Check::code, VendorBillReview.Check::outcome)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("MATCHED_TO_DELIVERY", VendorBillCheckOutcome.PASS),
                        org.assertj.core.groups.Tuple.tuple("WITHIN_PRICE_TOLERANCE", VendorBillCheckOutcome.PASS));
        evidence.setWithinTolerance(false);
        assertThat(VendorBillReader.checks(VendorBillReview.Channel.GOODS_RECEIPT, evidence)
                        .get(1)
                        .outcome())
                .isEqualTo(VendorBillCheckOutcome.FAIL);
    }

    @Test
    @DisplayName("An EDI bill never matched to a delivery fails MATCHED_TO_DELIVERY; tolerance does not apply")
    void checksWithoutEvidence() {
        assertThat(VendorBillReader.checks(VendorBillReview.Channel.SUPPLIER_CONNECTION, null))
                .extracting(VendorBillReview.Check::code, VendorBillReview.Check::outcome)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("MATCHED_TO_DELIVERY", VendorBillCheckOutcome.FAIL),
                        org.assertj.core.groups.Tuple.tuple(
                                "WITHIN_PRICE_TOLERANCE", VendorBillCheckOutcome.NOT_APPLICABLE));
        assertThat(VendorBillReader.checks(VendorBillReview.Channel.SUPPLIER_CONNECTION, null)
                        .get(0)
                        .args())
                .containsEntry("reason", "NO_DELIVERY_RECORDED");
    }
}
