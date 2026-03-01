package com.positivity.shopmanager.service.dto;

import com.positivity.shopmanager.service.enums.ConflictReasonCode;
import java.time.Instant;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ConflictBlock {
    Instant startTime;
    Instant endTime;
    ConflictReasonCode reasonCode;
    String detail;
}
