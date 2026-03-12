package com.positivity.mcp.internal.dto;

import java.util.UUID;

public record ClarificationResponseDTO(
        UUID intentId,
        String selectedOption,
        String userAnswer) {
}
