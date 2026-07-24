package com.positivity.order.service;

import com.positivity.order.service.model.CreateReturnCommand;
import com.positivity.order.service.model.ReturnOrderSummary;
import com.positivity.order.service.model.ReturnableLineView;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Returns & refunds against completed sales orders — the Odoo {@code refund()} analog (parity
 * stories F1/F2, issues #1086/#1087, spec R5.1–R5.5). Enforces the three refund invariants: a
 * negative document linked line-by-line to one source order, a per-line cap at the un-refunded
 * remainder, and exactly one completed source order per return.
 */
public interface ReturnOrderService {

    /**
     * Creates a return against a completed order, idempotent on the creation key. Enforces the
     * per-line qty cap under a row lock; a return whose refund exceeds the approval threshold lands
     * in PENDING_APPROVAL. Over-cap requests fail with a 422 listing each line's returnableQty.
     */
    @NonNull
    ReturnOrderSummary createReturn(@NonNull CreateReturnCommand command);

    /** Fetches a return order by id (404 if unknown). */
    @NonNull
    ReturnOrderSummary getReturn(@NonNull UUID returnOrderId);

    /** All returns taken against an original order, newest first. */
    @NonNull
    List<ReturnOrderSummary> listByOriginalOrder(@NonNull UUID originalOrderId);

    /** Per-line returnable remainder for a completed order (spec R5.4). */
    @NonNull
    List<ReturnableLineView> returnableLines(@NonNull UUID originalOrderId);

    /** Approves a PENDING_APPROVAL return, moving it to RETURN_REQUESTED for the saga. */
    @NonNull
    ReturnOrderSummary approveReturn(@NonNull UUID returnOrderId);

    /** Rejects a PENDING_APPROVAL return with a reason (terminal REJECTED). */
    @NonNull
    ReturnOrderSummary rejectReturn(@NonNull UUID returnOrderId, @NonNull String reason);

    /**
     * Runs the return orchestration saga (parity story F2, #1087, spec R5.3) from RETURN_REQUESTED:
     * issue the refund (original-tender reversal fails loudly; store-credit / on-account credit
     * paths recorded), then emit {@code order.order.returned} and complete. A refund failure parks
     * the return at REFUND_FAILED before any stock movement, retryable via {@link #retryReturn}.
     * Idempotent — a COMPLETED return is returned unchanged.
     */
    @NonNull
    ReturnOrderSummary processReturn(@NonNull UUID returnOrderId);

    /** Retries a REFUND_FAILED return's saga from the refund step. */
    @NonNull
    ReturnOrderSummary retryReturn(@NonNull UUID returnOrderId);
}
