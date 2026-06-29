package com.positivity.inventory.internal.dto.asn;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Data;

@Schema(description = "Request payload to create an advance shipping notice (ASN) for an inbound vendor shipment")
@Data
public class CreateAsnRequest {

    @Schema(
            description = "Identifier of the vendor that issued the shipment",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID vendorId;

    @Schema(
            description = "Human-readable reference number assigned to the ASN by the vendor",
            example = "ASN-2026-000123",
            requiredMode = REQUIRED)
    @NotNull
    @NotBlank
    private String asnReferenceNumber;

    @Schema(
            description = "Identifiers of the purchase orders this shipment relates to",
            example = "[\"01960003-0000-7000-8000-000000000002\"]",
            requiredMode = REQUIRED)
    @NotNull
    @NotEmpty
    private List<UUID> relatedPoIds;

    @Schema(description = "Date the shipment left the vendor", example = "2026-01-15", requiredMode = NOT_REQUIRED)
    private LocalDate shipDate;

    @Schema(
            description = "Expected arrival date of the shipment at the destination",
            example = "2026-01-20",
            requiredMode = NOT_REQUIRED)
    private LocalDate expectedArrivalDate;

    @Schema(description = "Line items detailing the SKUs and quantities included in this ASN", requiredMode = REQUIRED)
    @NotNull
    private List<@Valid CreateAsnLineRequest> lineItems;
}
