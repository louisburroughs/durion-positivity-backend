package com.positivity.workorder.internal.dto;

import lombok.Data;

@Data
public class EmergencyOverrideDTO {
    private Long managerId;
    private String exceptionReason;
}
