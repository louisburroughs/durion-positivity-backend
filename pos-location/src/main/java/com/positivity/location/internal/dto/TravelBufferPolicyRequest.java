package com.positivity.location.internal.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for travel buffer policy endpoints.
 *
 * Issue: #76
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class TravelBufferPolicyRequest {

    private String name;
    private String bufferType;
    private BigDecimal bufferValue;
    private String notes;
}
