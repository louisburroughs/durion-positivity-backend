package com.positivity.workorder.internal.dto;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class PtoEntry {
    String ptoId;
    Instant start;
    Instant end;
    String ptoType;
}
