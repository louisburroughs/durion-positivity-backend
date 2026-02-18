package com.positivity.catalog.internal.dto;

import java.util.UUID;
import lombok.Data;

@Data
public class LocationPriceOverrideDecisionRequestDto {
    private Long version;
    private UUID actorUserId;
    private String rejectionReasonCode;
    private String rejectionNotes;
}
