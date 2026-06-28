package com.positivity.inventory.internal.dto.receiving;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Inventory receiving session with its lines and source document context")
public class ReceivingSessionResponse {
    @Schema(
            description = "Identifier of the receiving session",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID sessionId;

    @Schema(
            description = "Identifier of the source document the session receives against",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private String sourceDocumentId;

    @Schema(
            description = "Type of the source document, such as PURCHASE_ORDER or WORKORDER",
            example = "PURCHASE_ORDER",
            requiredMode = NOT_REQUIRED)
    private String sourceDocumentType;

    @Schema(
            description = "Identifier of the supplier providing the received stock",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = NOT_REQUIRED)
    private String supplierId;

    @Schema(
            description = "Carrier or shipment reference for the received goods",
            example = "SHIP-2026-00042",
            requiredMode = NOT_REQUIRED)
    private String shipmentReference;

    @Schema(
            description = "Status of the receiving session, such as IN_PROGRESS or COMPLETED",
            example = "IN_PROGRESS",
            requiredMode = REQUIRED)
    @NotNull
    private String status;

    @Schema(
            description = "Method used to enter receiving lines, such as SCAN or MANUAL",
            example = "MANUAL",
            requiredMode = NOT_REQUIRED)
    private String entryMethod;

    @Schema(
            description = "Identifier of the user who created the receiving session",
            example = "01960003-0000-7000-8000-000000000004",
            requiredMode = NOT_REQUIRED)
    private String createdByUserId;

    @Schema(
            description = "Timestamp when the receiving session was created",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private Instant createdAt;

    @Schema(description = "Receiving lines belonging to the session", requiredMode = NOT_REQUIRED)
    private List<ReceivingLineResponse> lines;
}
