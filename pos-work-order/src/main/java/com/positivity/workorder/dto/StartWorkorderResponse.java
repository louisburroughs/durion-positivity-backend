package com.positivity.workorder.dto;

import com.positivity.workorder.entity.WorkorderStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StartWorkorderResponse {
    private Long workorderId;
    private WorkorderStatus previousStatus;
    private WorkorderStatus currentStatus;
    private Instant transitionedAt;
    private String message;
}
