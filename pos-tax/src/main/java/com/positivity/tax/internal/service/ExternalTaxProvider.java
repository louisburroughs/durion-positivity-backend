package com.positivity.tax.internal.service;

import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import com.positivity.tax.common.dto.TaxProviderTransactionResult;
import com.positivity.tax.common.enums.TaxCalculationType;
import com.positivity.tax.common.enums.TaxProviderTransactionStatus;
import com.positivity.tax.internal.exception.TaxCalculationTypeUnsupportedException;
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
        refuseUse(request, PROVIDER_NAME);
        return client.calculateTax(request);
    }

    /**
     * An external provider prices no self-assessed tax yet (CAP:550 S43): {@code USE} answers 501 {@code
     * TAX_CALCULATION_TYPE_UNSUPPORTED} before any provider call. Test mode and the self-hosted plug-ins price it.
     */
    static void refuseUse(@NonNull TaxCalculationRequest request, @NonNull String provider) {
        if (request.getCalculationType() == TaxCalculationType.USE) {
            throw new TaxCalculationTypeUnsupportedException("The " + provider + " tax provider does not price"
                    + " calculationType USE (self-assessed tax); only test mode and the self-hosted plug-ins do");
        }
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
