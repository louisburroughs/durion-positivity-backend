package com.positivity.bulkloader.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to submit corrected data records for a failed bulk load job")
public class BulkCorrectionRequest {

  @Valid
  @NotEmpty
  @Schema(description = "List of correction items, one per audit record to correct", requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED)
  private List<BulkCorrectionItem> corrections;
}
