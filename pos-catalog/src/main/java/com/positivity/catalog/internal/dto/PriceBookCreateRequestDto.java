package com.positivity.catalog.internal.dto;

import com.positivity.catalog.internal.entity.PriceBookScope;
import com.positivity.catalog.internal.entity.PriceBookStatus;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.Data;

@Data
public class PriceBookCreateRequestDto {

    @NotNull
    private String name;

    @NotNull
    private PriceBookScope scope;

    private UUID scopeId;

    private Boolean isDefault;

    private PriceBookStatus status;
}
