package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Describes the expected structure of accounting event envelopes")
public class EventEnvelopeContract {

    @Schema(description = "Contract schema version", example = "1.0", requiredMode = REQUIRED)
    private String version;

    @Schema(description = "Field definitions in the event envelope", requiredMode = REQUIRED)
    private List<ContractField> fields;

    @Schema(description = "Example event payloads", requiredMode = NOT_REQUIRED)
    private List<Object> examples;
}
