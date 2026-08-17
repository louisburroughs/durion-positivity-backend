package com.positivity.supplier.internal.order.service;

import com.positivity.supplier.internal.adapter.ediwheelc1.EdiwheelC11OrderStatusCodec;
import com.positivity.supplier.internal.client.SupplierBaseClient;
import com.positivity.supplier.internal.client.SupplierHttpResponse;
import com.positivity.supplier.internal.client.SupplierRequests;
import com.positivity.supplier.internal.domain.model.PartyContext;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierOrderStatusResult;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.domain.model.SupplierRequestSpec;
import com.positivity.supplier.internal.entity.SupplierAccountEntity;
import com.positivity.supplier.internal.entity.SupplierTransmissionIntentEntity;
import com.positivity.supplier.internal.exception.OrderStatusUnavailableException;
import com.positivity.supplier.internal.exception.SupplierConfigurationException;
import com.positivity.supplier.internal.registry.AdapterRegistry;
import com.positivity.supplier.internal.registry.SupplierCodecs;
import com.positivity.supplier.internal.service.SupplierProfileResolver;
import com.positivity.supplier.internal.service.SupplierProfileResolver.ResolvedBinding;
import com.positivity.supplier.internal.spi.SupplierOrderStatusPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Asks a vendor what it knows about one transmitted order.
 *
 * <p>The single place an {@code ORDER_STATUS} exchange happens, used by both callers that need
 * one: reconciliation after an ambiguous create (ADR-0052 §3) and the fulfilment poller that
 * feeds the purchase-order timeline (#1318). Keeping it in one place is what stops the two
 * growing different ideas of what the vendor said.
 *
 * <h2>A failed query is not an answer</h2>
 *
 * When the exchange fails, or the document cannot be decoded, this returns
 * {@link Answer#unavailable}. It emphatically does not return {@code NOT_FOUND}: "the vendor says
 * it has no such order" and "we could not ask the vendor" are the same shape and opposite facts,
 * and conflating them would let a network blip retire a real order — or, on the reconciliation
 * path, drive an order that the vendor is holding straight into a manual review that concludes it
 * was never sent.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderStatusQueryRunner implements SupplierOrderStatusPort {

    private final SupplierProfileResolver profileResolver;
    private final AdapterRegistry adapterRegistry;
    private final SupplierBaseClient baseClient;

    /** Whether the vendor profile can answer status questions at all (ADR-0052 §4). */
    public boolean isStatusConfigured(@NonNull SupplierTransmissionIntentEntity intent) {
        return profileResolver.isConfigured(new SupplierRef(intent.getSupplierRef()), SupplierCapability.ORDER_STATUS);
    }

    /**
     * Queries the vendor for one intent's order.
     *
     * @param intent the transmission to ask about
     * @return the vendor's answer, or an unavailable answer when we could not obtain one
     */
    @NonNull
    public Answer query(@NonNull SupplierTransmissionIntentEntity intent) {
        try {
            return Answer.of(queryOrderStatus(
                    new SupplierRef(intent.getSupplierRef()), intent.getDocumentId(), intent.getSupplierOrderNumber()));
        } catch (OrderStatusUnavailableException e) {
            // Turned into an answer rather than propagated: the reconciler's caller is a scheduled
            // sweep over many intents, and one unreachable vendor must not end it.
            return Answer.unavailable(e.getMessage());
        }
    }

    /**
     * Asks the vendor what became of one order (the {@link SupplierOrderStatusPort}).
     *
     * <p>Throws when no answer was obtained, rather than returning an empty result. "The vendor has
     * no such order" and "we could not ask the vendor" are opposite findings for a reconciler
     * resolving an ambiguous transmission — one means nothing was placed, the other means we still
     * do not know — and a single empty return would make them indistinguishable.
     */
    @Override
    @NonNull
    public SupplierOrderStatusResult queryOrderStatus(
            @NonNull SupplierRef supplierRef, @Nullable String documentId, @Nullable String supplierOrderNumber) {
        ResolvedBinding binding;
        EdiwheelC11OrderStatusCodec codec;
        PartyContext partyContext;
        try {
            binding = profileResolver.resolveBinding(supplierRef, SupplierCapability.ORDER_STATUS);
            codec = SupplierCodecs.require(
                    adapterRegistry, binding, SupplierCapability.ORDER_STATUS, EdiwheelC11OrderStatusCodec.class);
            SupplierAccountEntity billing =
                    profileResolver.resolvePartyContext(supplierRef, null).billing();
            partyContext = new PartyContext(billing.getAccountNumber(), billing.getAgencyCode(), null);
        } catch (SupplierConfigurationException e) {
            throw new OrderStatusUnavailableException(
                    "order status is not usable for this profile: " + e.getMessage(), e);
        }

        SupplierRequestSpec spec;
        try {
            spec = codec.buildRequest(documentId, supplierOrderNumber, partyContext);
        } catch (RuntimeException e) {
            throw new OrderStatusUnavailableException("could not build an order-status query: " + e.getMessage(), e);
        }

        SupplierHttpResponse response = baseClient.exchange(SupplierRequests.toHttpRequest(binding, spec));
        if (!response.isSuccess()) {
            throw new OrderStatusUnavailableException("vendor exchange failed: " + response.outcome()
                    + (response.failureDetail() == null ? "" : " — " + response.failureDetail()));
        }

        try {
            EdiwheelC11OrderStatusCodec.Decoded decoded = codec.decode(documentId, response.body());
            if (!decoded.isComplete()) {
                // Not a failure: the answer is usable, but an operator diagnosing a missing
                // delivery date needs to know the vendor stated one we could not read.
                log.warn(
                        "Order status for document {} carried values this codec could not read: {}",
                        documentId,
                        decoded.unmappedFields());
            }
            return decoded.result();
        } catch (RuntimeException e) {
            throw new OrderStatusUnavailableException(
                    "could not decode the vendor's order-status answer: " + e.getMessage(), e);
        }
    }

    /**
     * Either the vendor's answer or the reason we do not have one.
     *
     * @param result the vendor's answer; null when unavailable
     * @param unavailableReason why no answer was obtained; null when there is one
     */
    public record Answer(
            @Nullable SupplierOrderStatusResult result,
            @Nullable String unavailableReason) {

        static Answer of(SupplierOrderStatusResult result) {
            return new Answer(result, null);
        }

        static Answer unavailable(String reason) {
            return new Answer(null, reason);
        }

        /** Whether the vendor actually answered. */
        public boolean isAvailable() {
            return result != null;
        }
    }
}
