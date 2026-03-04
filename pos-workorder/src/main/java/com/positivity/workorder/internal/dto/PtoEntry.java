package com.positivity.workorder.internal.dto;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

@Value
@Builder
public class PtoEntry {
    String ptoId;
    Instant start;
    Instant end;
    String ptoType;
}