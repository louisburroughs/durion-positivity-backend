package com.positivity.inventory.internal.dto.returns;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
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
public class ReturnLineDto {
  @NotNull
  private UUID itemId;
  @Positive
  private int quantity;
  @NotBlank
  private String reasonCode;
  @NotNull
  private UUID locationId;
  @Nullable
  private UUID storageLocationId;
}