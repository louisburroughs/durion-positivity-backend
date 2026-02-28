package com.positivity.inventory.internal.dto.shortage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShortageResolutionRequest {
    @NotNull
    private UUID allocationId;

    @NotBlank
    private String sku;

    @Positive
    private Integer shortQuantity;
}