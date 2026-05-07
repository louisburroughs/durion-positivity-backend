package com.positivity.accounting.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A single field definition in an event envelope contract")
public class ContractField {

    @Schema
    private String name;

    @Schema
    private String jsonPath;

    @Schema
    private String type;

    @Schema
    private boolean required;

    @Schema
    private String description;

    @Nullable
    @Schema(nullable = true)
    private List<String> enumValues;
}
