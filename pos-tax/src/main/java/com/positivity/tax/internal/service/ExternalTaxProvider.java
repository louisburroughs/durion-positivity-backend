package com.positivity.tax.internal.service;

import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import com.positivity.tax.common.dto.TaxProviderTransactionResult;
import com.positivity.tax.common.enums.TaxProviderTransactionStatus;
import com.positivity.tax.service.TaxProviderClient;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * External provider (story T6): today's generic HTTP {@link ExternalTaxServiceClient}
 * refactored behind {@link TaxProviderClient}.
 * <p>
 * <strong>External adapter pending provider selection (R-T1)</strong>: this keeps the
 * existing generic HTTP shape only. The concrete provider (Avalara AvaTax / Stripe Tax /
 * TaxJar) API mapping — real commit/void/refund document semantics and the
 * {@code externalTransactionId} format — is a BLOCKED Wave-5 story. Commit/void here are
 * structured stubs that call the generic client and surface whatever id it returns.
 */
@Component
public class ExternalTaxProvider implements TaxProviderClient {

    /** Stable provider label recorded on the transaction log. */
    static final String PROVIDER_NAME = "EXTERNAL";

    private final ExternalTaxServiceClient client;

    public ExternalTaxProvider(ExternalTaxServiceClient client) {
        this.client = client;
    }

    @Override
    @NonNull
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    @NonNull
    public TaxCalculationResponse estimate(@NonNull TaxCalculationRequest request) {
        return client.calculateTax(request);
    }

    @Override
    @NonNull
    public TaxCalculationResponse refund(@NonNull TaxCalculationRequest request, @NonNull UUID originalReferenceId) {
        // The request already carries calculationType=REFUND and originalReferenceId; the
        // generic client forwards both. Real provider refund-document mapping is R-T1 (Wave 5).
        return client.calculateTax(request);
    }

    @Override
    @NonNull
    public TaxProviderTransactionResult commit(@NonNull UUID referenceId) {
        String externalTransactionId = client.commitDocument(referenceId);
        return new TaxProviderTransactionResult(
                referenceId,
                TaxProviderTransactionStatus.COMMITTED,
                externalTransactionId,
                "external adapter pending provider selection (R-T1)");
    }

    @Override
    @NonNull
    public TaxProviderTransactionResult voidTransaction(@NonNull UUID referenceId) {
        String externalTransactionId = client.voidDocument(referenceId);
        return new TaxProviderTransactionResult(
                referenceId,
                TaxProviderTransactionStatus.VOIDED,
                externalTransactionId,
                "external adapter pending provider selection (R-T1)");
    }
}
