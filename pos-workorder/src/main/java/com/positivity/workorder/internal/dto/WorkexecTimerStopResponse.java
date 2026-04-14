package com.positivity.workorder.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Stop timer response containing all stopped timers")
public class WorkexecTimerStopResponse {

    @JsonProperty("stopped")
    @Schema(description = "Stopped timer entries")
    private List<WorkexecTimerEntryResponse> stopped;
}
