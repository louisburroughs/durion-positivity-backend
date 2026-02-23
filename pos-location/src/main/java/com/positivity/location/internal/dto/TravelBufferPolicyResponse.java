package com.positivity.location.internal.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response payload for travel buffer policy endpoints.
 *
 * Issue: #76
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TravelBufferPolicyResponse {

    private UUID id;
    private String name;
    private String bufferType;
    private BigDecimal bufferValue;
    private String notes;
    private Instant createdAt;
    private Instant updatedAt;
}
