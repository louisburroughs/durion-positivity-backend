package com.positivity.workorder.dto;

import com.positivity.workorder.entity.WorkOrderStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompleteWorkOrderResponse {
    private Long workOrderId;
    private WorkOrderStatus previousStatus;
    private WorkOrderStatus currentStatus;
    private Instant completedAt;
    private String message;
}
