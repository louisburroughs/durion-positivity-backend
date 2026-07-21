package com.positivity.tax.service;

import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import com.positivity.tax.common.dto.TaxProviderTransactionResult;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Provider abstraction with the Avalara-shaped document lifecycle (story T6).
 * <p>
 * A tax document is first <em>estimated</em> (uncommitted), then <em>committed</em> when
 * its invoice finalizes, and <em>voided</em> when the invoice reverts to DRAFT
 * (InvoiceStatus has no CANCELLED/VOIDED — void hooks the revert transition, research
 * R-T2). Refunds map to a provider REFUND-type document (story T4).
 * <p>
 * Implementations: {@code TestModeTaxProvider} (wraps the T1 calculator; commit/void are
 * recorded no-ops that never fail) and {@code ExternalTaxProvider} (today's generic HTTP
 * client refactored behind this interface; the concrete external adapter is BLOCKED until
 * a provider is selected — R-T1). The active implementation is chosen by the test-mode flag.
 * <p>
 * Idempotency: the {@code referenceId} is the provider document code / idempotency key on
 * every lifecycle call, so retries never double-commit.
 */
public interface TaxProviderClient {

    /**
     * Short, stable provider identifier recorded on the transaction log (e.g. {@code TEST_MODE}).
     *
     * @return the provider name
     */
    @NonNull
    String providerName();

    /**
     * Uncommitted tax calculation for the request.
     *
     * @param request the tax calculation request
     * @return the calculated tax response
     */
    @NonNull
    TaxCalculationResponse estimate(@NonNull TaxCalculationRequest request);

    /**
     * Refund-document calculation (story T4). Amounts are positive, priced at the
     * supplied original sale date; callers negate at posting.
     *
     * @param request             the refund calculation request ({@code calculationType=REFUND})
     * @param originalReferenceId the original sale document being refunded
     * @return the calculated refund tax response
     */
    @NonNull
    TaxCalculationResponse refund(@NonNull TaxCalculationRequest request, @NonNull UUID originalReferenceId);

    /**
     * Commit the document identified by {@code referenceId} (invoice finalization).
     * Idempotent on {@code referenceId}.
     *
     * @param referenceId the document code / idempotency key
     * @return the commit outcome
     */
    @NonNull
    TaxProviderTransactionResult commit(@NonNull UUID referenceId);

    /**
     * Void the document identified by {@code referenceId} (invoice revert-to-DRAFT).
     * {@code void} is a Java keyword, hence {@code voidTransaction}.
     *
     * @param referenceId the document code / idempotency key
     * @return the void outcome
     */
    @NonNull
    TaxProviderTransactionResult voidTransaction(@NonNull UUID referenceId);
}
