package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.order.internal.entity.OrderNumberSequence;
import com.positivity.order.internal.repository.OrderNumberSequenceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Unit tests for {@link OrderNumberService} (plan story A2).
 *
 * <p>
 * The order number is what a customer reads back over the phone, so the format
 * is a contract: {@code SO-{locationCode}-{yyMM}-{seq}}, zero-padded, with the
 * period taken from the injected clock so a sequence rolls over on the calendar
 * month rather than on service restart.
 *
 * <p>
 * The interesting path is the insert race. Two carts created in the same new
 * month for the same location both find no sequence row; the loser of the insert
 * takes the lock on the winner's row instead of failing the cart. Gapless
 * numbering is explicitly not guaranteed (recorded decision), so the tests pin
 * allocation and recovery, not contiguity.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OrderNumberService — per-location monthly sequence")
class OrderNumberServiceTest {

    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-0000abcdef12");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-11T09:00:00Z"), ZoneOffset.UTC);

    @Mock
    private OrderNumberSequenceRepository sequenceRepository;

    private OrderNumberService service;

    @BeforeEach
    void setUp() {
        service = new OrderNumberService(sequenceRepository, CLOCK);
    }

    private static OrderNumberSequence sequence(long nextValue) {
        return OrderNumberSequence.builder()
                .key(new OrderNumberSequence.Key(LOCATION_ID, "2608"))
                .nextValue(nextValue)
                .build();
    }

    @Test
    @DisplayName("formats the number from the location tail, the clock's month, and the sequence")
    void formatsNumber() {
        when(sequenceRepository.findForUpdate(LOCATION_ID, "2608")).thenReturn(Optional.of(sequence(42L)));

        // Location code is the uppercased tail of the location UUID; period is yyMM from the clock.
        assertThat(service.nextNumber(LOCATION_ID)).isEqualTo("SO-ABCDEF12-2608-000042");
    }

    @Test
    @DisplayName("advances the stored sequence so the next cart gets a different number")
    void advancesTheSequence() {
        OrderNumberSequence existing = sequence(7L);
        when(sequenceRepository.findForUpdate(LOCATION_ID, "2608")).thenReturn(Optional.of(existing));

        service.nextNumber(LOCATION_ID);

        assertThat(existing.getNextValue()).isEqualTo(8L);
        verify(sequenceRepository).save(existing);
    }

    @Test
    @DisplayName("starts a new location-month at one")
    void firstOrderOfTheMonthStartsAtOne() {
        when(sequenceRepository.findForUpdate(LOCATION_ID, "2608")).thenReturn(Optional.empty());
        when(sequenceRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.nextNumber(LOCATION_ID)).isEqualTo("SO-ABCDEF12-2608-000001");

        ArgumentCaptor<OrderNumberSequence> captor = ArgumentCaptor.forClass(OrderNumberSequence.class);
        verify(sequenceRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getKey().getPeriod()).isEqualTo("2608");
        assertThat(captor.getValue().getKey().getLocationId()).isEqualTo(LOCATION_ID);
    }

    @Test
    @DisplayName("takes the winner's row when a concurrent cart inserted the sequence first")
    void losesTheInsertRaceGracefully() {
        when(sequenceRepository.findForUpdate(LOCATION_ID, "2608"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(sequence(3L)));
        when(sequenceRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        // Losing the race must cost a re-read, not the cart.
        assertThat(service.nextNumber(LOCATION_ID)).isEqualTo("SO-ABCDEF12-2608-000003");
    }

    @Test
    @DisplayName("fails loudly if the row is neither insertable nor readable")
    void vanishedRowIsAnError() {
        when(sequenceRepository.findForUpdate(LOCATION_ID, "2608")).thenReturn(Optional.empty());
        when(sequenceRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service.nextNumber(LOCATION_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vanished");
    }
}
