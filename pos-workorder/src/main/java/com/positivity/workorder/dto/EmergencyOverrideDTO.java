package com.positivity.workorder.dto;

import lombok.Data;

@Data
public class EmergencyOverrideDTO {
    private Long managerId;
    private String exceptionReason;
}
