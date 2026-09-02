package com.positivity.catalog.internal.spi.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One labor-time line inside a feed chunk. Vehicle-key fields follow the null-as-wildcard
 * convention; included operations arrive already normalized to Durion codes by the adapter.
 *
 * @param providerOperationCode vendor operation code, mapped via {@code service_operation_xref}
 * @param vehicleYear year or range; null = any
 * @param make vehicle make; null = any
 * @param model vehicle model; null = any
 * @param submodel submodel/trim; null = any
 * @param engineCode engine code; null = any
 * @param hours decimal hours in tenths
 * @param timeType time class, normalized to {@code LaborTimeType} names
 * @param overlapGroup shared-setup group, if published
 * @param includedOperations Durion operation codes included in this time
 * @param publishedAt vendor publication date, if stated
 */
public record ProviderFeedLine(
        @NonNull String providerOperationCode,
        @Nullable String vehicleYear,
        @Nullable String make,
        @Nullable String model,
        @Nullable String submodel,
        @Nullable String engineCode,
        @NonNull BigDecimal hours,
        @NonNull String timeType,
        @Nullable String overlapGroup,
        @NonNull List<String> includedOperations,
        @Nullable LocalDate publishedAt) {}
