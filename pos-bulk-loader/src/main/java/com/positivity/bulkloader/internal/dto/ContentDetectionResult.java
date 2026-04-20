package com.positivity.bulkloader.internal.dto;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.Map;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ContentDetectionResult {

    private DomainType detectedDomain;
    private double confidence;
    private Map<String, String> suggestedColumnMappings;
    private boolean llmFallbackUsed;
}
