package com.positivity.inventory.internal.dto;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShortageOptionDto {
  private UUID allocationId;
  @Nullable
  private UUID allocationLineId;
  private String resolution;
  private String description;
  @Nullable
  private String substituteSku;
  private int estimatedDays;
}