package com.positivity.inventory.internal.dto;

import com.positivity.inventory.internal.enums.MovementType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for recording a stock movement in the inventory ledger.
 *
 * Issue: CAP-215 Story #37
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecordMovementRequest {

    @NotBlank
    String productSku;

    @NotBlank
    String locationId;

    /** Destination location — required for TRANSFER movements. */
    String toLocationId;

    @NotNull
    MovementType movementType;

    @NotNull
    @Positive
    Integer quantity;

    String unitOfMeasure;

    /** Optional reference to originating system transaction. */
    String sourceTransactionId;
}
