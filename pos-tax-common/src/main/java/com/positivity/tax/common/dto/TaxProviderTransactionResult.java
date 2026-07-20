package com.positivity.tax.common.dto;

import com.positivity.tax.common.enums.TaxProviderTransactionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Outcome of a provider document lifecycle call (story T6): commit or void.
 * <p>
 * Returned by {@code TaxProviderClient.commit}/{@code voidTransaction} and by the
 * pos-tax lifecycle REST endpoints. The {@code status} reflects what was recorded in the
 * {@code tax_provider_transaction} log; {@code externalTransactionId} carries the
 * provider's document id once an external adapter is selected (R-T1).
 *
 * @param referenceId           the document code / idempotency key (source invoice id)
 * @param status                the recorded lifecycle status
 * @param externalTransactionId the provider transaction id, when known ({@code null} in test mode)
 * @param message               a short human-readable outcome note
 */
@Schema(name = "TaxProviderTransactionResult", description = "Outcome of a provider tax-document lifecycle call")
public record TaxProviderTransactionResult(
        @Schema(
                description = "Document code / idempotency key (source invoice id)",
                requiredMode = Schema.RequiredMode.REQUIRED)
        UUID referenceId,

        @Schema(description = "Recorded lifecycle status", requiredMode = Schema.RequiredMode.REQUIRED)
        TaxProviderTransactionStatus status,

        @Schema(
                description = "Provider transaction id when known; null in test mode",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String externalTransactionId,

        @Schema(description = "Short human-readable outcome note", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String message) {}
