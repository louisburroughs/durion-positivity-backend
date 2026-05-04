package com.positivity.inventory.internal.dto.returns;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReasonCodeDto {
  private String code;
  private String description;
  private String category;
}