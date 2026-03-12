package com.positivity.mcp.internal.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;
import java.util.UUID;

public record NltiRequestDTO(
        @NotBlank String prompt,
        UUID sessionId,
        Map<String, Object> clientContext) {
}
