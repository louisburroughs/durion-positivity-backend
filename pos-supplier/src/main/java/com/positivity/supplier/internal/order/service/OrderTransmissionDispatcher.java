package com.positivity.supplier.internal.order.service;

import com.positivity.supplier.internal.adapter.ediwheelc1.EdiwheelC10OrderCodec;
import com.positivity.supplier.internal.audit.SupplierCorrelationContext;
import com.positivity.supplier.internal.client.SupplierBaseClient;
import com.positivity.supplier.internal.client.SupplierHttpRequest;
import com.positivity.supplier.internal.client.SupplierHttpResponse;
import com.positivity.supplier.internal.domain.model.PartyContext;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierOrderResult;
import com.positivity.supplier.internal.domain.model.SupplierPurchaseOrder;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.domain.model.SupplierRequestSpec;
import com.positivity.supplier.internal.entity.SupplierAccountEntity;
import com.positivity.supplier.internal.entity.SupplierTransmissionIntentEntity;
import com.positivity.supplier.internal.entity.SupplierTransmissionLineEntity;
import com.positivity.supplier.internal.registry.AdapterRegistry;
import com.positivity.supplier.internal.registry.SupplierCodecs;
import com.positivity.supplier.internal.repository.SupplierTransmissionLineRepository;
import com.positivity.supplier.internal.service.SupplierProfileResolver;
import com.positivity.supplier.internal.service.SupplierProfileResolver.ResolvedBinding;
import com.positivity.supplier.internal.service.SupplierProfileResolver.ResolvedPartyAccounts;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;

