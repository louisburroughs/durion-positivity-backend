package com.positivity.workorder.internal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StartWorkorderResponse {
    private UUID workorderId;
    private String previousStatus;
    private String currentStatus;
    private Instant transitionedAt;
    private String message;
}
