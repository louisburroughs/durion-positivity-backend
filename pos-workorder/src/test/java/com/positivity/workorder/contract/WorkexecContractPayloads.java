package com.positivity.workorder.contract;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Shared payload builders for workexec contract tests.
 */
final class WorkexecContractPayloads {

    private WorkexecContractPayloads() {}

    static Map<String, Object> laborPerformedPayload(
            UUID workorderId, UUID technicianId, BigDecimal quantity, String sourceReferenceId) {
        return Map.of(
                "workorderId", workorderId.toString(),
                "technicianId", technicianId.toString(),
                "performedAt", "2026-02-16T15:00:00Z",
                "labor", Map.of("quantity", quantity, "unit", "HOURS"),
                "source", Map.of("system", "people", "sourceReferenceId", sourceReferenceId));
    }

    static Map<String, Object> timerStartPayload(UUID workorderId) {
        return Map.of("workorderId", workorderId.toString());
    }

    static Map<String, Object> timerStartPayload(UUID workorderId, String laborCode) {
        return Map.of("workorderId", workorderId.toString(), "laborCode", laborCode);
    }
}
