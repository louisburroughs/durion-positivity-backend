package com.positivity.inventory.internal.dto.shortage;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShortageResolutionRequest {
    @NotNull
    private UUID allocationId;

    @NotNull
    private String sku;

    private Integer shortQuantity;
}