package com.positivity.workorder.internal.dto.pick;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfirmPickLineRequest {
  @NotNull
  @Positive
  private Integer quantityPicked;
}
