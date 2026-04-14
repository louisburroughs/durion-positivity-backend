package com.positivity.workorder.internal.dto;

import com.positivity.workorder.internal.enums.SubstituteType;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateSubstituteLinkRequest {
    private UUID productId;
    private UUID substitutePartId;
    private SubstituteType substituteType;
    private Integer priority;
    private Boolean isAutoSuggest;
    private Instant effectiveFrom;
    private Instant effectiveTo;
}
