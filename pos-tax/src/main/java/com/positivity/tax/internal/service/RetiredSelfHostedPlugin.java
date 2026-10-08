package com.positivity.tax.internal.service;

import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import com.positivity.tax.common.dto.TaxProviderTransactionResult;
import com.positivity.tax.common.enums.TaxProviderTransactionStatus;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;

/**
 * The lifecycle side of a self-hosted plug-in whose country profile is no longer configured
 * (CAP:550 S32a, ADR-0071 §3).
 * <p>
 * A document priced by {@code <country>_SELF} keeps reaching that plug-in for commit and void even
 * after a configuration change removes the profile: both are logged no-ops that hold no state, so
 * they need no profile. Pricing needs one, so estimate and refund refuse.
 */
@Slf4j
final class RetiredSelfHostedPlugin implements TaxProviderClient {

    private final String providerName;

    RetiredSelfHostedPlugin(@NonNull String providerName) {
        this.providerName = providerName;
    }

    @Override
    @NonNull
    public String providerName() {
        return providerName;
    }

    @Override
    @NonNull
    public TaxCalculationResponse estimate(@NonNull TaxCalculationRequest request) {
        throw new IllegalStateException("Tax plug-in " + providerName + " has no configured profile");
    }

    @Override
    @NonNull
    public TaxCalculationResponse refund(@NonNull TaxCalculationRequest request, @NonNull UUID originalReferenceId) {
        throw new IllegalStateException("Tax plug-in " + providerName + " has no configured profile");
    }

    @Override
    @NonNull
    public TaxProviderTransactionResult commit(@NonNull UUID referenceId) {
        log.info("Tax plug-in {} (no configured profile) commit for reference {}: no-op", providerName, referenceId);
        return new TaxProviderTransactionResult(
                referenceId, TaxProviderTransactionStatus.COMMITTED, null, providerName + " commit (no-op)");
    }

    @Override
    @NonNull
    public TaxProviderTransactionResult voidTransaction(@NonNull UUID referenceId) {
        log.info("Tax plug-in {} (no configured profile) void for reference {}: no-op", providerName, referenceId);
        return new TaxProviderTransactionResult(
                referenceId, TaxProviderTransactionStatus.VOIDED, null, providerName + " void (no-op)");
    }
}
