package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.APPaymentResponse;
import com.positivity.accounting.internal.dto.AllocateAPPaymentRequest;
import com.positivity.accounting.internal.dto.ExecuteAPPaymentRequest;
import com.positivity.accounting.internal.dto.VendorBillSummaryResponse;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Service for AP payment orchestration and queries.
 *
 * <p>
 * Primary ownership (from Issue #128 decision record):
 * <ul>
 * <li>Billing/AP owns payment orchestration, allocations, idempotency, workflow
 * state</li>
 * <li>Accounting owns GL posting (asynchronous via event)</li>
 * </ul>
 *
 * <p>
 * Payment lifecycle:
 * <ol>
 * <li>INITIATED: Payment request received</li>
 * <li>GATEWAY_PENDING: Submitted to gateway</li>
 * <li>GATEWAY_SUCCEEDED: Gateway confirmed, allocations applied</li>
 * <li>GL_POST_PENDING: Event emitted for GL posting</li>
 * <li>GL_POSTED: Journal entry posted by accounting</li>
 * </ol>
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/128">Issue
 *      #128</a>
 */
public interface APPaymentService {

    /**
     * Execute a vendor payment with optional explicit allocations.
     *
     * <p>
     * Idempotency: same paymentRef + same payload returns existing payment;
     * same paymentRef + different payload yields 409 conflict.
     *
     * <p>
     * Allocation rules (BR-4):
     * <ul>
     * <li>If explicit allocations provided: validate amounts, bills must be
     * payable/open</li>
     * <li>If no explicit allocations: automatic allocation (oldest due first)</li>
     * <li>Partial allocations allowed</li>
     * <li>Unapplied remainder recorded as vendor credit</li>
     * </ul>
     *
     * @param request     Execute payment request with paymentRef, vendor, amount,
     *                    optional allocations
     * @param currentUser User initiating payment (for audit)
     * @return APPaymentResponse with status, allocations, gateway reference
     * @throws IllegalArgumentException if validation fails (e.g., negative amounts,
     *                                  invalid bills or inconsistent idempotency
     *                                  payload)
     * @throws RuntimeException         if payment processing fails at runtime
     *                                  (e.g.,
     *                                  gateway rejection or unexpected downstream
     *                                  error)
     */
    @NonNull
    APPaymentResponse executePayment(@NonNull ExecuteAPPaymentRequest request, @NonNull String currentUser);

    /**
     * Get payment details by payment ID.
     *
     * @param paymentId Payment UUID
     * @return Optional payment response with full details including allocations
     */
    @NonNull
    Optional<APPaymentResponse> getPaymentById(@NonNull UUID paymentId);

    /**
     * Get payment details by paymentRef (idempotency key).
     *
     * @param paymentRef Unique payment reference
     * @return Optional payment response
     */
    @NonNull
    Optional<APPaymentResponse> getPaymentByRef(@NonNull String paymentRef);

    /**
     * List eligible vendor bills for payment (status = APPROVED, openAmount > 0).
     *
     * @param vendorId Vendor UUID
     * @return List of payable bills ordered by due date (oldest first, nulls last)
     */
    @NonNull
    Page<VendorBillSummaryResponse> listEligibleBills(@Nullable UUID vendorId, @NonNull Pageable pageable);

    /**
     * Acknowledge GL posting completion (called by accounting service after journal
     * entry posted).
     *
     * <p>
     * This updates payment status to GL_POSTED and records journal entry ID.
     *
     * @param paymentId      Payment UUID
     * @param journalEntryId Journal entry ID from accounting
     */
    void acknowledgeGLPosted(@NonNull UUID paymentId, @NonNull UUID journalEntryId);

    /**
     * Record GL posting failure (for retry/alerting).
     *
     * @param paymentId    Payment UUID
     * @param errorMessage GL posting error
     */
    void recordGLPostFailure(@NonNull UUID paymentId, @NonNull String errorMessage);

    /**
     * Posts again a payment whose posting was refused (CAP:550 S42, #2603): only a {@code GL_POST_FAILED} payment, on
     * its stored {@code payment_date}, with the payment row locked.
     *
     * @param paymentId             the payment
     * @param overrideJustification the caller's closed-period justification (with the caller's {@code
     *                              accounting:period:override}); the override stored by the pay command never applies
     * @return the payment, {@code GL_POSTED} with its journal entry id
     * @throws jakarta.persistence.EntityNotFoundException when no such payment is visible (404 {@code NOT_FOUND})
     * @throws com.positivity.accounting.internal.exception.VendorBillException {@code AP_PAYMENT_NOT_RETRYABLE} (409)
     *     when the payment is not {@code GL_POST_FAILED}
     * @throws RuntimeException a refusal (422 {@code GL_MAPPING_NOT_CONFIGURED}, {@code PERIOD_CLOSED}, {@code
     *     PERIOD_HARD_LOCKED}, {@code ACCOUNTING_TIME_ZONE_UNSET}); the payment stays {@code GL_POST_FAILED} with the
     *     new reason in {@code glPostError}
     */
    @NonNull
    APPaymentResponse retryGLPosting(@NonNull UUID paymentId, @Nullable String overrideJustification);

    /**
     * The retry with the #2729 remedies: {@code deferPastHardLock} (needs {@code justification}, audited) dates the
     * entry on the retry's business date when the payment's own date is hard-locked; a {@code GL_POST_PENDING}
     * payment whose outbox row is FAILED is retryable too.
     */
    @NonNull
    APPaymentResponse retryGLPosting(
            @NonNull UUID paymentId,
            @NonNull com.positivity.accounting.internal.dto.APPaymentGLPostingRetryRequest request,
            @NonNull String currentUser);

    /**
     * Reconciles a {@code GATEWAY_PENDING} orphan with the gateway's outcome (#2729): {@code NOT_CHARGED} voids it
     * (posts nothing, nothing to reopen); {@code CHARGED} posts it as an unapplied payment (the vendor's bills are
     * allocated later). 409 {@code AP_PAYMENT_NOT_VOIDABLE} in any other status (with the remedy as {@code
     * nextAction}); 422 {@code AP_PAYMENT_GATEWAY_OUTCOME_REQUIRED} for {@code CHARGED} without a transaction id.
     */
    @NonNull
    APPaymentResponse reconcileGateway(
            @NonNull UUID paymentId,
            @NonNull com.positivity.accounting.internal.dto.ReconcileAPPaymentGatewayRequest request,
            @NonNull String currentUser);

    /**
     * Allocate an unapplied {@code CASH} payment (a drawer cash-on-delivery payout, S17 #2513) to the vendor's
     * approved bills. Posts nothing (AW41 ruling 3): AP was debited at session close and credited at bill approval,
     * so the allocation only matches the subledger. Idempotent on {@code requestId}: the same lines answer the same
     * payment, another body is 409 {@code IDEMPOTENCY_CONFLICT}. Approver ≠ payer applies to every bill (403 {@code
     * AP_PAYMENT_SELF_APPROVED_BILL}). 422 {@code AP_PAYMENT_NOT_ALLOCATABLE} when the payment is not {@code CASH}
     * or nothing of it is unapplied; 400 {@code VALIDATION_ERROR} for a bill of another vendor, an unapproved bill
     * or a total above the unapplied amount.
     */
    APPaymentResponse allocateCashPayment(
            @NonNull UUID paymentId, @NonNull AllocateAPPaymentRequest request, @NonNull String currentUser);
}
