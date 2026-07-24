package com.positivity.order.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Request payload for creating a return against a completed order")
public class CreateReturnRequest {

    @Schema(description = "The single COMPLETED order being returned against", requiredMode = REQUIRED)
    @NotNull
    private UUID originalOrderId;

    @Schema(
            description = "How the refund is issued",
            allowableValues = {"ORIGINAL_TENDER", "STORE_CREDIT", "ON_ACCOUNT_CREDIT"},
            requiredMode = REQUIRED)
    @NotBlank
    private String refundMethod;

    @Schema(description = "Return reason code", requiredMode = NOT_REQUIRED)
    private String reasonCode;

    @Schema(description = "Return lines", requiredMode = REQUIRED)
    @NotEmpty
    @Valid
    private List<ReturnLineRequest> lines;
}
