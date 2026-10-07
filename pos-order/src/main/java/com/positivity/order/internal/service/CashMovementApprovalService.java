package com.positivity.order.internal.service;

import com.positivity.order.internal.entity.CashMovementApproval;
import com.positivity.order.internal.entity.CashMovementReason;
import com.positivity.order.internal.service.model.CashMovementApprovalCommand;
import com.positivity.order.internal.service.model.CashMovementApprovalResult;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Manager approval of a drawer cash movement at a shared register (CAP:550 S16, #2512;
 * SPEC-accounting-workspace §4.6, AW31): the step-up and the single-use token it returns.
 */
public interface CashMovementApprovalService {

    /**
     * The step-up: verify the manager's own credentials through pos-security-service and mint a
     * single-use approval token bound to the movement it approves.
     *
     * @throws com.positivity.order.internal.exception.CashMovementRefusedException {@code APPROVAL_DENIED},
     *     {@code SELF_APPROVAL} or {@code CALLER_UNIDENTIFIED}
     */
    @NonNull
    CashMovementApprovalResult approve(@NonNull CashMovementApprovalCommand command);

    /**
     * Use {@code token} for the movement described, inside the caller's transaction (which holds the
     * session's row lock). Returns the approval, now USED.
     *
     * @throws com.positivity.order.internal.exception.CashMovementRefusedException {@code APPROVAL_INVALID}
     *     for an unknown, used, expired or mismatched token; {@code SELF_APPROVAL} when the approver is the
     *     caller; {@code CALLER_UNIDENTIFIED} when the caller's sign-in carries no user id
     */
    @NonNull
    CashMovementApproval use(
            @NonNull String token,
            @NonNull UUID sessionId,
            @NonNull CashMovementReason reason,
            @NonNull BigDecimal amount,
            @NonNull String currencyCode,
            @Nullable String categoryCode,
            @Nullable UUID vendorId);
}
