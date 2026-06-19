package com.positivity.inventory.internal.dto.returns;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "Request to submit a set of return lines against a workorder")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReturnSubmitRequest {
    @Schema(
            description = "Identifier of the workorder the return is submitted against",
            example = "01960003-0000-7000-8000-00000000000c",
            requiredMode = REQUIRED)
    @NotNull
    private UUID workorderId;

    @Schema(description = "Return lines describing each item, quantity and reason", requiredMode = REQUIRED)
    @Valid
    @NotEmpty
    private List<ReturnLineDto> lines;
}
