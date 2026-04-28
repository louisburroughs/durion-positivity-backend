package com.positivity.inventory.internal.dto.returns;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
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
public class ReturnSubmitRequest {
  @NotNull
  private UUID workorderId;
  @Valid
  @NotEmpty
  private List<ReturnLineDto> lines;
}