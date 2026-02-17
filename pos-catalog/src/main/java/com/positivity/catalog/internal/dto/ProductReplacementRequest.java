package com.positivity.catalog.internal.dto;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import java.util.UUID;

@Getter
@Setter
public class ProductReplacementRequest {
    private UUID replacementProductId;
    private Integer priorityOrder;
    private String notes;
    private Instant effectiveAt;
}
