package com.positivity.warranty.internal.dto;

import com.positivity.warranty.internal.enums.AppliesToType;
import com.positivity.warranty.internal.enums.CoverageType;
import com.positivity.warranty.internal.enums.ProrationMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Create/update payload for a warranty policy's structured coverage terms (PRD §3.2).
 * Cross-field {@code appliesTo} consistency (e.g. {@code PRODUCT_LIST} requires a non-empty
 * product-id list) is enforced in the service layer.
 */
@Schema(description = "Warranty policy create/update request — structured coverage terms")
public record PolicyRequest(
        @Schema(description = "Owning warranty provider id") @NotNull
        UUID providerId,

        @Schema(description = "Program name", example = "Michelin passenger replacement — workmanship & materials")
        @NotBlank
        @Size(max = 255)
        String name,

        @Schema(description = "Claim type this policy covers") @NotNull
        CoverageType coverageType,

        @Schema(description = "Matching scope of the appliesTo terms") @NotNull
        AppliesToType appliesToType,

        @Schema(description = "Explicit pos-catalog productEntityIds; required when appliesToType=PRODUCT_LIST")
        List<UUID> appliesToProductEntityIds,

        @Schema(description = "pos-catalog category id; required when appliesToType=CATEGORY")
        UUID appliesToCategoryId,

        @Schema(description = "pos-catalog manufacturerId; required when appliesToType=MANUFACTURER")
        UUID appliesToManufacturerId,

        @Schema(description = "Policy must be in effect on the original sale date") @NotNull
        LocalDate effectiveFrom,

        LocalDate effectiveTo,

        @Schema(description = "Time limit in months from sale date; null = unlimited") @Positive
        Integer durationMonths,

        @Schema(description = "Miles from sale odometer; null = n/a") @Positive
        Integer mileageLimit,

        @Schema(description = "Tires: coverage ends at this remaining depth, in 32nds of an inch", example = "2")
        @Min(0)
        @Max(32)
        Integer treadPullPointThirtySeconds,

        @Schema(description = "Whether the provider reimburses install labor; defaults to false")
        Boolean laborCovered,

        @Schema(description = "Max labor hours reimbursed") @DecimalMin("0")
        BigDecimal laborHoursCap,

        @Schema(description = "Max labor rate reimbursed") @DecimalMin("0")
        BigDecimal laborRateCap,

        @Schema(description = "How the credit fraction is computed") @NotNull
        ProrationMethod prorationMethod,

        @Schema(description = "Provider demands the defective part back (drives RMA); defaults to false")
        Boolean requiresPartReturn,

        @Schema(description = "Intake enforces at least one photo before submission; defaults to false")
        Boolean requiresPhotoEvidence,

        @Schema(description = "Coverage survives vehicle/owner change; defaults to false")
        Boolean transferable,

        @Schema(
                description = "Auto-create a registration when a listed product is sold"
                        + " (invoiced workorder); only effective with appliesToType=PRODUCT_LIST; defaults to false")
        Boolean autoRegister,

        @Schema(description = "Human-readable fine print") String termsText,

        @Schema(description = "Links to the provider's policy documents")
        List<@NotBlank String> documentUrls) {}
