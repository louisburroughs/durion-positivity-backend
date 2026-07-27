package com.positivity.order.internal.service;

import com.positivity.order.internal.entity.OrderNumberSequence;
import com.positivity.order.internal.repository.OrderNumberSequenceRepository;
import java.time.Clock;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Issues human-facing order numbers {@code SO-{locationCode}-{yyMM}-{seq}} from a per-location,
 * per-month sequence (plan story A2). Must be called inside the cart-creation transaction so the
 * row lock scopes to it. Gapless numbering is NOT guaranteed (recorded decision).
 *
 * <p>The location code is currently derived from the location UUID's random tail; a friendly code
 * from pos-location replaces it when register sessions supply location context (story G1).
 */
@Component
@RequiredArgsConstructor
public class OrderNumberService {

    private static final DateTimeFormatter PERIOD_FORMAT = DateTimeFormatter.ofPattern("yyMM");

    private final OrderNumberSequenceRepository sequenceRepository;
    private final Clock clock;

    @NonNull
    public String nextNumber(@NonNull UUID locationId) {
        String period = YearMonth.now(clock).format(PERIOD_FORMAT);
        long value = nextValue(locationId, period);
        return "SO-%s-%s-%06d".formatted(locationCode(locationId), period, value);
    }

    private long nextValue(UUID locationId, String period) {
        OrderNumberSequence sequence =
                sequenceRepository.findForUpdate(locationId, period).orElseGet(() -> insertNew(locationId, period));
        long value = sequence.getNextValue();
        sequence.setNextValue(value + 1);
        sequenceRepository.save(sequence);
        return value;
    }

    private OrderNumberSequence insertNew(UUID locationId, String period) {
        try {
            return sequenceRepository.saveAndFlush(OrderNumberSequence.builder()
                    .key(new OrderNumberSequence.Key(locationId, period))
                    .nextValue(1L)
                    .build());
        } catch (DataIntegrityViolationException raced) {
            // Another transaction inserted the row first; take the lock on the existing row.
            return sequenceRepository
                    .findForUpdate(locationId, period)
                    .orElseThrow(() -> new IllegalStateException(
                            "Order number sequence row vanished for location " + locationId + " period " + period));
        }
    }

    private static String locationCode(UUID locationId) {
        String hex = locationId.toString().replace("-", "");
        return hex.substring(hex.length() - 8).toUpperCase(Locale.ROOT);
    }
}
