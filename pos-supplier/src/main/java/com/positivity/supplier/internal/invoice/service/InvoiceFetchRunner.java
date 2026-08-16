package com.positivity.supplier.internal.invoice.service;

import com.positivity.supplier.internal.adapter.ediwheelb3.EdiwheelB33InvoiceCodec;
import com.positivity.supplier.internal.adapter.ediwheelb3.InvoiceDecodeException;
import com.positivity.supplier.internal.client.SupplierBaseClient;
import com.positivity.supplier.internal.client.SupplierHttpRequest;
import com.positivity.supplier.internal.client.SupplierHttpResponse;
import com.positivity.supplier.internal.domain.model.PartyContext;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierInvoice;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.domain.model.SupplierRequestSpec;
import com.positivity.supplier.internal.entity.SupplierAccountEntity;
import com.positivity.supplier.internal.exception.SupplierConfigurationException;
import com.positivity.supplier.internal.registry.AdapterRegistry;
import com.positivity.supplier.internal.registry.AdapterResolution;
import com.positivity.supplier.internal.service.SupplierProfileResolver;
import com.positivity.supplier.internal.service.SupplierProfileResolver.ResolvedBinding;
import com.positivity.supplier.internal.service.SupplierProfileResolver.ResolvedPartyAccounts;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;

/**
 * Fetches one window of vendor invoices and imports what is new (CAP-321 #1343).
 *
 * <h2>One path, two callers</h2>
 *
 * The schedule and the operator's on-demand trigger both come through here. Two implementations of
 * "fetch a window" would drift, and the one that drifts is the one reached for under pressure —
 * when somebody is chasing a missing invoice and wants it now.
 *
 * <h2>A failed fetch throws, and that is the point</h2>
 *
 * Nothing here degrades to an empty result. The caller runs this inside
 * {@code SupplierScheduleCoordinator.runPage}, which advances the checkpoint in the same
 * transaction — so an exception rolls the checkpoint back with it and the next run re-covers the
 * window. Returning quietly instead would advance the checkpoint past invoices that were never
 * fetched, and a missing invoice is not noticed until the vendor chases payment.
 *
 * <p>That is the opposite of the live stock inquiry, where a vendor being down must never fail a
 * customer's screen. The difference is that nobody is waiting on this, and the cost of pretending
 * is a debt that disappears rather than a blank column.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceFetchRunner {

    private final SupplierProfileResolver profileResolver;
    private final AdapterRegistry adapterRegistry;
    private final SupplierBaseClient baseClient;
    private final InvoiceImporter importer;

    /**
     * Fetches the vendor's invoices for an inclusive date window and imports the new ones.
     *
     * @return how many invoices were new; the rest were already held from an overlapping window
     * @throws InvoiceFetchException when the vendor could not be reached or refused the window
     * @throws SupplierConfigurationException when the profile, binding, codec or billing account is
     *     not configured — a deployment defect, surfaced loudly rather than retried forever
     */
    public int fetchWindow(@NonNull SupplierRef supplierRef, @NonNull LocalDate fromDate, @NonNull LocalDate toDate) {
        ResolvedBinding binding = profileResolver.resolveBinding(supplierRef, SupplierCapability.INVOICE_FETCH);
        EdiwheelB33InvoiceCodec codec = resolveCodec(binding);
        ResolvedPartyAccounts accounts = profileResolver.resolvePartyContext(supplierRef, null);
        SupplierAccountEntity billing = accounts.billing();

        PartyContext partyContext = new PartyContext(billing.getAccountNumber(), billing.getAgencyCode(), null);
        SupplierRequestSpec spec = codec.buildRequest(partyContext, fromDate, toDate);
        SupplierHttpResponse response = baseClient.exchange(toHttpRequest(binding, spec));

        if (!response.isSuccess()) {
            // Thrown, not recorded as an empty window. The vendor may well have issued invoices in
            // this window and simply not told us; treating silence as "none" would let the
            // checkpoint move past them for good.
            throw new InvoiceFetchException("vendor exchange failed: " + response.outcome()
                    + (response.failureDetail() == null ? "" : " — " + response.failureDetail()));
        }

        List<SupplierInvoice> invoices;
        try {
            invoices = codec.decode(response.body());
        } catch (InvoiceDecodeException e) {
            // Also thrown. A document-level refusal ("window too wide") is a configuration problem
            // that will recur until somebody changes something, and it must not look like a quiet
            // stretch of days with no invoices.
            throw new InvoiceFetchException("vendor refused or returned an unreadable window: " + e.getMessage(), e);
        }

        int imported = importer.importInvoices(binding.binding().getVendorProfileId(), supplierRef.value(), invoices);
        log.info(
                "Invoice window {}..{} for {}: {} fetched, {} new",
                fromDate,
                toDate,
                supplierRef.value(),
                invoices.size(),
                imported);
        return imported;
    }

    private SupplierHttpRequest toHttpRequest(ResolvedBinding binding, SupplierRequestSpec spec) {
        return new SupplierHttpRequest(
                binding,
                HttpMethod.valueOf(spec.method()),
                spec.pathSuffix(),
                spec.queryParams(),
                spec.body(),
                spec.contentType(),
                spec.accept(),
                spec.idempotent());
    }

    private EdiwheelB33InvoiceCodec resolveCodec(ResolvedBinding binding) {
        AdapterResolution resolution =
                adapterRegistry.resolve(SupplierCapability.INVOICE_FETCH, binding.family(), binding.version());
        if (resolution instanceof AdapterResolution.Resolved resolved
                && resolved.codec() instanceof EdiwheelB33InvoiceCodec codec) {
            return codec;
        }
        throw new SupplierConfigurationException(
                SupplierConfigurationException.CAPABILITY_NOT_CONFIGURED,
                "No invoice codec registered for " + binding.family() + " "
                        + binding.version().value());
    }
}
