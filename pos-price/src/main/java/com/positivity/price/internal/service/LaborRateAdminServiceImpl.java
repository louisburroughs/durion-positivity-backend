package com.positivity.price.internal.service;

import com.positivity.price.internal.dto.LaborRateAdjustmentRequest;
import com.positivity.price.internal.dto.LaborRateAdjustmentResponse;
import com.positivity.price.internal.dto.LaborRateRequest;
import com.positivity.price.internal.dto.LaborRateResponse;
import com.positivity.price.internal.entity.LaborRate;
import com.positivity.price.internal.entity.LaborRateAdjustment;
import com.positivity.price.internal.enums.LaborRateAdjustmentType;
import com.positivity.price.internal.enums.ServiceOperationCategory;
import com.positivity.price.internal.exception.LaborRateValidationException;
import com.positivity.price.internal.repository.LaborRateAdjustmentRepository;
import com.positivity.price.internal.repository.LaborRateRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authoring for shop labor rates and the matrix (#1575 Tier 0, T0-3).
 *
 * <p>Validation here rather than at the database: the V4 CHECK constraints are the backstop that
 * catches direct SQL and concurrent writers, but a caller sending an unknown category or an
 * inverted window should get a 422 explaining which field is wrong, not a 500 carrying a
 * constraint name.
 *
 * <p>There is deliberately no update or delete. A rate that has priced an invoice cannot be
 * edited away without making that invoice unexplainable, so a change is a new row in a new
 * window — the same append-and-supersede reasoning as {@code service_labor_standard}.
 */
@Service
@RequiredArgsConstructor
public class LaborRateAdminServiceImpl implements LaborRateAdminService {

    private final LaborRateRepository rateRepository;
    private final LaborRateAdjustmentRepository adjustmentRepository;

    @Override
    @NonNull
    @Transactional
    public LaborRateResponse createRate(@NonNull LaborRateRequest request) {
        LaborRate rate = new LaborRate();
        rate.setLocationId(request.getLocationId());
        rate.setOperationCategory(parsedCategory(request.getOperationCategory()));
        rate.setCurrency(requiredCurrency(request.getCurrency()));
        rate.setHourlyRate(positiveRate(request.getHourlyRate()));
        rate.setEffectiveFrom(required(request.getEffectiveFrom(), "effectiveFrom"));
        rate.setEffectiveTo(validatedWindow(request.getEffectiveFrom(), request.getEffectiveTo()));
        return toResponse(rateRepository.save(rate));
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public List<LaborRateResponse> listRates() {
        return rateRepository.findAllByOrderByEffectiveFromDesc().stream()
                .map(LaborRateAdminServiceImpl::toResponse)
                .toList();
    }

    @Override
    @NonNull
    @Transactional
    public LaborRateAdjustmentResponse createAdjustment(@NonNull LaborRateAdjustmentRequest request) {
        LaborRateAdjustment step = new LaborRateAdjustment();
        step.setLocationId(request.getLocationId());
        step.setOperationCategory(parsedCategory(request.getOperationCategory()));
        step.setAdjustmentCode(requiredCode(request.getAdjustmentCode()));
        step.setDescription(trimToNull(request.getDescription()));
        step.setAdjustmentType(parsedType(request.getAdjustmentType()));
        step.setAdjustmentValue(required(request.getAdjustmentValue(), "adjustmentValue"));
        step.setSequence(required(request.getSequence(), "sequence"));
        step.setEffectiveFrom(required(request.getEffectiveFrom(), "effectiveFrom"));
        step.setEffectiveTo(validatedWindow(request.getEffectiveFrom(), request.getEffectiveTo()));
        return toResponse(adjustmentRepository.save(step));
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public List<LaborRateAdjustmentResponse> listAdjustments() {
        return adjustmentRepository.findAllByOrderBySequenceAscAdjustmentCodeAsc().stream()
                .map(LaborRateAdminServiceImpl::toResponse)
                .toList();
    }

    // ── Validation ──────────────────────────────────────────────────────────────────────

    @Nullable
    private static ServiceOperationCategory parsedCategory(@Nullable String category) {
        String normalized = trimToNull(category);
        if (normalized == null) {
            return null;
        }
        try {
            return ServiceOperationCategory.valueOf(normalized.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new LaborRateValidationException("operationCategory must be one of "
                    + Arrays.toString(ServiceOperationCategory.values()) + ": " + category);
        }
    }

    private static LaborRateAdjustmentType parsedType(@Nullable String type) {
        String normalized = trimToNull(type);
        if (normalized == null) {
            throw new LaborRateValidationException("adjustmentType is required");
        }
        try {
            return LaborRateAdjustmentType.valueOf(normalized.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new LaborRateValidationException(
                    "adjustmentType must be one of " + Arrays.toString(LaborRateAdjustmentType.values()) + ": " + type);
        }
    }

    private static String requiredCurrency(@Nullable String currency) {
        String normalized = trimToNull(currency);
        if (normalized == null || normalized.length() != 3) {
            throw new LaborRateValidationException("currency must be a 3-letter ISO 4217 code: " + currency);
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    private static String requiredCode(@Nullable String code) {
        String normalized = trimToNull(code);
        if (normalized == null) {
            throw new LaborRateValidationException("adjustmentCode is required");
        }
        // Uppercased on the way in because resolution matches uppercased request codes; a step
        // stored as "corrosion" would silently never apply.
        return normalized.toUpperCase(Locale.ROOT);
    }

    private static BigDecimal positiveRate(@Nullable BigDecimal rate) {
        if (rate == null || rate.signum() <= 0) {
            throw new LaborRateValidationException("hourlyRate must be greater than zero: " + rate);
        }
        return rate;
    }

    @Nullable
    private static Instant validatedWindow(@Nullable Instant from, @Nullable Instant to) {
        if (to != null && from != null && !to.isAfter(from)) {
            throw new LaborRateValidationException("effectiveTo must be after effectiveFrom");
        }
        return to;
    }

    private static <T> T required(@Nullable T value, String field) {
        if (value == null) {
            throw new LaborRateValidationException(field + " is required");
        }
        return value;
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ── Mapping ─────────────────────────────────────────────────────────────────────────

    private static LaborRateResponse toResponse(LaborRate rate) {
        LaborRateResponse dto = new LaborRateResponse();
        dto.setId(rate.getId());
        dto.setLocationId(rate.getLocationId());
        dto.setOperationCategory(
                rate.getOperationCategory() == null
                        ? null
                        : rate.getOperationCategory().name());
        dto.setCurrency(rate.getCurrency());
        dto.setHourlyRate(rate.getHourlyRate());
        dto.setEffectiveFrom(rate.getEffectiveFrom());
        dto.setEffectiveTo(rate.getEffectiveTo());
        return dto;
    }

    private static LaborRateAdjustmentResponse toResponse(LaborRateAdjustment step) {
        LaborRateAdjustmentResponse dto = new LaborRateAdjustmentResponse();
        dto.setId(step.getId());
        dto.setLocationId(step.getLocationId());
        dto.setOperationCategory(
                step.getOperationCategory() == null
                        ? null
                        : step.getOperationCategory().name());
        dto.setAdjustmentCode(step.getAdjustmentCode());
        dto.setDescription(step.getDescription());
        dto.setAdjustmentType(step.getAdjustmentType().name());
        dto.setAdjustmentValue(step.getAdjustmentValue());
        dto.setSequence(step.getSequence());
        dto.setEffectiveFrom(step.getEffectiveFrom());
        dto.setEffectiveTo(step.getEffectiveTo());
        return dto;
    }
}
