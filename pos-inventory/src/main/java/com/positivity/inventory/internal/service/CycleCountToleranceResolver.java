package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.CycleCountTolerance;
import com.positivity.inventory.internal.repository.CycleCountToleranceRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Resolves the tolerance that applies to a cycle count and decides whether a variance falls
 * inside it (ADR-0055 stage 4, #1416; issue #1414 owner spec).
 *
 * <h2>Scope precedence</h2>
 *
 * Most-specific wins: product+location, then product alone, then location alone, then the
 * global default (a row with both null), then — if no row matches at all — zero tolerance.
 *
 * <h2>Combining an absolute and a percentage bound</h2>
 *
 * The owner's spec allows tolerance to be configured "as an absolute quantity, percentage, or
 * both" but does not spell out how the two combine when both are set. This resolver interprets
 * "both" the way tank-tolerance policy conventionally works: the variance is accepted if it fits
 * under <em>either</em> bound — equivalently, the effective allowance is the larger of the two.
 * A drum tolerance of "±50 gal or ±1%, whichever is greater" is the ordinary real-world shape
 * this is modeling; a strict AND (must fit under both) would make the looser bound pointless to
 * configure. When only one bound is set, that bound alone governs. When neither is set, the
 * tolerance is zero — today's behavior, preserved as the default — and only an exact match (zero
 * variance) is accepted.
 */
@Component
@RequiredArgsConstructor
public class CycleCountToleranceResolver {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final int ALLOWANCE_SCALE = 4;

    private final CycleCountToleranceRepository repository;

    /** The resolved bounds, or {@link #NONE} when no row matched at any scope. */
    public record Resolution(
            @Nullable UUID toleranceId,
            @Nullable BigDecimal absoluteTolerance,
            @Nullable BigDecimal percentageTolerance) {

        public static final Resolution NONE = new Resolution(null, null, null);

        public boolean isConfigured() {
            return absoluteTolerance != null || percentageTolerance != null;
        }
    }

    /**
     * Resolves the tolerance for a product/location pair, trying scopes most-specific first.
     *
     * @param productId the catalog product being counted, or {@code null} when the stock
     *     reference resolves to no catalog product
     * @param storageLocation the task's bin/location as keyed ({@code null} or blank is treated
     *     as no location scope)
     */
    public @NonNull Resolution resolve(@Nullable UUID productId, @Nullable String storageLocation) {
        String location = (storageLocation == null || storageLocation.isBlank()) ? null : storageLocation;

        Optional<CycleCountTolerance> match = Optional.empty();
        if (productId != null && location != null) {
            match = repository.findByProductIdAndStorageLocationAndActiveTrue(productId, location);
        }
        if (match.isEmpty() && productId != null) {
            match = repository.findByProductIdAndStorageLocationIsNullAndActiveTrue(productId);
        }
        if (match.isEmpty() && location != null) {
            match = repository.findByProductIdIsNullAndStorageLocationAndActiveTrue(location);
        }
        if (match.isEmpty()) {
            match = repository.findByProductIdIsNullAndStorageLocationIsNullAndActiveTrue();
        }
        return match.map(t -> new Resolution(t.getToleranceId(), t.getAbsoluteTolerance(), t.getPercentageTolerance()))
                .orElse(Resolution.NONE);
    }

    /**
     * Whether an absolute variance is within the resolved tolerance, per the combining rule
     * documented on the class.
     *
     * @param absVariance {@code |measured − book|}, already converted to base UoM
     * @param bookQuantity the book (expected) quantity the percentage bound is a fraction of
     */
    public boolean isWithinTolerance(
            @NonNull Resolution resolution, @NonNull BigDecimal absVariance, @NonNull BigDecimal bookQuantity) {
        if (!resolution.isConfigured()) {
            return absVariance.signum() == 0;
        }
        boolean withinAbsolute =
                resolution.absoluteTolerance() != null && absVariance.compareTo(resolution.absoluteTolerance()) <= 0;
        boolean withinPercentage = resolution.percentageTolerance() != null
                && absVariance.compareTo(allowedQuantityForPercentage(resolution.percentageTolerance(), bookQuantity))
                        <= 0;
        return withinAbsolute || withinPercentage;
    }

    /**
     * The quantity a percentage tolerance permits against {@code bookQuantity}: {@code
     * |book| × percentage / 100}, rounded {@code HALF_UP} at the same scale the ledger stores.
     * A zero book quantity permits nothing — a percentage of nothing is nothing — so only an
     * exact match passes the percentage bound in that case.
     */
    private @NonNull BigDecimal allowedQuantityForPercentage(
            @NonNull BigDecimal percentageTolerance, @NonNull BigDecimal bookQuantity) {
        if (bookQuantity.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return bookQuantity
                .abs()
                .multiply(percentageTolerance)
                .divide(ONE_HUNDRED, ALLOWANCE_SCALE, RoundingMode.HALF_UP);
    }
}
