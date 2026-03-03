package com.positivity.workorder.internal.dto;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload for stopping a work session")
public class StopWorkSessionRequest {

    @Nullable
    @Schema(description = "Optional mechanic ID for disambiguation when multiple sessions could match")
    private UUID mechanicId;
}
