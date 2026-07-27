package com.positivity.inventory.internal.dto.purchasesuggestion;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to dismiss a purchase suggestion; the reason is mandatory")
public class DismissPurchaseSuggestionRequest {

    @Schema(
            description = "Why the suggestion is being dismissed",
            example = "Vendor contract under renegotiation",
            requiredMode = REQUIRED)
    @NotBlank
    @Size(max = 255)
    private String reason;
}
