package com.positivity.mcp.internal.dto;

import java.util.List;
import java.util.UUID;

public record IntentV1(
        UUID intentId,
        String intentType,
        String status,
        String riskLevel,
        List<IntentSlot> slots,
        List<ClarificationQuestion> clarificationQuestions) {}
