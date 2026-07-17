package com.positivity.warranty.internal.dto;

import com.positivity.warranty.internal.enums.ProviderType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Create/update payload for a warranty provider (PRD §3.1). */
@Schema(description = "Warranty provider create/update request")
public record ProviderRequest(
        @Schema(description = "Provider display name", example = "Michelin North America") @NotBlank @Size(max = 255)
        String name,

        @Schema(description = "Who funds the program") @NotNull
        ProviderType providerType,

        @Schema(description = "Lifecycle status; defaults to ACTIVE on create", example = "ACTIVE")
        @Pattern(regexp = "ACTIVE|INACTIVE", message = "must be ACTIVE or INACTIVE")
        String status,

        @Schema(description = "Optional pos-accounting ap_vendor.vendorId for credit matching")
        UUID apVendorId,

        @Schema(description = "Optional pos-catalog ProductEntity.manufacturerId for policy lookup")
        UUID manufacturerId,

        @Schema(description = "How claims are submitted to this provider", example = "Portal") @Size(max = 255)
        String claimSubmissionMethod,

        @Size(max = 1024) String portalUrl,
        @Size(max = 255) String contactName,
        @Size(max = 50) String contactPhone,
        @Email @Size(max = 255) String contactEmail) {}
