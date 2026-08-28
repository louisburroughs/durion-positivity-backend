package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.CustomerCreditApplicationRequest;
import com.positivity.accounting.internal.dto.CustomerCreditRefundRequest;
import com.positivity.accounting.internal.dto.CustomerCreditResponse;
import com.positivity.accounting.internal.dto.CustomerCreditTransactionResponse;
import com.positivity.accounting.internal.enums.CustomerCreditStatus;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Draw-down of AR customer credits (story parity-C1 follow-on, issue #992).
 *
 * <p>Overpayment issuance (#975) recognizes a Customer Credit Liability (2300); this service
 * owns the other half of the lifecycle — <em>relieving</em> that liability when the credit is
 * applied to a later invoice or refunded to the customer. Every draw-down posts its mirror
 * journal entry through the transactional outbox, so across the full lifecycle a
 * fully-consumed credit nets the 2300 control account back to zero.
 */
public interface CustomerCreditService {

    /**
     * Apply an open customer credit to an outstanding invoice.
     *
     * <p>Posts {@code Dr Customer Credit Liability / Cr Accounts Receivable} for the applied
     * amount, enqueued in the same transaction as the draw-down record and gated by the
     * accounting period.
     *
     * <p>The draw-down copies the credit's own currency verbatim: the {@code ext_invoice} replica
     * carries no currency (pos-invoice is single-currency), so there is nothing to compare against
     * and no currency check is performed.
     *
     * @param creditId    credit to draw down
     * @param request     invoice, amount, and idempotency key
     * @param currentUser user recording the application
     * @return the recorded draw-down, including the credit's remaining open amount
     * @throws org.springframework.web.server.ResponseStatusException 400 when the amount is
     *         missing or non-positive; 404 when the credit or invoice is unknown; 409 when the
     *         credit lacks the open amount, the invoice is not AR-eligible, the invoice belongs to
     *         another customer, the amount exceeds the invoice balance, the accounting period is
     *         not open, or the request id was already used for a different operation
     */
    @NonNull
    CustomerCreditTransactionResponse applyCreditToInvoice(
            @NonNull UUID creditId, @NonNull CustomerCreditApplicationRequest request, @NonNull String currentUser);

    /**
     * Refund an open customer credit as cash back to the customer.
     *
     * <p>Posts {@code Dr Customer Credit Liability / Cr Undeposited Funds} for the refunded
     * amount, with the same transactional, idempotency, and period guarantees as an application.
     *
     * @param creditId    credit to draw down
     * @param request     amount, optional note, and idempotency key
     * @param currentUser user recording the refund
     * @return the recorded draw-down, including the credit's remaining open amount
     * @throws org.springframework.web.server.ResponseStatusException 400 when the amount is
     *         missing or non-positive; 404 when the credit is unknown; 409 when it lacks the open
     *         amount, the accounting period is not open, or the request id was already used for a
     *         different operation
     */
    @NonNull
    CustomerCreditTransactionResponse refundCredit(
            @NonNull UUID creditId, @NonNull CustomerCreditRefundRequest request, @NonNull String currentUser);

    /**
     * Fetch a single customer credit with its consumption state.
     *
     * @param creditId credit identifier
     * @return the credit
     * @throws org.springframework.web.server.ResponseStatusException 404 when unknown
     */
    @NonNull
    CustomerCreditResponse getCredit(@NonNull UUID creditId);

    /**
     * List customer credits, optionally narrowed to one customer and/or consumption state.
     *
     * @param customerId optional customer filter
     * @param status     optional consumption-state filter
     * @param pageable   pagination
     * @return matching credits
     */
    @NonNull
    Page<CustomerCreditResponse> listCredits(
            @Nullable UUID customerId, @Nullable CustomerCreditStatus status, @NonNull Pageable pageable);
}
