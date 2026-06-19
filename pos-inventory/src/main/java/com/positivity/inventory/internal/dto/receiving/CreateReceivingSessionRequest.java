package com.positivity.inventory.internal.dto.receiving;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to open a new inventory receiving session against a source document")
public class CreateReceivingSessionRequest {
    @Schema(
            description = "Identifier of the source document (e.g. purchase order or workorder) being received against",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotBlank(message = "sourceDocumentId is required")
    private String sourceDocumentId;

    @Schema(
            description = "Method used to enter receiving lines, such as SCAN or MANUAL",
            example = "MANUAL",
            requiredMode = NOT_REQUIRED)
    private String entryMethod;
}
