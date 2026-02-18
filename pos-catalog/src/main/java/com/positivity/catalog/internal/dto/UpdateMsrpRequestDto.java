package com.positivity.catalog.internal.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Data;

@Data
public class UpdateMsrpRequestDto {

    @NotNull
    private BigDecimal amount;

    @NotNull
    private String currency;

    @NotNull
    private LocalDate effectiveStartDate;

    private LocalDate effectiveEndDate;

    @NotNull
    private UUID createdByUserId;

    private Long version;
}
