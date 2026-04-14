package com.positivity.people.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class TimeEntryExceptionResolveRequest {

    @Schema(description = "Notes explaining the resolution", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String resolutionNotes;
}
