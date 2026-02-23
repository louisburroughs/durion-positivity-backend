package com.positivity.location.internal.dto;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lightweight location reference payload for reconciliation roster consumers.
 *
 * Issue: CAP-214 #40
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LocationRef {

    private UUID id;
    private String name;
    private String code;
    private String status;
    private String hrLocationId;
    private Instant updatedAt;
}
