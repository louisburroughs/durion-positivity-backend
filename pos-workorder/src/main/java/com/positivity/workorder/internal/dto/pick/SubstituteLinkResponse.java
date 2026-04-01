package com.positivity.workorder.internal.dto.pick;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.positivity.workorder.internal.enums.SubstituteType;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class SubstituteLinkResponse {
    private UUID id;
    private UUID productId;
    private UUID substitutePartId;
    private SubstituteType substituteType;
    private int priority;
    @JsonProperty("isAutoSuggest")
    private boolean isAutoSuggest;
    @JsonProperty("isActive")
    private boolean isActive;
    private int version;
    private String createdBy;
    private String updatedBy;
}
