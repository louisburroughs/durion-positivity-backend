package com.positivity.price.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Single estimate line item used for promotion and pricing calculations")
public class LineItemContext {

    @Schema(description = "Product SKU or identifier", requiredMode = Schema.RequiredMode.REQUIRED, example = "P001")
    @NotBlank(message = "sku is required")
    @Size(max = 120, message = "sku must not exceed 120 characters")
    private String sku;

    @Schema(description = "Line item quantity", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "quantity is required")
    @DecimalMin(value = "0", message = "quantity must be zero or greater")
    private BigDecimal quantity;

    @Schema(description = "Line item unit price", requiredMode = Schema.RequiredMode.REQUIRED, example = "100.00")
    @NotNull(message = "unitPrice is required")
    @DecimalMin(value = "0", message = "unitPrice must be zero or greater")
    private BigDecimal unitPrice;
}
