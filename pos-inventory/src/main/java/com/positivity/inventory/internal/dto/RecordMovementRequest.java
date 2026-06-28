package com.positivity.inventory.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.enums.MovementType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
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
@Schema(description = "Request body for recording a directional stock movement in the inventory ledger")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecordMovementRequest {

    @Schema(
            description = "Stock keeping unit code of the product being moved",
            example = "SKU-10042",
            requiredMode = REQUIRED)
    @NotBlank
    String productSku;

    @Schema(
            description = "Identifier of the location the stock moves from",
            example = "01960003-0000-7000-8000-000000000130",
            requiredMode = REQUIRED)
    @NotNull
    UUID fromLocationId;

    /** Destination location — required for TRANSFER movements. */
    @Schema(
            description = "Identifier of the destination location; required for TRANSFER movements",
            example = "01960003-0000-7000-8000-000000000131",
            requiredMode = NOT_REQUIRED)
    UUID toLocationId;

    @NotNull
    @Schema(
            description =
                    "Direct ledger movement type. ADJUST is workflow-only and must use /v1/inventory/adjustments endpoints.",
            example = "RECEIVE",
            requiredMode = REQUIRED,
            allowableValues = {"RECEIVE", "PUT_AWAY", "PICK", "ISSUE", "RETURN", "TRANSFER"})
    MovementType movementType;

    @Schema(description = "Quantity of units being moved", example = "12", requiredMode = REQUIRED)
    @NotNull
    @Positive
    Integer quantity;

    @Schema(description = "Unit of measure for the quantity", example = "EACH", requiredMode = NOT_REQUIRED)
    String unitOfMeasure;

    /** Optional reference to originating system transaction. */
    @Schema(
            description = "Reference to the originating system transaction that caused the movement",
            example = "01960003-0000-7000-8000-000000000132",
            requiredMode = NOT_REQUIRED)
    String sourceTransactionId;

    @AssertTrue(message = "toLocationId is required for TRANSFER movements")
    boolean isTransferDestinationPresent() {
        if (movementType == null || movementType != MovementType.TRANSFER) {
            return true;
        }
        return toLocationId != null;
    }

    @AssertTrue(message = "movementType ADJUST must use adjustment workflow endpoints")
    boolean isDirectMovementTypeAllowed() {
        return movementType == null || movementType != MovementType.ADJUST;
    }
}
