package com.positivity.inventory.internal.dto.reallocation;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReallocateRequest {

    @NotNull
    private UUID stockItemId;

    private String triggerType;

    private String triggerReferenceId;
}
