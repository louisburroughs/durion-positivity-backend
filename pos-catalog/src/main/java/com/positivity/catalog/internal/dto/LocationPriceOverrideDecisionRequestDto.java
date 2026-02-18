package com.positivity.catalog.internal.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.Data;

@Data
public class LocationPriceOverrideDecisionRequestDto {
    @NotNull
    private Long version;

    @NotNull
    private UUID actorUserId;

    private String rejectionReasonCode;
    private String rejectionNotes;
}
