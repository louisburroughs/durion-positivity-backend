package com.positivity.catalog.internal.spi.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One published time from a provider's live lookup (sourcing plan §3.3). Included operations are
 * expressed in <em>Durion</em> operation codes by the adapter (the normalization boundary), so
 * downstream summation never handles vendor code spaces.
 *
 * @param providerOperationCode the vendor code the time answers
 * @param hours decimal hours in tenths
 * @param timeType vendor time class, normalized to {@code LaborTimeType} names
 * @param includedOperations Durion operation codes whose time this one already includes
 * @param overlapGroup shared-setup group, if the vendor publishes one
 * @param sourceRevision the vendor revision/vintage the time came from
 * @param publishedAt the vendor's publication date, if stated
 * @param notes vendor remarks worth surfacing at point of use
 */
public record ProviderLaborTime(
        @NonNull String providerOperationCode,
        @NonNull BigDecimal hours,
        @NonNull String timeType,
        @NonNull List<String> includedOperations,
        @Nullable String overlapGroup,
        @NonNull String sourceRevision,
        @Nullable LocalDate publishedAt,
        @Nullable String notes) {}
