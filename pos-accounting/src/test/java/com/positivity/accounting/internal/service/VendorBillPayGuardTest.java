package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.VendorBillApproverKind;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Separation of duties 2, approver is not payer (CAP:550 S13, #2510; §4.3, §4.9; AC6, AC7): the pay guard on the
 * allocation plan. The ordering before the payment row and the gateway is APPaymentServiceTest's; the refusal's row
 * surviving the rollback is VendorBillApprovalLimitsPostgresIT's.
 */
@DisplayName("VendorBillPayGuard: approver is not payer (#2510)")
class VendorBillPayGuardTest {

    private final ApApprovalPolicy policy = mock();
    private final AccountingAuditLogRepository auditLogs = mock();
    private VendorBillPayGuard guard;

    @BeforeEach
    void wire() {
        guard = new VendorBillPayGuard(policy, auditLogs, mock(PlatformTransactionManager.class));
        when(policy.forDecision()).thenReturn(settings(false));
    }

    private static ApApprovalPolicy.Settings settings(boolean allowApproverPayment) {
        return new ApApprovalPolicy.Settings(
                new BigDecimal("2500.00"), BigDecimal.ZERO.setScale(2), false, allowApproverPayment, "NET30");
    }

    private static VendorBill bill(String number, String approvedBy, VendorBillApproverKind kind) {
        VendorBill bill = new VendorBill(UUID.randomUUID());
        bill.setBillNumber(number);
        bill.setTotalAmount(new BigDecimal("100.00"));
        bill.setStatus(VendorBillStatus.APPROVED);
        bill.setApprovedBy(approvedBy);
        bill.setApprovedByKind(kind);
        return bill;
    }

    @Test
    @DisplayName("AC6: a bill the payer approved refuses the whole payment, 403 AP_PAYMENT_SELF_APPROVED_BILL naming"
            + " each such bill, one VENDOR_BILL_PAYMENT_REFUSED row per bill")
    void refusesTheApproversPayment() {
        VendorBill mine = bill("INV-B", "ana", VendorBillApproverKind.PERSON);
        VendorBill also = bill("INV-D", "ana", VendorBillApproverKind.PERSON);
        VendorBill theirs = bill("INV-E", "bob", VendorBillApproverKind.PERSON);

        assertThatThrownBy(() -> guard.check(List.of(mine, theirs, also), "ana", "pay-1"))
                .isInstanceOfSatisfying(VendorBillException.class, refusal -> {
                    assertThat(refusal.getCode()).isEqualTo(VendorBillException.Code.AP_PAYMENT_SELF_APPROVED_BILL);
                    assertThat(refusal.getCode().status().value()).isEqualTo(403);
                    assertThat(refusal.getFieldErrors())
                            .extracting(VendorBillException.FieldError::field, VendorBillException.FieldError::message)
                            .containsExactly(
                                    org.assertj.core.groups.Tuple.tuple("selfApprovedBillNumbers", "INV-B"),
                                    org.assertj.core.groups.Tuple.tuple("selfApprovedBillNumbers", "INV-D"));
                });
        ArgumentCaptor<AccountingAuditLog> rows = ArgumentCaptor.forClass(AccountingAuditLog.class);
        verify(auditLogs, times(2)).save(rows.capture());
        assertThat(rows.getAllValues()).allSatisfy(row -> {
            assertThat(row.getOperation()).isEqualTo("VENDOR_BILL_PAYMENT_REFUSED");
            assertThat(row.getUserId()).isEqualTo("ana");
            assertThat(row.getNewValue()).contains("paymentRef=pay-1", "code=AP_PAYMENT_SELF_APPROVED_BILL");
        });
    }

    @Test
    @DisplayName("AC7: a system approval never blocks a payer; another person's approval neither")
    void systemApprovalNeverBlocks() {
        guard.check(
                List.of(
                        bill("INV-S", "SYSTEM", VendorBillApproverKind.SYSTEM),
                        bill("INV-P", "bob", VendorBillApproverKind.PERSON)),
                "SYSTEM",
                "pay-2");
        guard.check(List.of(bill("INV-P", "bob", VendorBillApproverKind.PERSON)), "ana", "pay-3");
        verify(auditLogs, never()).save(any());
    }

    @Test
    @DisplayName(
            "AP_ALLOW_APPROVER_PAYMENT: the payment goes through and each such bill is audited as an exception use")
    void exceptionSwitchAllowsAndAudits() {
        when(policy.forDecision()).thenReturn(settings(true));

        guard.check(List.of(bill("INV-B", "ana", VendorBillApproverKind.PERSON)), "ana", "pay-4");

        ArgumentCaptor<AccountingAuditLog> row = ArgumentCaptor.forClass(AccountingAuditLog.class);
        verify(auditLogs).save(row.capture());
        assertThat(row.getValue().getOperation()).isEqualTo("VENDOR_BILL_SOD_EXCEPTION");
        assertThat(row.getValue().getNewValue()).contains("exception=APPROVER_PAYMENT", "paymentRef=pay-4");
    }
}
