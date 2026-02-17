package com.positivity.catalog.internal.dto;

import com.positivity.catalog.internal.entity.ProductLifecycleState;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProductLifecycleUpdateRequest {
    private ProductLifecycleState lifecycleState;
    private Instant effectiveAt;
    private LocalDate effectiveDate;
    private UUID changedBy;
    private String overrideReason;
}
