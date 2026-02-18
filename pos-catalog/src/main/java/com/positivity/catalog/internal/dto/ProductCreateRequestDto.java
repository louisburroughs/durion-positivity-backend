package com.positivity.catalog.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "Create product request")
public class ProductCreateRequestDto {

    @NotBlank
    private String name;

    @NotBlank
    private String description;

    @NotBlank
    private String unitOfMeasure;

    private UUID manufacturerId;

    private UUID categoryId;

    @NotBlank
    private String sku;

    @NotBlank
    private String mpn;

    private String upc;

    @Schema(description = "JSON object represented as string")
    private String attributes;
}
