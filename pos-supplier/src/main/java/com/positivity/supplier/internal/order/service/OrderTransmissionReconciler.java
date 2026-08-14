package com.positivity.supplier.internal.order.service;

import com.positivity.supplier.internal.domain.model.SupplierOrderStatusResult;
import com.positivity.supplier.internal.entity.SupplierTransmissionIntentEntity;
import com.positivity.supplier.internal.repository.SupplierTransmissionIntentRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Resolves an ambiguous order-create outcome by asking the vendor, never by re-sending
 * (ADR-0052 §3/§4).
 *
 * <h2>The rule this class exists to enforce</h2>
 *
 * When a create call times out, fails after the body may have gone out, or answers something we
 * cannot read, the vendor may be holding a real order. The one thing that must never happen is a
 * blind retry, because the cost of being wrong is a second physical delivery. So the only moves
 * available here are: ask the vendor and apply what it says, or hand the case to a human.
 *
 * <h2>No status binding means no automatic disposition at all</h2>
 *
 * ADR-0052 §4 is categorical: a profile with {@code ORDER_CREATE} and no {@code ORDER_STATUS}
 * binding has no reconciliation path, so every post-send ambiguity goes straight to
 * {@code MANUAL_REVIEW}. Not "retried more carefully", not "assumed failed" — there is nothing to
 * ask, so there is nothing to conclude.
 *
 * <h2>NOT_FOUND after transmission is still a human's call</h2>
 *
 * A vendor answering "I have no such document" is only safe grounds for re-dispatch when the
 * attempt demonstrably never left {@code PENDING}. Reconciliation runs on attempts that reached
 * {@code DISPATCHING}, where the body may have been transmitted and the vendor's indexing may
 * simply lag. So NOT_FOUND here escalates rather than resends.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderTransmissionReconciler {

    private final SupplierTransmissionIntentRepository intentRepository;
    private final OrderStatusQueryRunner statusQueryRunner;
    private final TransmissionStateWriter stateWriter;

    /**
     * Reconciles one ambiguous transmission.
     *
     * @param transmissionIntentId the intent to reconcile
     * @param ambiguityDetail what made the outcome ambiguous, recorded for the operator who may
     *     end up resolving it
     * @return true when the vendor gave a usable answer and the intent reached a definite state
     */
    public boolean reconcile(@NonNull UUID transmissionIntentId, @Nullable String ambiguityDetail) {
        SupplierTransmissionIntentEntity intent =
                intentRepository.findById(transmissionIntentId).orElse(null);
        if (intent == null) {
            return false;
        }

        if (!statusQueryRunner.isStatusConfigured(intent)) {
            stateWriter.recordManualReview(
                    transmissionIntentId,
                    "no ORDER_STATUS binding for this vendor profile, so the outcome cannot be reconciled"
                            + " (ADR-0052 §4); " + safe(ambiguityDetail));
            return false;
        }

        OrderStatusQueryRunner.Answer answer = statusQueryRunner.query(intent);
        if (!answer.isAvailable()) {
            // Left where it is — DISPATCHING — so recovery tries again on the next cycle and the
            // ageing check escalates it if the vendor stays unreachable. Concluding anything from a
            // failed query would be concluding it from silence.
            log.warn(
                    "Could not reconcile transmission intent {} (document {}): {}",
                    transmissionIntentId,
                    intent.getDocumentId(),
                    answer.unavailableReason());
            return false;
        }

        SupplierOrderStatusResult result = answer.result();
        if (result.status() == SupplierOrderStatusResult.Status.NOT_FOUND) {
            stateWriter.recordManualReview(
                    transmissionIntentId,
                    "the vendor reports no order for document " + intent.getDocumentId()
                            + " after the document may have been transmitted; a re-send would risk a duplicate"
                            + " (ADR-0052 §3); " + safe(ambiguityDetail));
            return false;
        }

        stateWriter.recordReconciliation(transmissionIntentId, result);
        log.info(
                "Reconciled transmission intent {} (document {}) to vendor state {}",
                transmissionIntentId,
                intent.getDocumentId(),
                result.status());
        return true;
    }

    private static String safe(@Nullable String detail) {
        return detail == null ? "" : detail;
    }
}