/**
 * Sends one transmission intent to its vendor, exactly once (ADR-0052 §§2–3, §5).
 *
 * <h2>Deliberately not transactional</h2>
 *
 * This class holds no transaction and must not acquire one. Its whole job is to sequence a
 * committed state change, then a network call, then another committed state change — and a
 * surrounding transaction would collapse that sequence, holding the {@code DISPATCHING} row
 * uncommitted across the very I/O it exists to precede. Every persistence step goes through
 * {@link TransmissionStateWriter}, which owns the transactions.
 *
 * <h2>The order of operations is the safety property</h2>
 *
 * <ol>
 *   <li>Resolve configuration and <strong>encode the document</strong>. Anything that fails here
 *       leaves the intent {@code PENDING}, where a later re-dispatch is safe, because the vendor
 *       cannot know about a document that was never built.
 *   <li>Commit {@code DISPATCHING}. From this instant, no automatic re-send of this intent exists
 *       anywhere in the module.
 *   <li>Send, and classify the outcome (ADR-0052 §5): a definite answer is applied, a pre-send
 *       failure returns to {@code PENDING}, and anything ambiguous goes to status-first
 *       reconciliation — never to a retry.
 * </ol>
 *
 * <p>A response that arrives but cannot be decoded is treated as ambiguous rather than as a
 * failure. The vendor answered; we simply could not read the answer, and the order may well be
 * accepted. Calling that "not ordered" is how a duplicate happens.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderTransmissionDispatcher {

    private final SupplierProfileResolver profileResolver;
    private final AdapterRegistry adapterRegistry;
    private final SupplierBaseClient baseClient;
    private final SupplierTransmissionLineRepository lineRepository;
    private final TransmissionStateWriter stateWriter;
    private final OrderTransmissionReconciler reconciler;

    /**
     * Dispatches one intent.
     *
     * @param intent an intent in {@code PENDING}
     */
    public void dispatch(@NonNull SupplierTransmissionIntentEntity intent) {
        SupplierRef supplierRef = new SupplierRef(intent.getSupplierRef());
        String correlationId = SupplierCorrelationContext.currentOrGenerate();

        ResolvedBinding binding;
        EdiwheelC10OrderCodec codec;
        SupplierRequestSpec spec;
        try {
            binding = profileResolver.resolveBinding(supplierRef, SupplierCapability.ORDER_CREATE);
            codec = SupplierCodecs.require(
                    adapterRegistry, binding, SupplierCapability.ORDER_CREATE, EdiwheelC10OrderCodec.class);
            ResolvedPartyAccounts accounts =
                    profileResolver.resolvePartyContext(supplierRef, intent.getDeliveryLocationId());
            SupplierAccountEntity billing = accounts.billing();
            SupplierAccountEntity delivery = accounts.delivery();
            PartyContext partyContext = new PartyContext(
                    billing.getAccountNumber(), billing.getAgencyCode(), intent.getDeliveryLocationId());
            spec = codec.buildRequest(
                    toCanonicalOrder(intent),
                    partyContext,
                    delivery == null ? null : delivery.getAccountNumber(),
                    delivery == null ? null : delivery.getAgencyCode());
        } catch (RuntimeException e) {
            // Catches SupplierConfigurationException (unbound capability, missing delivery mapping)
            // and OrderEncodeException (a party with no agency code, an unidentifiable line) alike:
            // what matters is not which it was but that it happened before anything was sent.
            // Still PENDING: nothing was sent, so a re-dispatch after the fix is safe and will
            // present the same document id.
            stateWriter.recordPreSendFailure(
                    intent.getTransmissionIntentId(), "could not prepare the order document: " + e.getMessage());
            log.warn(
                    "Transmission intent {} could not be prepared: {}",
                    intent.getTransmissionIntentId(),
                    e.getMessage());
            return;
        }

        if (!stateWriter.markDispatching(intent.getTransmissionIntentId(), correlationId)) {
            // Another instance claimed it between the poll and now.
            return;
        }

        SupplierHttpResponse response = baseClient.exchange(new SupplierHttpRequest(
                binding,
                HttpMethod.valueOf(spec.method()),
                spec.pathSuffix(),
                spec.queryParams(),
                spec.body(),
                spec.contentType(),
                spec.accept(),
                // Never idempotent for an order create, whatever the codec says.
                false));

        switch (response.outcome()) {
            case OK -> applyVendorAnswer(intent, codec, response);
            case PRE_SEND_FAILURE -> {
                stateWriter.recordPreSendFailure(
                        intent.getTransmissionIntentId(), "pre-send failure: " + describe(response));
                log.info(
                        "Transmission intent {} returned to PENDING after a pre-send failure: {}",
                        intent.getTransmissionIntentId(),
                        describe(response));
            }
            case DEFINITIVE_REJECTION -> {
                // A 4xx with the vendor's own error payload. The request reached the vendor and was
                // refused on its merits, so this is a business outcome, not an ambiguity.
                applyDefiniteRejection(intent, codec, response);
            }
            case POST_SEND_AMBIGUOUS, CONFIGURATION_ERROR ->
                reconciler.reconcile(
                        intent.getTransmissionIntentId(), "ambiguous order-create outcome: " + describe(response));
        }
    }

    private void applyVendorAnswer(
            SupplierTransmissionIntentEntity intent, EdiwheelC10OrderCodec codec, SupplierHttpResponse response) {
        SupplierOrderResult result;
        try {
            result = codec.decodeResponse(response.body());
        } catch (RuntimeException e) {
            // The vendor answered and we could not read it. Ambiguous, not failed.
            reconciler.reconcile(
                    intent.getTransmissionIntentId(),
                    "the vendor answered but the response could not be decoded: " + e.getMessage());
            return;
        }
        stateWriter.recordCreateResult(intent.getTransmissionIntentId(), result);
    }

    private void applyDefiniteRejection(
            SupplierTransmissionIntentEntity intent, EdiwheelC10OrderCodec codec, SupplierHttpResponse response) {
        SupplierOrderResult result;
        try {
            result = codec.decodeResponse(response.body());
            if (result.status() == SupplierOrderResult.Status.CONFIRMED) {
                // A 4xx body that decodes as a confirmation is a contradiction. Trust the status
                // code — the transport said refused — and keep the vendor's own text.
                result = new SupplierOrderResult(
                        SupplierOrderResult.Status.REJECTED,
                        result.supplierOrderNumber(),
                        result.vendorReason() == null ? describe(response) : result.vendorReason(),
                        result.vendorErrorCode(),
                        result.lines());
            }
        } catch (RuntimeException e) {
            result = new SupplierOrderResult(
                    SupplierOrderResult.Status.REJECTED, null, describe(response), null, List.of());
        }
        stateWriter.recordCreateResult(intent.getTransmissionIntentId(), result);
    }

    /** Rebuilds the canonical order from the intent and the lines committed alongside it. */
    private SupplierPurchaseOrder toCanonicalOrder(SupplierTransmissionIntentEntity intent) {
        List<SupplierTransmissionLineEntity> rows =
                lineRepository.findByTransmissionIntentIdOrderByLineNumberAsc(intent.getTransmissionIntentId());
        List<SupplierPurchaseOrder.Line> lines = rows.stream()
                .map(row -> new SupplierPurchaseOrder.Line(
                        row.getLineNumber(),
                        row.getArticleEan(),
                        row.getSupplierArticleCode(),
                        row.getQuantity(),
                        row.getRequestedDeliveryDate()))
                .toList();
        return new SupplierPurchaseOrder(
                intent.getTransmissionIntentId(),
                intent.getPurchaseOrderId(),
                intent.getIntentType(),
                intent.getRevision(),
                intent.getDocumentId(),
                intent.getPurchaseOrderNumber(),
                lines);
    }

    private static String describe(SupplierHttpResponse response) {
        return response.outcome()
                + (response.httpStatus() == null ? "" : " HTTP " + response.httpStatus())
                + (response.failureDetail() == null ? "" : " — " + response.failureDetail());
    }
}
