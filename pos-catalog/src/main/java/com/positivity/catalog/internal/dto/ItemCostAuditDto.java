package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.catalog.internal.enums.ChangeSourceType;
import com.positivity.catalog.internal.enums.CostType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Item cost audit event")
public class ItemCostAuditDto {

    @Schema(
            description = "Audit event identifier",
            example = "0196cf6f-c8dd-7ee0-93e7-f48a5698a535",
            requiredMode = REQUIRED)
    private UUID auditId;

    @Schema(
            description = "Item identifier the audit event belongs to",
            example = "2c018664-dfea-46ef-a838-6b9dbf8f9b1d",
            requiredMode = REQUIRED)
    private UUID itemId;

    @Schema(description = "Timestamp the cost change occurred", example = "2026-01-15T09:30:00Z", requiredMode = REQUIRED)
    private Instant timestamp;

    @Schema(
            description = "Type of cost that changed",
            example = "STANDARD",
            implementation = CostType.class,
            requiredMode = REQUIRED)
    private CostType costTypeChanged;

    @Schema(description = "Previous cost value", example = "5.00", requiredMode = NOT_REQUIRED)
    private BigDecimal oldValue;

    @Schema(description = "New cost value", example = "6.25", requiredMode = NOT_REQUIRED)
    private BigDecimal newValue;

    @Schema(
            description = "Source that triggered the cost change",
            example = "MANUAL",
            implementation = ChangeSourceType.class,
            requiredMode = REQUIRED)
    private ChangeSourceType changeSourceType;

    @Schema(description = "Identifier of the change source", example = "PO-10045", requiredMode = NOT_REQUIRED)
    private String changeSourceId;

    @Schema(description = "Actor responsible for the change", example = "jane.doe", requiredMode = NOT_REQUIRED)
    private String actor;

    @Schema(description = "Reason code for the change", example = "PRICE_CORRECTION", requiredMode = NOT_REQUIRED)
    private String reasonCode;
}
