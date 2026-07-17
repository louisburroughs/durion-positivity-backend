package com.positivity.warranty.internal.dto;

import com.positivity.warranty.internal.enums.AppliesToType;
import com.positivity.warranty.internal.enums.CoverageType;
import com.positivity.warranty.internal.enums.ProrationMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** API representation of a warranty policy's structured coverage terms (PRD §3.2). */
@Schema(description = "Warranty policy — structured coverage terms")
public record PolicyResponse(
        UUID id,
        UUID providerId,
        String name,
        CoverageType coverageType,
        AppliesToType appliesToType,
        List<UUID> appliesToProductEntityIds,
        UUID appliesToCategoryId,
        UUID appliesToManufacturerId,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        Integer durationMonths,
        Integer mileageLimit,
        Integer treadPullPointThirtySeconds,
        Boolean laborCovered,
        BigDecimal laborHoursCap,
        BigDecimal laborRateCap,
        ProrationMethod prorationMethod,
        Boolean requiresPartReturn,
        Boolean requiresPhotoEvidence,
        Boolean transferable,
        Boolean autoRegister,
        String termsText,
        List<String> documentUrls,
        Instant createdAt,
        Instant updatedAt,
        Long version) {}
