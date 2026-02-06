package com.positivity.workorder.internal.dto;

import java.util.UUID;

import lombok.Data;

@Data
public class EmergencyOverrideDTO {
    private UUID managerId;
    private String exceptionReason;
}
