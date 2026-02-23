package com.positivity.location.internal.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for creating bays.
 *
 * Issue: CAP-136 #77
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class BayRequest {

    @NotBlank
    private String name;

    @NotBlank
    private String bayType;

    @NotNull
    @Valid
    private BayCapacityRequest capacity;

    private Integer maxConcurrentVehicles;
    private List<String> serviceCapabilityIds;
    private List<String> skillRequirementIds;
    private String status;
}
