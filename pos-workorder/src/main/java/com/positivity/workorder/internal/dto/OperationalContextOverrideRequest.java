package com.positivity.workorder.internal.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OperationalContextOverrideRequest {
    @NotNull
    private UUID locationId;
    private String bayId;
    private List<UUID> assignedMechanics;
    private List<UUID> assignedResources;
    private List<String> constraints;
}