package com.positivity.accounting.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Event published when vendor invoice is received.
 * Triggers three-way match against existing vendor bill.
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/130">Issue
 *      #130</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Vendor invoice received event from AP system")
public class VendorInvoiceReceivedEvent {

    @NotNull
    @Schema(description = "Event UUID (idempotency key)", example = "01936e5d-5678-7a3d-8b6e-456789012345")
    @JsonProperty("eventId")
    private UUID eventId;

    @NotNull
    @Schema(description = "Organization UUID", example = "00000000-0000-4000-a000-000000000010")
    @JsonProperty("organizationId")
    private UUID organizationId;

    @NotNull
    @Schema(description = "Vendor UUID", example = "01936e5a-7890-7a3d-8b6e-2b3456789012")
    @JsonProperty("vendorId")
    private UUID vendorId;

    @NotBlank
    @Schema(description = "Vendor invoice number", example = "INV-2026-001234")
    @JsonProperty("invoiceReference")
    private String invoiceReference;

    @NotNull
    @Schema(description = "Invoice date", example = "2026-01-16T00:00:00")
    @JsonProperty("invoiceDate")
    private LocalDateTime invoiceDate;

    @Nullable
    @Schema(description = "Invoice due date", example = "2026-02-15T00:00:00")
    @JsonProperty("dueDate")
    private LocalDateTime dueDate;

    @NotNull
    @Schema(description = "Invoice line items")
    @JsonProperty("lineItems")
    private List<InvoiceLineItem> lineItems;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Invoice line item from vendor")
    public static class InvoiceLineItem {

        @NotNull
        @Schema(description = "Product/SKU UUID", example = "01936e59-abcd-7a3d-8b6e-3c4567890123")
        @JsonProperty("productId")
        private UUID productId;

        @NotBlank
        @Schema(description = "Product description", example = "Widget Type A")
        @JsonProperty("description")
        private String description;

        @NotNull
        @Positive
        @Schema(description = "Invoiced quantity", example = "100.00")
        @JsonProperty("quantity")
        private BigDecimal quantity;

        @NotNull
        @Positive
        @Schema(description = "Unit price from invoice", example = "12.50")
        @JsonProperty("unitPrice")
        private BigDecimal unitPrice;
    }
}
