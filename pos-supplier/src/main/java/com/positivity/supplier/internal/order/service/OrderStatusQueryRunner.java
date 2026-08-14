package com.positivity.supplier.internal.order.service;

import com.positivity.supplier.internal.adapter.ediwheelc1.EdiwheelC11OrderStatusCodec;
import com.positivity.supplier.internal.client.SupplierBaseClient;
import com.positivity.supplier.internal.client.SupplierHttpRequest;
import com.positivity.supplier.internal.client.SupplierHttpResponse;
import com.positivity.supplier.internal.domain.model.PartyContext;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierOrderStatusResult;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.domain.model.SupplierRequestSpec;
import com.positivity.supplier.internal.entity.SupplierAccountEntity;
import com.positivity.supplier.internal.entity.SupplierTransmissionIntentEntity;
import com.positivity.supplier.internal.exception.SupplierConfigurationException;
import com.positivity.supplier.internal.registry.AdapterRegistry;
import com.positivity.supplier.internal.registry.AdapterResolution;
import com.positivity.supplier.internal.service.SupplierProfileResolver;
import com.positivity.supplier.internal.service.SupplierProfileResolver.ResolvedBinding;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
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
public class OrderStatusQueryRunner {

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
        SupplierRef supplierRef = new SupplierRef(intent.getSupplierRef());
        ResolvedBinding binding;
        EdiwheelC11OrderStatusCodec codec;
        PartyContext partyContext;
        try {
            binding = profileResolver.resolveBinding(supplierRef, SupplierCapability.ORDER_STATUS);
            codec = resolveCodec(binding);
            SupplierAccountEntity billing =
                    profileResolver.resolvePartyContext(supplierRef, null).billing();
            partyContext = new PartyContext(billing.getAccountNumber(), billing.getAgencyCode(), null);
        } catch (SupplierConfigurationException e) {
            return Answer.unavailable("order status is not usable for this profile: " + e.getMessage());
        }

        SupplierRequestSpec spec;
        try {
            spec = codec.buildRequest(intent.getDocumentId(), intent.getSupplierOrderNumber(), partyContext);
        } catch (RuntimeException e) {
            return Answer.unavailable("could not build an order-status query: " + e.getMessage());
        }

        SupplierHttpResponse response = baseClient.exchange(new SupplierHttpRequest(
                binding,
                HttpMethod.valueOf(spec.method()),
                spec.pathSuffix(),
                spec.queryParams(),
                spec.body(),
                spec.contentType(),
                spec.accept(),
                spec.idempotent()));

        if (!response.isSuccess()) {
            return Answer.unavailable("vendor exchange failed: " + response.outcome()
                    + (response.failureDetail() == null ? "" : " — " + response.failureDetail()));
        }

        try {
            EdiwheelC11OrderStatusCodec.Decoded decoded = codec.decode(intent.getDocumentId(), response.body());
            if (!decoded.isComplete()) {
                // Not a failure: the answer is usable, but an operator diagnosing a missing
                // delivery date needs to know the vendor stated one we could not read.
                log.warn(
                        "Order status for document {} carried values this codec could not read: {}",
                        intent.getDocumentId(),
                        decoded.unmappedFields());
            }
            return Answer.of(decoded.result());
        } catch (RuntimeException e) {
            return Answer.unavailable("could not decode the vendor's order-status answer: " + e.getMessage());
        }
    }

    private EdiwheelC11OrderStatusCodec resolveCodec(ResolvedBinding binding) {
        AdapterResolution resolution =
                adapterRegistry.resolve(SupplierCapability.ORDER_STATUS, binding.family(), binding.version());
        if (resolution instanceof AdapterResolution.Resolved resolved
                && resolved.codec() instanceof EdiwheelC11OrderStatusCodec codec) {
            return codec;
        }
        throw new SupplierConfigurationException(
                SupplierConfigurationException.CAPABILITY_NOT_CONFIGURED,
                "No order-status codec registered for " + binding.family() + " "
                        + binding.version().value());
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
