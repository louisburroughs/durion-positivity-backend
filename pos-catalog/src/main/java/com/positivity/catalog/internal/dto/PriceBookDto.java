package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.catalog.internal.entity.PriceBookScope;
import com.positivity.catalog.internal.entity.PriceBookStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Price book detail")
public class PriceBookDto {

    @Schema(
            description = "Price book identifier",
            example = "0196cf6f-c8dd-7ee0-93e7-f48a5698a535",
            requiredMode = REQUIRED)
    private UUID priceBookId;

    @Schema(description = "Price book name", example = "West Region Pricing", requiredMode = REQUIRED)
    private String name;

    @Schema(
            description = "Scope the price book applies to",
            example = "LOCATION",
            implementation = PriceBookScope.class,
            requiredMode = REQUIRED)
    private PriceBookScope scope;

    @Schema(
            description = "Identifier of the scoped entity (location or customer tier)",
            example = "2c018664-dfea-46ef-a838-6b9dbf8f9b1d",
            requiredMode = NOT_REQUIRED)
    private UUID scopeId;

    @Schema(
            description = "Whether this is the default price book for the scope",
            example = "false",
            requiredMode = REQUIRED)
    private boolean isDefault;

    @Schema(
            description = "Current status of the price book",
            example = "ACTIVE",
            implementation = PriceBookStatus.class,
            requiredMode = REQUIRED)
    private PriceBookStatus status;

    @Schema(
            description = "Timestamp the price book was created",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    private OffsetDateTime createdAt;

    @Schema(
            description = "Timestamp the price book was last updated",
            example = "2026-01-16T11:00:00Z",
            requiredMode = NOT_REQUIRED)
    private OffsetDateTime updatedAt;

    @Schema(description = "Version for optimistic locking", example = "1", requiredMode = REQUIRED)
    private Long version;
}
