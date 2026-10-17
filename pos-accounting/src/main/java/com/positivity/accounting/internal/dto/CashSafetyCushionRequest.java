package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Request to set or clear the tenant's cash safety cushion (CAP:550 S19, #2515). The service validates every field, so
 * each refusal is the platform's 400 {@code VALIDATION_ERROR} naming the field.
 */
@Data
@NoArgsConstructor
@Schema(description = "Sets or clears the tenant's cash safety cushion, a management threshold of the cash outlook")
public class CashSafetyCushionRequest {

    @Schema(
            description = "The cushion in the tenant's functional currency, at its minor-unit precision and not"
                    + " negative; 0 is a real value. The key is required: send null to clear it (unset).",
            example = "2500.00",
            nullable = true,
            requiredMode = REQUIRED)
    private BigDecimal amount;

    @JsonIgnore
    @Setter(AccessLevel.NONE)
    @Schema(hidden = true)
    private boolean amountPresent;

    @Schema(
            description = "ISO 4217 code of the amount; must be the tenant's functional currency (422"
                    + " CURRENCY_NOT_SUPPORTED otherwise)",
            example = "USD",
            requiredMode = REQUIRED)
    private String currencyCode;

    @Schema(
            description = "Why the cushion changes; 10 to 1000 characters; recorded on the audit row",
            example = "Treasury sets the cushion at one payroll after the Q3 review",
            requiredMode = REQUIRED)
    private String justification;

    @Schema(
            description = "Client-generated UUID that makes the PUT idempotent: a replay with the same body writes"
                    + " nothing, a reuse with another body is refused 409",
            example = "019a1d3e-6f7a-7b8c-9d0e-1f2a3b4c5d6e",
            requiredMode = REQUIRED)
    private UUID requestId;

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
        this.amountPresent = true;
    }
}
