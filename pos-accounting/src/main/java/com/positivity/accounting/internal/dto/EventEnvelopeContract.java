package com.positivity.accounting.internal.dto;

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

  @Schema(description = "Contract schema version")
  private String version;

  @Schema(description = "Field definitions in the event envelope")
  private List<ContractField> fields;

  @Schema(description = "Example event payloads")
  private List<Object> examples;
}
