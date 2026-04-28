package com.positivity.inventory.internal.dto;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShortageResolutionResultDto {
  private UUID allocationId;
  private String resolution;
  private Instant resolvedAt;
  private String status;
}