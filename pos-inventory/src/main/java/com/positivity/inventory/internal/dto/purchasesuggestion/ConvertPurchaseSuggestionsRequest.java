package com.positivity.inventory.internal.dto.purchasesuggestion;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(
        description = "Request to convert one or more ACCEPTED purchase suggestions into a single multi-line"
                + " DRAFT purchase order. All suggestions must be ACCEPTED and share one vendor")
public class ConvertPurchaseSuggestionsRequest {

    @Schema(
            description = "Identifiers of the ACCEPTED suggestions to convert; all must share one vendor",
            requiredMode = REQUIRED)
    @NotNull
    @NotEmpty
    private List<UUID> suggestionIds;
}
