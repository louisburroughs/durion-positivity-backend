package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Product MSRP detail")
public class ProductMsrpDto {

    @Schema(description = "MSRP identifier", example = "0196cf6f-c8dd-7ee0-93e7-f48a5698a535", requiredMode = REQUIRED)
    private UUID msrpId;

    @Schema(
            description = "Product identifier the MSRP applies to",
            example = "2c018664-dfea-46ef-a838-6b9dbf8f9b1d",
            requiredMode = REQUIRED)
    private UUID productId;

    @Schema(description = "MSRP amount", example = "99.99", requiredMode = REQUIRED)
    private String amount;

    @Schema(description = "ISO 4217 currency code", example = "USD", requiredMode = REQUIRED)
    private String currency;

    @Schema(description = "Date the MSRP becomes effective", example = "2026-01-15", requiredMode = REQUIRED)
    private LocalDate effectiveStartDate;

    @Schema(description = "Date the MSRP stops being effective", example = "2026-12-31", requiredMode = NOT_REQUIRED)
    private LocalDate effectiveEndDate;

    @Schema(description = "Timestamp the MSRP was created", example = "2026-01-15T09:30:00Z", requiredMode = REQUIRED)
    private OffsetDateTime createdAt;

    @Schema(description = "Timestamp the MSRP was last updated", example = "2026-01-16T11:00:00Z", requiredMode = NOT_REQUIRED)
    private OffsetDateTime updatedAt;

    @Schema(
            description = "Identifier of the user that last updated the MSRP",
            example = "8b8df63e-18d8-4bde-a8f4-88bc36bc57d7",
            requiredMode = NOT_REQUIRED)
    private UUID updatedBy;

    @Schema(description = "Version for optimistic locking", example = "1", requiredMode = REQUIRED)
    private Long version;
}
