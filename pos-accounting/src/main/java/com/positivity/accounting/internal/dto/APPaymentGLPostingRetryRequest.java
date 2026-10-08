package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Optional body of {@code POST /v1/accounting/ap/payments/{paymentId}/gl-posting-retry} (CAP:550 S42, #2603). A missing
 * body is an empty request: no override of the caller's own.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Optional request payload for posting a refused AP payment again")
public class APPaymentGLPostingRetryRequest {

    @Nullable
    @Size(min = 10, max = 1000, message = "Override justification must be 10-1000 characters")
    @Schema(
            description = "Justification for posting into the CLOSED period of the payment's own date; honoured only"
                    + " with the caller's accounting:period:override, and audited under the caller. A retry never reuses"
                    + " the override the payer gave on the pay command. Never bypasses the hard lock.",
            example = "Period reopened for audit adjustments; posting the June vendor payment",
            requiredMode = NOT_REQUIRED)
    @JsonProperty("overrideJustification")
    private String overrideJustification;
}
