package com.positivity.warranty.internal.service;

import com.positivity.warranty.internal.dto.ProrationOutcome;
import com.positivity.warranty.internal.entity.WarrantyClaimLine;
import com.positivity.warranty.internal.entity.WarrantyPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Pure proration math per the PRD §6 table (method from the governing policy):
 *
 * <pre>
 * NONE         1.0 (free replacement)
 * TREAD_DEPTH  (measuredDepth - pullPoint) / (originalDepth - pullPoint)
 * MILEAGE      (mileageLimit - milesDriven) / mileageLimit
 * TIME         (durationMonths - monthsElapsed) / durationMonths
 * </pre>
 *
 * <p>The fraction is clamped to [0, 1] (scale 6); credit = fraction x originalUnitPrice x
 * quantity, clamped to [0, originalUnitPrice x quantity] (scale 4); RoundingMode.HALF_UP.
 * Missing inputs or zero denominators never throw — they yield an indeterminate outcome with
 * the missing facts named in the frozen {@code inputs} map.
 */
@Service
public class ProrationService {

    static final int PCT_SCALE = 6;
    static final int MONEY_SCALE = 4;

    /**
     * Compute the proration fraction and requested credit for one claim line.
     *
     * @param milesDriven odometer delta since the original sale, when known (MILEAGE method)
     * @param monthsElapsed whole months elapsed since the original sale, when known (TIME method)
     */
    @NonNull
    public ProrationOutcome prorate(
            @NonNull WarrantyPolicy policy,
            @NonNull WarrantyClaimLine line,
            @Nullable Long milesDriven,
            @Nullable Long monthsElapsed) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        inputs.put("method", policy.getProrationMethod().name());

        BigDecimal pct =
                switch (policy.getProrationMethod()) {
                    case NONE -> BigDecimal.ONE;
                    case TREAD_DEPTH -> treadDepthFraction(policy, line, inputs, missing);
                    case MILEAGE -> mileageFraction(policy, milesDriven, inputs, missing);
                    case TIME -> timeFraction(policy, monthsElapsed, inputs, missing);
                };
        if (pct != null) {
            pct = clamp(pct, BigDecimal.ZERO, BigDecimal.ONE).setScale(PCT_SCALE, RoundingMode.HALF_UP);
        }
        inputs.put("prorationPct", pct);

        BigDecimal unitPrice = line.getOriginalUnitPrice();
        BigDecimal quantity = line.getQuantity() != null ? line.getQuantity() : BigDecimal.ONE;
        inputs.put("originalUnitPrice", unitPrice);
        inputs.put("quantity", quantity);

        BigDecimal amount = null;
        if (unitPrice == null) {
            missing.add("originalUnitPrice");
        } else if (pct != null) {
            BigDecimal original = unitPrice.multiply(quantity);
            amount = clamp(pct.multiply(original), BigDecimal.ZERO, original)
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        inputs.put("amountRequested", amount);

        boolean indeterminate = !missing.isEmpty();
        if (indeterminate) {
            inputs.put("missing", List.copyOf(missing));
        }
        return new ProrationOutcome(pct, amount, inputs, indeterminate);
    }

    /** Tire-industry standard: remaining usable tread over original usable tread. */
    @Nullable
    private static BigDecimal treadDepthFraction(
            WarrantyPolicy policy, WarrantyClaimLine line, Map<String, Object> inputs, List<String> missing) {
        Integer measured = line.getMeasuredTreadDepth();
        Integer original = line.getOriginalTreadDepth();
        // Coverage floor; a policy without an explicit pull point prorates over the full depth.
        int pullPoint = policy.getTreadPullPointThirtySeconds() != null ? policy.getTreadPullPointThirtySeconds() : 0;
        inputs.put("measuredTreadDepth", measured);
        inputs.put("originalTreadDepth", original);
        inputs.put("treadPullPointThirtySeconds", pullPoint);
        if (measured == null) {
            missing.add("measuredTreadDepth");
        }
        if (original == null) {
            missing.add("originalTreadDepth");
        }
        if (measured == null || original == null) {
            return null;
        }
        int denominator = original - pullPoint;
        if (denominator <= 0) {
            missing.add("usableTreadDepth (originalTreadDepth must exceed pull point)");
            return null;
        }
        return BigDecimal.valueOf((long) measured - pullPoint)
                .divide(BigDecimal.valueOf(denominator), PCT_SCALE, RoundingMode.HALF_UP);
    }

    @Nullable
    private static BigDecimal mileageFraction(
            WarrantyPolicy policy, @Nullable Long milesDriven, Map<String, Object> inputs, List<String> missing) {
        Integer limit = policy.getMileageLimit();
        inputs.put("mileageLimit", limit);
        inputs.put("milesDriven", milesDriven);
        if (limit == null || limit <= 0) {
            missing.add("mileageLimit (must be a positive limit)");
            return null;
        }
        if (milesDriven == null) {
            missing.add("milesDriven");
            return null;
        }
        return BigDecimal.valueOf(limit - milesDriven)
                .divide(BigDecimal.valueOf(limit), PCT_SCALE, RoundingMode.HALF_UP);
    }

    @Nullable
    private static BigDecimal timeFraction(
            WarrantyPolicy policy, @Nullable Long monthsElapsed, Map<String, Object> inputs, List<String> missing) {
        Integer duration = policy.getDurationMonths();
        inputs.put("durationMonths", duration);
        inputs.put("monthsElapsed", monthsElapsed);
        if (duration == null || duration <= 0) {
            missing.add("durationMonths (must be a positive duration)");
            return null;
        }
        if (monthsElapsed == null) {
            missing.add("monthsElapsed");
            return null;
        }
        return BigDecimal.valueOf(duration - monthsElapsed)
                .divide(BigDecimal.valueOf(duration), PCT_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal clamp(BigDecimal value, BigDecimal min, BigDecimal max) {
        return value.max(min).min(max);
    }
}
