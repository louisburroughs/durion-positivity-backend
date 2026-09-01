package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One vendor-bill row for the due-date-window list (Wave 2 E9, issue #1597).
 *
 * @see com.positivity.accounting.internal.service.VendorBillService#listByDueDateWindow
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One vendor bill in a due-date-window listing")
public class VendorBillListRow {

    @Schema(
            description = "Vendor bill identifier",
            example = "01936e5d-1234-7a3d-8b6e-3c4567890123",
            requiredMode = REQUIRED)
    @NotNull
    @JsonProperty("billId")
    private UUID billId;

    @Schema(
            description = "Vendor identifier",
            example = "01936e5b-4567-7a3d-8b6e-1a2345678901",
            requiredMode = REQUIRED)
    @NotNull
    @JsonProperty("vendorId")
    private UUID vendorId;

    @Nullable
    @Schema(
            description =
                    "Bill due date; bills matched by this endpoint always have a due date in the requested" + " window",
            example = "2026-02-14T00:00:00",
            requiredMode = NOT_REQUIRED)
    @JsonProperty("dueDate")
    private LocalDateTime dueDate;

    @Schema(description = "Total bill amount", example = "1200.00", requiredMode = REQUIRED)
    @NotNull
    @JsonProperty("amount")
    private BigDecimal amount;

    @Schema(description = "Bill lifecycle status", example = "APPROVED", requiredMode = REQUIRED)
    @NotNull
    @JsonProperty("status")
    private VendorBillStatus status;
}
