package com.positivity.inventory.dto.reallocation;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReallocateRequest {

    @NotBlank
    private String sku;

    private String triggerType;

    private String triggerReferenceId;
}