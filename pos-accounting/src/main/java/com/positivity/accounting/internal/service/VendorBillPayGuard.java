package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.VendorBillApproverKind;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Separation of duties 2, approver is not payer (CAP:550 S13, #2510; SPEC-accounting-workspace §4.3, §4.9; AW6, AW7):
 * the pay guard of {@code POST /v1/accounting/ap/payments}. It runs in the payment's pre-gateway block, on the bills
 * of the allocation plan (explicit, or oldest due first), locked, before any payment row is saved and before the
 * gateway is called (§3 P4).
 *
 * <p>A bill a person approved ({@code approvedByKind = PERSON}) whose approver is the payer refuses the whole payment:
 * 403 {@code AP_PAYMENT_SELF_APPROVED_BILL}, its field errors naming each such bill by number, and one {@value
 * #AUDIT_REFUSED} row per bill written in a transaction of its own, so it survives the payment's rollback. Automatic
 * allocation never skips such a bill silently. Under {@code AP_ALLOW_APPROVER_PAYMENT} the payment goes through and
 * each such bill gets one {@value VendorBillApprovalServiceImpl#AUDIT_SOD_EXCEPTION} row. A system approval never
 * blocks a payer. Payer and approver are both the security context's username (ADR-0018), the form every vendor-bill
 * decision records.
 */
@Slf4j
@Component
public class VendorBillPayGuard {

    static final String AUDIT_REFUSED = "VENDOR_BILL_PAYMENT_REFUSED";

    /** {@code exception=} of the exception-use row: the approver paid under {@code AP_ALLOW_APPROVER_PAYMENT}. */
    static final String EXCEPTION_APPROVER_PAYMENT = "APPROVER_PAYMENT";

    /** The field of each bill named in the refusal's field errors. */
    static final String FIELD_BILLS = "selfApprovedBillNumbers";

    private final ApApprovalPolicy policy;
    private final AccountingAuditLogRepository auditLogs;
    private final TransactionTemplate refusalTransaction;

    public VendorBillPayGuard(
            ApApprovalPolicy policy,
            AccountingAuditLogRepository auditLogs,
            PlatformTransactionManager transactionManager) {
        this.policy = policy;
        this.auditLogs = auditLogs;
        this.refusalTransaction = new TransactionTemplate(transactionManager);
        this.refusalTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Refuses the payment when {@code payer} approved any bill of the plan, unless the tenant allows it.
     *
     * @param plannedBills the bills of the allocation plan, locked
     * @param payer the caller paying
     * @param paymentRef the payment's idempotency key, recorded on each audit row
     * @throws VendorBillException 403 {@code AP_PAYMENT_SELF_APPROVED_BILL}
     */
    public void check(@NonNull List<VendorBill> plannedBills, @NonNull String payer, @NonNull String paymentRef) {
        List<VendorBill> selfApproved = plannedBills.stream()
                .filter(bill -> bill.getApprovedByKind() == VendorBillApproverKind.PERSON)
                .filter(bill -> payer.equals(bill.getApprovedBy()))
                .distinct()
                .toList();
        if (selfApproved.isEmpty()) {
            return;
        }
        ApApprovalPolicy.Settings settings = policy.forDecision();
        if (settings.allowApproverPayment()) {
            selfApproved.forEach(bill -> auditLogs.save(row(
                    bill,
                    VendorBillApprovalServiceImpl.AUDIT_SOD_EXCEPTION,
                    payer,
                    "operation=AP_PAYMENT;paymentRef=" + paymentRef + ";exception=" + EXCEPTION_APPROVER_PAYMENT)));
            log.info(
                    "Payment {} pays {} bill(s) its payer approved, under AP_ALLOW_APPROVER_PAYMENT",
                    paymentRef,
                    selfApproved.size());
            return;
        }
        refusalTransaction.executeWithoutResult(_ -> selfApproved.forEach(bill -> auditLogs.save(row(
                bill,
                AUDIT_REFUSED,
                payer,
                "paymentRef=" + paymentRef + ";code="
                        + VendorBillException.Code.AP_PAYMENT_SELF_APPROVED_BILL.name()))));
        log.info("Payment {} refused: its payer approved {} of its bills", paymentRef, selfApproved.size());
        throw new VendorBillException(
                VendorBillException.Code.AP_PAYMENT_SELF_APPROVED_BILL,
                "You approved " + (selfApproved.size() == 1 ? "a bill" : selfApproved.size() + " bills")
                        + " this payment would pay; another person pays them",
                selfApproved.stream()
                        .map(bill -> new VendorBillException.FieldError(FIELD_BILLS, bill.getBillNumber()))
                        .toList(),
                "Ask another person holding accounting:ap:pay to pay these bills, or leave them out of this payment");
    }

    private static AccountingAuditLog row(VendorBill bill, String operation, String payer, String details) {
        AccountingAuditLog row = new AccountingAuditLog();
        row.setEntityType(VendorBillApprovalServiceImpl.AUDIT_ENTITY_TYPE);
        row.setEntityId(bill.getVendorBillId());
        row.setOperation(operation);
        row.setUserId(payer);
        row.setNewValue("billNumber=" + bill.getBillNumber() + ";approvedBy=" + bill.getApprovedBy() + ";" + details);
        return row;
    }
}
