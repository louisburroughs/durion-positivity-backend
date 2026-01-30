package com.positivity.workorder.internal.dto;

import com.positivity.workorder.internal.entity.WorkorderStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompleteWorkorderResponse {
    private Long workorderId;
    private WorkorderStatus previousStatus;
    private WorkorderStatus currentStatus;
    private Instant completedAt;
    private String message;
}
