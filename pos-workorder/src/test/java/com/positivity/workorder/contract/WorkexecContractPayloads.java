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

    static Map<String, Object> timerStartOnBehalfPayload(UUID workorderId, UUID technicianId, String reason) {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("workorderId", workorderId.toString());
        payload.put("technicianId", technicianId.toString());
        if (reason != null) {
            payload.put("reason", reason);
        }
        return payload;
    }
}
