package com.positivity.inventory.internal.dto;

import com.positivity.inventory.internal.enums.MovementType;
import jakarta.validation.constraints.AssertTrue;
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
 * <p>
 * The request uses directional fields (`fromLocationId`, `toLocationId`) while
 * ledger entries persist both directional metadata and a posting bucket
 * (`locationId`). For example, a TRANSFER_IN entry posts to
 * `locationId=toLocationId`, so those two values intentionally match for that
 * row.
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
    String fromLocationId;

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

    @AssertTrue(message = "toLocationId is required for TRANSFER movements")
    boolean isTransferDestinationPresent() {
        if (movementType == null || movementType != MovementType.TRANSFER) {
            return true;
        }
        return toLocationId != null && !toLocationId.isBlank();
    }
}
