package com.positivity.inventory.internal.dto.returns;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Schema(description = "A single line of a return submission: item, quantity, reason and disposition location")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReturnLineDto {
    @Schema(
            description = "Identifier of the item being returned",
            example = "01960003-0000-7000-8000-000000000005",
            requiredMode = REQUIRED)
    @NotNull
    private UUID itemId;

    @Schema(description = "Quantity of the item being returned", example = "3", requiredMode = REQUIRED)
    @Positive
    private int quantity;

    @Schema(description = "Reason code explaining the return", example = "DAMAGED", requiredMode = REQUIRED)
    @NotBlank
    private String reasonCode;

    @Schema(
            description = "Identifier of the location the item is returned to",
            example = "01960003-0000-7000-8000-000000000006",
            requiredMode = REQUIRED)
    @NotNull
    private UUID locationId;

    @Schema(
            description = "Optional storage location within the location to receive the returned item",
            example = "01960003-0000-7000-8000-000000000007",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private UUID storageLocationId;
}
