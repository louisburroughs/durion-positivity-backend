package com.positivity.bulkloader.internal.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.Data;

@Data
public class ColumnMappingApproveRequest {

    @NotEmpty
    @Valid
    private List<ColumnMappingUpdateRequest> mappings;
}
