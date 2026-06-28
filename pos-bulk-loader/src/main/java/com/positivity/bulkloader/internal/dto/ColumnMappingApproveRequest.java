package com.positivity.bulkloader.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.Data;

@Data
@Schema(description = "Request to approve the finalized set of column mappings for a bulk load job")
public class ColumnMappingApproveRequest {

    @NotEmpty
    @Valid
    @Schema(description = "Column mappings to approve, one per source column", requiredMode = REQUIRED)
    private List<ColumnMappingUpdateRequest> mappings;
}
