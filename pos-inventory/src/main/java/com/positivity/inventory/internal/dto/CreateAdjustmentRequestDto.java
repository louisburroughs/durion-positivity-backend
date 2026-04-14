package com.positivity.inventory.internal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for creating a draft inventory adjustment request.
 *
 * Issue: CAP-215 Story #37
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateAdjustmentRequestDto {

    @NotBlank
    String productSku;

    @NotNull
    UUID locationId;

    @NotNull
    Integer quantity;

    /** Mandatory reason code explaining the adjustment. */
    @NotBlank
    String reasonCode;

    String unitOfMeasure;
}
