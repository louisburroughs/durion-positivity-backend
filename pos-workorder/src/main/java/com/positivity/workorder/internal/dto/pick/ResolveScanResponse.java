package com.positivity.workorder.internal.dto.pick;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResolveScanResponse {
  private UUID pickTaskId;
  private UUID pickListId;
  private UUID resolvedSkuId;
  private UUID resolvedLocationId;
  private boolean matched;
  private String matchStatus;
}
