package com.positivity.inventory.internal.dto.asn;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.enums.AsnStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Schema(description = "Advance shipping notice (ASN) representing an inbound shipment from a vendor, including its line items and lifecycle status")
@Data
@Builder
public class AsnResponse {

    @Schema(
            description = "Unique identifier of the ASN",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID asnId;

    @Schema(
            description = "Human-readable reference number assigned to the ASN",
            example = "ASN-2026-000123",
            requiredMode = REQUIRED)
    @NotNull
    private String asnReferenceNumber;

    @Schema(
            description = "Identifier of the vendor that issued the shipment",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private UUID vendorId;

    @Schema(
            description = "Current lifecycle status of the ASN",
            example = "READY_FOR_RECEIPT",
            requiredMode = REQUIRED)
    @NotNull
    private AsnStatus status;

    @Schema(
            description = "Date the shipment left the vendor",
            example = "2026-01-15",
            requiredMode = NOT_REQUIRED)
    private LocalDate shipDate;

    @Schema(
            description = "Expected arrival date of the shipment at the destination",
            example = "2026-01-20",
            requiredMode = NOT_REQUIRED)
    private LocalDate expectedArrivalDate;

    @Schema(
            description = "Identifier of the user who created the ASN",
            example = "user-jdoe",
            requiredMode = NOT_REQUIRED)
    private String createdBy;

    @Schema(
            description = "Timestamp when the ASN was created",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private Instant createdAt;

    @Schema(
            description = "Line items detailing the SKUs and quantities included in this ASN",
            requiredMode = NOT_REQUIRED)
    private List<AsnLineResponse> lineItems;
}
