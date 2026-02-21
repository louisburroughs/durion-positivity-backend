package com.positivity.workorder.internal.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StartWorkorderRequest {
    @Schema(description = "Deprecated. Actor identity is resolved from authenticated security context.", deprecated = true)
    private UUID userId;
    private String reason;
}
