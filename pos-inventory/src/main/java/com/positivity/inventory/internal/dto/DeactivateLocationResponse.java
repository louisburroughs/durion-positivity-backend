package com.positivity.inventory.internal.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public class DeactivateLocationResponse {
    private UUID sourceLocationId;
    private UUID destinationLocationId;
    private String status; // Inactive
    private Transfer transfer;

    public static class Transfer {
        private List<MovedItem> movedItems;
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

    public static class MovedItem {
        private String itemId;
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
