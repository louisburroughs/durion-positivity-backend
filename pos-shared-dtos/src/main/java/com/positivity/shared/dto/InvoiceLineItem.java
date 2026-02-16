package com.positivity.shared.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;

import java.math.BigDecimal;

/**
 * Line item DTO used for invoice generation payloads and responses.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceLineItem {

    @NonNull
    private String description;

    @NonNull
    private BigDecimal quantity;

    @NonNull
    private BigDecimal unitPrice;

    @NonNull
    private BigDecimal amount;
}
