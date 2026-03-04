package com.positivity.workorder.internal.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class BayStatus {
    String bayId;
    String bayName;
    String status;
    String assignedWorkorderId;
    boolean available;
}