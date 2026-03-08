package com.positivity.order.internal.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class AddItemRequest {

    @NotBlank
    private String itemSku;

    @Min(1)
    private int quantity;

    private String reasonCode;

    private BigDecimal manualPrice;
}
