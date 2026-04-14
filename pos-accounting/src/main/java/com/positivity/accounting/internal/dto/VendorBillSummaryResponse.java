package com.positivity.accounting.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Summary of a vendor bill (for bill selection/eligibility lists).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Vendor bill summary for payment allocation")
public class VendorBillSummaryResponse {

    @Schema(description = "Vendor bill UUID", example = "01936e5d-1234-7a3d-8b6e-3c4567890123")
    @JsonProperty("vendorBillId")
    private UUID vendorBillId;

    @Schema(description = "Vendor UUID", example = "01936e5b-4567-7a3d-8b6e-1a2345678901")
    @JsonProperty("vendorId")
    private UUID vendorId;

    @Nullable
    @Schema(description = "Vendor name", example = "Acme Supplies Ltd")
    @JsonProperty("vendorName")
    private String vendorName;

    @Schema(description = "Bill number", example = "INV-2026-001234")
    @JsonProperty("billNumber")
    private String billNumber;

    @Nullable
    @Schema(description = "Bill date", example = "2026-01-15T00:00:00")
    @JsonProperty("billDate")
    private LocalDateTime billDate;

    @Nullable
    @Schema(description = "Due date", example = "2026-02-14T00:00:00")
    @JsonProperty("dueDate")
    private LocalDateTime dueDate;

    @Schema(description = "Total bill amount", example = "1200.00")
    @JsonProperty("totalAmount")
    private BigDecimal totalAmount;

    @Schema(description = "Open/unpaid amount (eligible for payment)", example = "1200.00")
    @JsonProperty("openAmount")
    private BigDecimal openAmount;

    @Schema(description = "Bill status", example = "APPROVED")
    @JsonProperty("status")
    private VendorBillStatus status;
}
