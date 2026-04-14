package com.positivity.workorder.internal.event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkCompletedEvent {
    private String eventId;
    private String eventType;
    private Instant eventTimestamp;
    private String sourceDomain;
    private String idempotencyKey;
    private WorkCompletedPayload payload;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WorkCompletedPayload {
        private UUID workorderId;
        private Instant completedAt;
        private String completedBy;
        private Map<String, Object> finalBillableScope;
    }
}
