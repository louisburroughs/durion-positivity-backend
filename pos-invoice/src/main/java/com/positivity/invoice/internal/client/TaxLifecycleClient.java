package com.positivity.invoice.internal.client;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Drives the pos-tax provider document lifecycle (story T6) from invoice state changes:
 * {@code commit} at finalization, {@code voidTransaction} at revert-to-DRAFT (InvoiceStatus
 * has no CANCELLED/VOIDED, so void hooks the revert transition — research R-T2).
 *
 * <p>These are synchronous utility calls (permitted: pos-tax is on the ADR-0044 utility
 * list). Per decision D-T3 (estimate-and-true-up) a provider/transport failure must NEVER
 * block the sale: failures are logged and swallowed here. pos-tax itself never fails a commit
 * — it records a {@code PENDING_COMMIT} row and a scheduled job trues it up — so the only
 * failure this guards against is pos-tax being unreachable, which must not fail finalization.
 */
@Component
public class TaxLifecycleClient {

    private static final Logger log = LoggerFactory.getLogger(TaxLifecycleClient.class);

    private final RestClient restClient;

    public TaxLifecycleClient(
            RestClient.Builder restClientBuilder,
            @Value("${invoice.tax.base-url:http://pos-tax:8091/v1/tax}") String taxServiceBaseUrl) {
        this.restClient = restClientBuilder.baseUrl(taxServiceBaseUrl).build();
    }

    /**
     * Commit the provider tax document for a finalized invoice. Never throws.
     *
     * @param invoiceId the finalized invoice id (provider document code / idempotency key)
     */
    public void commit(@NonNull UUID invoiceId) {
        try {
            restClient
                    .post()
                    .uri("/transactions/{referenceId}/commit?referenceType=INVOICE", invoiceId)
                    .header("X-User", "pos-invoice")
                    .header("X-Authorities", "tax:commit")
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException ex) {
            // D-T3: a tax provider/transport failure must never block finalization.
            log.warn(
                    "Tax commit call failed for invoice {} (finalization not blocked): {}", invoiceId, ex.getMessage());
        }
    }

    /**
     * Void the provider tax document for an invoice reverted to DRAFT. Never throws.
     *
     * @param invoiceId the reverted invoice id (provider document code)
     */
    public void voidTransaction(@NonNull UUID invoiceId) {
        try {
            restClient
                    .post()
                    .uri("/transactions/{referenceId}/void", invoiceId)
                    .header("X-User", "pos-invoice")
                    .header("X-Authorities", "tax:commit")
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException ex) {
            log.warn("Tax void call failed for invoice {} (revert not blocked): {}", invoiceId, ex.getMessage());
        }
    }
}
