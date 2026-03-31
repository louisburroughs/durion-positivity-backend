package com.positivity.workorder.internal.dto.pick;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResolveScanRequest {
  @NotNull
  private UUID scannedSkuId;

  @NotNull
  private UUID scannedLocationId;
}
