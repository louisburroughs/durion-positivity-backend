package com.positivity.inventory.internal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
public class ShortageResolveRequest {
    @NotNull
    private UUID allocationId;

    @Nullable
    private UUID allocationLineId;

    @NotBlank
    private String resolution;

    @Nullable
    private String substituteSku;

    @Nullable
    private String notes;
}
