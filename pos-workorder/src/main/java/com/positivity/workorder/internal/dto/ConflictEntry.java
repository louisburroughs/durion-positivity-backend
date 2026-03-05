package com.positivity.workorder.internal.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ConflictEntry {
    String conflictType;
    String severity;
    String message;
    String affectedResourceId;
    String affectedWorkorderId;
}