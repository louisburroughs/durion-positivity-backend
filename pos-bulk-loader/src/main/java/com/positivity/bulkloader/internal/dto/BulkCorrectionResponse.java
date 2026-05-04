package com.positivity.bulkloader.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Result of a bulk correction submission")
public class BulkCorrectionResponse {

  @Schema(description = "The bulk load job ID", example = "00000000-0000-0000-0000-000000000001")
  private UUID jobId;

  @Schema(description = "Total number of corrections submitted", example = "5")
  private int submittedCount;

  @Schema(description = "Number of corrections accepted for processing", example = "5")
  private int acceptedCount;

  @Schema(description = "Number of corrections rejected", example = "0")
  private int rejectedCount;

  @Schema(description = "Rejection detail messages for each rejected correction, if any")
  private List<String> rejections;
}
