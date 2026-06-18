package com.positivity.inventory.internal.dto.receiving;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to record received quantities for one or more receiving session lines")
public class ReceiveItemsRequest {
    @Schema(
            description = "Receiving lines with their received quantities; at least one line is required",
            requiredMode = REQUIRED)
    @NotEmpty(message = "At least one line must be provided")
    @Valid
    private List<ReceiveLineRequest> lines;
}
