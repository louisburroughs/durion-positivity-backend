package com.positivity.warranty.internal.dto;

import com.positivity.warranty.internal.enums.ProviderType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** API representation of a warranty provider (PRD §3.1). */
@Schema(description = "Warranty provider")
public record ProviderResponse(
        UUID id,
        String name,
        String status,
        ProviderType providerType,
        UUID apVendorId,
        UUID manufacturerId,
        String claimSubmissionMethod,
        String portalUrl,
        String contactName,
        String contactPhone,
        String contactEmail,
        Instant createdAt,
        Instant updatedAt,
        Long version) {}
