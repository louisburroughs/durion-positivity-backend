package com.positivity.workorder.internal.dto;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkorderStartResponse {
    private UUID workorderId;
    private String operationalContextVersion;
    private Instant workStartedAt;
    private String previousStatus;
    private String currentStatus;
    private Instant transitionedAt;
    private String message;
}
