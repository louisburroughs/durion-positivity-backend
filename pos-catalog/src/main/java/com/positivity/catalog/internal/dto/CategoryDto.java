package com.positivity.catalog.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Product category")
public class CategoryDto {

    @Schema(description = "Category identifier", example = "0196cf6f-c8dd-7ee0-93e7-f48a5698a535")
    private UUID id;

    @Schema(description = "Category name", example = "Tools")
    private String name;
}
