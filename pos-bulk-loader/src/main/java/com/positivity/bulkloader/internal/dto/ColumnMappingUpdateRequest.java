package com.positivity.bulkloader.internal.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import lombok.Data;

@Data
public class ColumnMappingUpdateRequest {

    private UUID mappingId;

    @NotBlank
    private String sourceColumn;

    @NotBlank
    private String targetField;
}
