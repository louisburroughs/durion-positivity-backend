package com.positivity.accounting.service;

import com.positivity.accounting.internal.dto.APPaymentResponse;
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
}
