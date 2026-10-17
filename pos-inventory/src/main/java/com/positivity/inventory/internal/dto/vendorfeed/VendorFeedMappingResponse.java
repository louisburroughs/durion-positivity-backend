package com.positivity.inventory.internal.dto.vendorfeed;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A feed vendor mapped to a pos-supplier vendor, with the vendor as the local copy holds it")
public class VendorFeedMappingResponse {

    @Schema(description = "Mapping identifier (UUIDv7)", example = "01980003-0000-7000-8000-0000000000a1")
    private UUID mappingId;

    @Schema(description = "Which feed the vendor comes from", example = "MANUFACTURER")
    private String feedType;

    @Schema(description = "The feed's own vendor id", example = "01980003-0000-7000-8000-000000000004")
    private String feedVendorRef;

    @Schema(description = "The pos-supplier vendor id", example = "01980003-0000-7000-8000-000000000009")
    private UUID vendorId;

    @Schema(description = "The vendor's number; null when the vendor is not in the local copy", requiredMode = NOT_REQUIRED)
    private String vendorNumber;

    @Schema(
            description = "The vendor's display name; null when the vendor is not in the local copy",
            requiredMode = NOT_REQUIRED)
    private String vendorDisplayName;

    @Schema(
            description = "The vendor's status; null when the vendor is not in the local copy",
            allowableValues = {"ACTIVE", "INACTIVE"},
            requiredMode = NOT_REQUIRED)
    private String vendorStatus;

    @Schema(description = "Free-text note", requiredMode = NOT_REQUIRED)
    private String note;

    @Schema(description = "Optimistic-lock version; send it back on update", example = "0")
    private Long version;

    private String createdBy;
    private Instant createdAt;
    private String updatedBy;
    private Instant updatedAt;
}
