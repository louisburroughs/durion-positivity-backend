package com.positivity.inventory.internal.dto;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LocationZoneDto {
  private UUID zoneId;
  private UUID locationId;
  private String zoneName;
  private String description;
}