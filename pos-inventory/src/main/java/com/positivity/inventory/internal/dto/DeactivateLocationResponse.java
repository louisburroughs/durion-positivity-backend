package com.positivity.inventory.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Schema(description = "Response describing the outcome of deactivating a location and any inventory transfer performed")
public class DeactivateLocationResponse {

    @Schema(
            description = "Identifier of the location that was deactivated",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID sourceLocationId;

    @Schema(
            description = "Identifier of the destination location inventory was moved to, if any",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    private UUID destinationLocationId;

    @Schema(
            description = "Resulting status of the source location after deactivation",
            example = "Inactive",
            requiredMode = REQUIRED)
    @NotNull
    private String status; // Inactive

    @Schema(
            description = "Details of the inventory transfer performed during deactivation, if any",
            requiredMode = NOT_REQUIRED)
    private Transfer transfer;

    @Schema(description = "Details of inventory moved from the deactivated location to the destination location")
    public static class Transfer {

        @Schema(description = "Items moved as part of the transfer", requiredMode = NOT_REQUIRED)
        private List<MovedItem> movedItems;

        @Schema(
                description = "Timestamp when the inventory was moved",
                example = "2026-01-15T09:30:00Z",
                requiredMode = NOT_REQUIRED)
        private OffsetDateTime movedAt;

        public List<MovedItem> getMovedItems() {
            return movedItems;
        }

        public void setMovedItems(List<MovedItem> movedItems) {
            this.movedItems = movedItems;
        }

        public OffsetDateTime getMovedAt() {
            return movedAt;
        }

        public void setMovedAt(OffsetDateTime movedAt) {
            this.movedAt = movedAt;
        }
    }

    @Schema(description = "A single inventory item moved during location deactivation")
    public static class MovedItem {

        @Schema(
                description = "Identifier of the moved inventory item",
                example = "SKU-10042",
                requiredMode = REQUIRED)
        @NotNull
        private String itemId;

        @Schema(description = "Quantity of the item moved", example = "4.5", requiredMode = REQUIRED)
        private double quantity;

        public String getItemId() {
            return itemId;
        }

        public void setItemId(String itemId) {
            this.itemId = itemId;
        }

        public double getQuantity() {
            return quantity;
        }

        public void setQuantity(double quantity) {
            this.quantity = quantity;
        }
    }

    public UUID getSourceLocationId() {
        return sourceLocationId;
    }

    public void setSourceLocationId(UUID sourceLocationId) {
        this.sourceLocationId = sourceLocationId;
    }

    public UUID getDestinationLocationId() {
        return destinationLocationId;
    }

    public void setDestinationLocationId(UUID destinationLocationId) {
        this.destinationLocationId = destinationLocationId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Transfer getTransfer() {
        return transfer;
    }

    public void setTransfer(Transfer transfer) {
        this.transfer = transfer;
    }
}
