package com.positivity.catalog.internal.dto;

import com.positivity.catalog.internal.entity.PriceBookScope;
import com.positivity.catalog.internal.entity.PriceBookStatus;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Data;

@Data
public class PriceBookDto {
    private UUID priceBookId;
    private String name;
    private PriceBookScope scope;
    private UUID scopeId;
    private boolean isDefault;
    private PriceBookStatus status;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Long version;
}
