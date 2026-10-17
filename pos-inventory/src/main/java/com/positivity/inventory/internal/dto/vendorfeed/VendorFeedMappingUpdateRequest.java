package com.positivity.inventory.internal.dto.vendorfeed;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to point an existing feed vendor mapping at another pos-supplier vendor")
public class VendorFeedMappingUpdateRequest {

    @Schema(
            description = "The pos-supplier vendor the feed vendor is; it must be known to pos-inventory and ACTIVE",
            example = "01980003-0000-7000-8000-000000000009",
            requiredMode = REQUIRED)
    @NotNull
    private UUID vendorId;

    @Schema(description = "Free-text note about the mapping; null clears it", example = "Same company, new entity")
    @Size(max = 500)
    private String note;

    @Schema(
            description = "The version of the mapping the caller read; a different stored version is a 409",
            example = "0",
            requiredMode = REQUIRED)
    @NotNull
    private Long version;
}
