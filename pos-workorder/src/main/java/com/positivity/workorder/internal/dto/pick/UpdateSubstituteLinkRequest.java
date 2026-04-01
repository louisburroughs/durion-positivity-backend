package com.positivity.workorder.internal.dto.pick;

import com.positivity.workorder.internal.enums.SubstituteType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateSubstituteLinkRequest {
    private SubstituteType substituteType;
    private Integer priority;
    private Boolean isAutoSuggest;
    private Instant effectiveFrom;
    private Instant effectiveTo;
    private int version;
}
