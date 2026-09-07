package com.positivity.price.internal.service;

import com.positivity.price.internal.entity.LaborRate;
import com.positivity.price.internal.entity.LaborRateAdjustment;
import com.positivity.price.internal.enums.LaborRateAdjustmentType;
import com.positivity.price.internal.repository.LaborRateAdjustmentRepository;
import com.positivity.price.internal.repository.LaborRateRepository;
import com.positivity.price.service.model.LaborRateQuoteRequest;
import com.positivity.price.service.model.LaborRateQuoteResponse;
import com.positivity.price.service.model.LaborRateQuoteResponse.AppliedAdjustment;
import com.positivity.price.service.model.LaborRateQuoteResponse.Scope;
import com.positivity.price.service.model.LaborRateQuoteResponse.Status;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Labor-rate resolution and the shop labor matrix (#1575 Tier 0, T0-3).
 *
 * <h2>Order of answers</h2>
 *
 * The rate row in force at the requested moment whose scope is the narrowest that can answer:
 * the location's rate for this operation category, then the location's rate for every category,
 * then the platform rate for the category, then the platform default. A scope with no row in
 * force at all is {@code NO_RATE_AVAILABLE} — a status the caller renders around, never an
 * exception, because a missing rate must not stop someone writing an estimate.
 *
 * <h2>The matrix</h2>
 *
 * Steps the caller opted into by code are applied in {@code sequence} order. {@code PERCENT}
 * compounds on the running rate and {@code FIXED} adds to it, so order changes the answer and is
 * therefore stored rather than assumed. Each step's resulting rate is returned so a quote can
 * show the derivation rather than an unexplained number. A code that names no in-force step is
 * silently not applied: the writer ticking a condition the shop has not priced should get the
 * base rate, not an error.
 *
 * <p>Rounding is deferred to the end — intermediate steps keep full scale and only the final
 * rate is rounded to 4dp (the column's scale), so a chain of percentages does not accumulate
 * rounding drift.
 */
@Service
@RequiredArgsConstructor
public class LaborRateResolutionServiceImpl implements LaborRateResolutionService {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final int RATE_SCALE = 4;

    private final LaborRateRepository rateRepository;
    private final LaborRateAdjustmentRepository adjustmentRepository;
    private final Clock clock;

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public LaborRateQuoteResponse resolve(@NonNull LaborRateQuoteRequest request) {
        Instant at = request.at() == null ? Instant.now(clock) : request.at();
        String category = normalizedCategory(request.operationCategory());

        Optional<LaborRate> best = rateRepository.findCandidates(request.locationId(), category, at).stream()
                .min(Comparator.comparingInt(rate -> scopeOf(rate).ordinal()));
        if (best.isEmpty()) {
            return LaborRateQuoteResponse.miss();
        }

        LaborRate rate = best.get();
        BigDecimal running = rate.getHourlyRate();
        List<AppliedAdjustment> steps = new ArrayList<>();

        Set<String> codes = normalizedCodes(request.adjustmentCodesOrEmpty());
        if (!codes.isEmpty()) {
            for (LaborRateAdjustment step :
                    adjustmentRepository.findApplicable(codes, request.locationId(), category, at)) {
                running = applied(running, step);
                steps.add(new AppliedAdjustment(
                        step.getAdjustmentCode(),
                        step.getAdjustmentType().name(),
                        step.getAdjustmentValue(),
                        running.setScale(RATE_SCALE, RoundingMode.HALF_UP)));
            }
        }

        return new LaborRateQuoteResponse(
                Status.RESOLVED,
                running.setScale(RATE_SCALE, RoundingMode.HALF_UP),
                rate.getHourlyRate().setScale(RATE_SCALE, RoundingMode.HALF_UP),
                rate.getCurrency(),
                scopeOf(rate),
                rate.getId(),
                rate.getEffectiveFrom(),
                List.copyOf(steps));
    }

    /**
     * A discount step must never invert the rate. Clamped at zero rather than rejected at write
     * time, because a −120% step is only wrong in combination with the steps around it, and the
     * combination is not knowable until a quote names its codes.
     */
    private static BigDecimal applied(BigDecimal running, LaborRateAdjustment step) {
        BigDecimal next = step.getAdjustmentType() == LaborRateAdjustmentType.PERCENT
                ? running.add(running.multiply(step.getAdjustmentValue()).divide(ONE_HUNDRED, 10, RoundingMode.HALF_UP))
                : running.add(step.getAdjustmentValue());
        return next.signum() < 0 ? BigDecimal.ZERO : next;
    }

    /** Narrowest first, so {@code ordinal()} is the ranking. */
    private static Scope scopeOf(LaborRate rate) {
        if (rate.getLocationId() != null) {
            return rate.getOperationCategory() != null ? Scope.LOCATION_CATEGORY : Scope.LOCATION_DEFAULT;
        }
        return rate.getOperationCategory() != null ? Scope.PLATFORM_CATEGORY : Scope.PLATFORM_DEFAULT;
    }

    /**
     * An unrecognised category widens to the category-agnostic rate rather than erroring: the
     * category is a hint from a caller in another module, and a vocabulary drift between the two
     * should cost precision, not availability.
     */
    @Nullable
    private static String normalizedCategory(@Nullable String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        return category.trim().toUpperCase(Locale.ROOT);
    }

    /** Deduplicated and order-preserving: a code repeated in the request applies once. */
    private static Set<String> normalizedCodes(List<String> codes) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String code : codes) {
            if (code != null && !code.isBlank()) {
                normalized.add(code.trim().toUpperCase(Locale.ROOT));
            }
        }
        return normalized;
    }
}
