package com.positivity.catalog.internal.dto;

import com.positivity.catalog.internal.enums.ProductStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProductStatusUpdateRequestDto {

    @NotNull
    private ProductStatus status;
}
