package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.enums.PickListStatus;
import com.positivity.inventory.internal.enums.PickTaskStatus;
import com.positivity.inventory.internal.enums.ReservationStatus;
import com.positivity.inventory.internal.repository.PickTaskRepository;
import com.positivity.inventory.internal.repository.ReservationRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-side forecast computation from open documents (odoo-parity A2, issue #1028). See
 * {@link ForecastQuantityService} for the full component and horizon semantics.
 */
@Service
@RequiredArgsConstructor
public class ForecastQuantityServiceImpl implements ForecastQuantityService {

    /** Reservation statuses representing open demand. */
    private static final List<ReservationStatus> OPEN_RESERVATION_STATUSES =
            List.of(ReservationStatus.PENDING, ReservationStatus.PARTIALLY_FULFILLED);

    /** Pick-list statuses considered released to the floor. */
    private static final List<PickListStatus> RELEASED_PICK_LIST_STATUSES =
            List.of(PickListStatus.READY_TO_PICK, PickListStatus.IN_PROGRESS);

    private final ExpectedSupplyService expectedSupplyService;
    private final ReservationRepository reservationRepository;
    private final PickTaskRepository pickTaskRepository;

    @Override
    @Transactional(readOnly = true)
    public @NonNull ForecastQuantities forecast(
            @NonNull String stockItemId, @Nullable UUID siteId, @Nullable Instant horizon, @NonNull BigDecimal onHand) {
        // No rounding here since ADR-0055 (#1414): the forecast fields are decimal, so expected
        // supply is carried at the precision the supplying documents state it in rather than
        // floored to squeeze it into an integer.
        BigDecimal incomingQty = expectedSupplyService.expectedIncomingQuantity(stockItemId, siteId, horizon);

        BigDecimal outgoingQty = reservationRemainder(stockItemId, horizon).add(pickTaskRemainder(stockItemId, siteId));

        return new ForecastQuantities(
                incomingQty, outgoingQty, onHand.add(incomingQty).subtract(outgoingQty));
    }

    private BigDecimal reservationRemainder(String stockItemId, @Nullable Instant horizon) {
        UUID skuUuid = parseUuid(stockItemId);
        if (skuUuid == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal remainder = reservationRepository.sumOpenRemainderForSku(
                skuUuid, OPEN_RESERVATION_STATUSES, horizon != null, horizon);
        return remainder == null ? BigDecimal.ZERO : remainder;
    }

    private BigDecimal pickTaskRemainder(String stockItemId, @Nullable UUID siteId) {
        Long remainder = pickTaskRepository.sumReleasedUnpickedRemainderForSku(
                stockItemId, parseUuid(stockItemId), PickTaskStatus.PENDING, RELEASED_PICK_LIST_STATUSES, siteId);
        return remainder == null ? BigDecimal.ZERO : BigDecimal.valueOf(remainder);
    }

    private static @Nullable UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
