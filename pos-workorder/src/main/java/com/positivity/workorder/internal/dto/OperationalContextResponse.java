package com.positivity.workorder.internal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OperationalContextResponse {
    private String version;
    private UUID locationId;
    private String bayId;
    private Instant scheduledStartAt;
    private Instant scheduledEndAt;
    private List<UUID> assignedMechanics;
    private List<UUID> assignedResources;
    private List<String> constraints;
    private boolean locked; // true once workStartedAt is set
}