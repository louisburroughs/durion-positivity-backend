package com.positivity.mcp.internal.dto;

import java.util.List;

public record ClarificationQuestion(
        String text,
        List<String> options) {
}
