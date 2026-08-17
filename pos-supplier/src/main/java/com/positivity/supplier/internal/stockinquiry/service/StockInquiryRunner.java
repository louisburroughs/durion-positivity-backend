package com.positivity.supplier.internal.stockinquiry.service;

import com.positivity.supplier.internal.adapter.ediwheela2.EdiwheelA25StockInquiryCodec;
import com.positivity.supplier.internal.adapter.ediwheela2.StockInquiryDecodeException;
import com.positivity.supplier.internal.adapter.ediwheela2.StockInquiryEncodeException;
import com.positivity.supplier.internal.client.SupplierBaseClient;
import com.positivity.supplier.internal.client.SupplierHttpResponse;
import com.positivity.supplier.internal.client.SupplierRequests;
import com.positivity.supplier.internal.domain.model.PartyContext;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.domain.model.SupplierRequestSpec;
import com.positivity.supplier.internal.domain.model.SupplierStockInquiry;
import com.positivity.supplier.internal.domain.model.SupplierStockInquiryResult;
import com.positivity.supplier.internal.entity.SupplierAccountEntity;
import com.positivity.supplier.internal.exception.SupplierConfigurationException;
import com.positivity.supplier.internal.registry.AdapterRegistry;
import com.positivity.supplier.internal.registry.SupplierCodecs;
import com.positivity.supplier.internal.service.SupplierProfileResolver;
import com.positivity.supplier.internal.service.SupplierProfileResolver.ResolvedBinding;
import com.positivity.supplier.internal.service.SupplierProfileResolver.ResolvedPartyAccounts;
import com.positivity.supplier.internal.spi.ExchangeOutcome;
import com.positivity.supplier.internal.spi.SupplierStockInquiryPort;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

/**
 * Runs one live stock inquiry end to end (CAP-319 #1225).
 *
 * <h2>This method does not throw</h2>
 *
 * A stock inquiry sits inside a product page and a procurement screen. If it threw, every caller
 * would need a try/catch whose only sensible body is "render without live stock" — and the first
 * caller to forget one takes down a page over a vendor's bad afternoon. So every failure becomes a
 * status: an unbound capability, an unmapped delivery location, a refused connection, a timeout, an
 * unreadable answer. The exception types still exist and are still specific; they are translated
 * here, once, rather than at every call site.
 *
 * <h2>Configuration failures never reach the network</h2>
 *
 * The binding, the codec and both party accounts are resolved before a request is built. A profile
 * that cannot name the receiving location's account is answered {@code CONFIGURATION_ERROR} without
 * a call, because the alternative — asking the vendor about an unstated address — returns an answer
 * that looks right and is about the wrong place.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockInquiryRunner implements SupplierStockInquiryPort {

    private final SupplierProfileResolver profileResolver;
    private final AdapterRegistry adapterRegistry;
    private final SupplierBaseClient baseClient;

    /**
     * Asks the vendor what it has, for one receiving location.
     *
     * <p>The commercial identity is resolved here rather than passed in: the billing account and the
     * delivery account both come from the vendor profile, and a caller that supplied them could
     * supply a pair the profile does not agree with.
     *
     * @param supplierRef the vendor profile alias to ask
     * @param deliveryLocationId the receiving location the question is about; required, because
     *     availability is consignee-specific
     * @param inquiry the articles to ask about
     * @return the canonical answer; never null, and never an exception for a vendor-side failure
     */
    @Override
    @NonNull
    public SupplierStockInquiryResult inquireAvailability(
            @NonNull SupplierRef supplierRef, @NonNull UUID deliveryLocationId, @NonNull SupplierStockInquiry inquiry) {

        ResolvedBinding binding;
        EdiwheelA25StockInquiryCodec codec;
        SupplierAccountEntity billing;
        SupplierAccountEntity delivery;
        try {
            binding = profileResolver.resolveBinding(supplierRef, SupplierCapability.STOCK_INQUIRY);
            codec = SupplierCodecs.require(
                    adapterRegistry, binding, SupplierCapability.STOCK_INQUIRY, EdiwheelA25StockInquiryCodec.class);
            ResolvedPartyAccounts accounts = profileResolver.resolvePartyContext(supplierRef, deliveryLocationId);
            billing = accounts.billing();
            delivery = accounts.delivery();
        } catch (SupplierConfigurationException e) {
            return configurationOutcome(supplierRef, e);
        }

        PartyContext partyContext =
                new PartyContext(billing.getAccountNumber(), billing.getAgencyCode(), deliveryLocationId);

        SupplierRequestSpec spec;
        try {
            spec = codec.buildRequest(
                    inquiry,
                    partyContext,
                    delivery == null ? null : delivery.getAccountNumber(),
                    delivery == null ? null : delivery.getAgencyCode());
        } catch (StockInquiryEncodeException e) {
            log.warn("Cannot express a stock inquiry for supplier {} in A2.5: {}", supplierRef.value(), e.getMessage());
            return failed(SupplierStockInquiryResult.Status.CONFIGURATION_ERROR);
        }

        SupplierHttpResponse response = baseClient.exchange(SupplierRequests.toHttpRequest(binding, spec));
        if (!response.isSuccess()) {
            // Every non-OK outcome is the same answer to a caller rendering a page: we could not
            // find out. The distinctions the client draws (pre-send, ambiguous, rejected) matter for
            // retry safety on state-creating calls, and an inquiry creates no state.
            log.debug(
                    "Stock inquiry to supplier {} did not answer: outcome={} detail={}",
                    supplierRef.value(),
                    response.outcome(),
                    response.failureDetail());
            return failed(
                    response.outcome() == ExchangeOutcome.CONFIGURATION_ERROR
                            ? SupplierStockInquiryResult.Status.CONFIGURATION_ERROR
                            : SupplierStockInquiryResult.Status.SUPPLIER_UNAVAILABLE);
        }

        try {
            return codec.decodeQuote(response.body());
        } catch (StockInquiryDecodeException e) {
            // An answer we cannot read is not an answer. Reporting the articles as unavailable would
            // turn our parsing problem into the vendor's empty warehouse.
            log.warn("Unreadable stock inquiry answer from supplier {}: {}", supplierRef.value(), e.getMessage());
            return failed(SupplierStockInquiryResult.Status.SUPPLIER_UNAVAILABLE);
        }
    }

    /**
     * Maps a configuration failure to the status that tells an operator where to look.
     *
     * <p>An unbound capability is a deployment that has not switched this vendor on, which is a
     * normal state and reported as such (ADR-0050 §3). Everything else — an unknown alias, a
     * disabled profile, a missing account mapping — is a profile that is switched on and wrong.
     */
    private SupplierStockInquiryResult configurationOutcome(SupplierRef supplierRef, SupplierConfigurationException e) {
        if (SupplierConfigurationException.CAPABILITY_NOT_CONFIGURED.equals(e.getCode())) {
            log.debug("Stock inquiry not configured for supplier {}", supplierRef.value());
            return failed(SupplierStockInquiryResult.Status.CAPABILITY_NOT_CONFIGURED);
        }
        log.warn(
                "Stock inquiry for supplier {} is misconfigured: {} — {}",
                supplierRef.value(),
                e.getCode(),
                e.getMessage());
        return failed(SupplierStockInquiryResult.Status.CONFIGURATION_ERROR);
    }

    private static SupplierStockInquiryResult failed(SupplierStockInquiryResult.Status status) {
        return new SupplierStockInquiryResult(status, List.of(), null);
    }
}
