package com.positivity.vehicle.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.vehicle.internal.config.VehicleEventPublisher;
import com.positivity.vehicle.internal.dto.VehicleFactReplayResultDto;
import com.positivity.vehicle.internal.entity.VehicleRecord;
import com.positivity.vehicle.internal.repository.VehicleRecordRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

/**
 * The replay reads vehicles, not the outbox.
 *
 * <p>That distinction is the whole point: the rows this exists to repair are the ones whose facts
 * were never written, so an outbox-sourced replay would emit nothing for them however often it ran.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Vehicle fact replay re-emits from the vehicles themselves")
class VehicleFactReplayServiceImplTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-23T12:00:00Z"), ZoneOffset.UTC);

    @Mock
    private VehicleRecordRepository vehicleRecordRepository;

    @Mock
    private VehicleEventPublisher vehicleEventPublisher;

    private VehicleFactReplayServiceImpl service() {
        return new VehicleFactReplayServiceImpl(vehicleRecordRepository, vehicleEventPublisher, CLOCK);
    }

    private static VehicleRecord vehicle(UUID id) {
        VehicleRecord record = new VehicleRecord();
        record.setVehicleId(id);
        return record;
    }

    @Test
    @DisplayName("publishes one fact per vehicle and reports completion on a short page")
    void publishesOneFactPerVehicle() {
        UUID first = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01");
        UUID second = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a02");
        when(vehicleRecordRepository.findForReplay(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(List.of(vehicle(first), vehicle(second)));

        VehicleFactReplayResultDto result = service().replayPage(null, null, 500);

        verify(vehicleEventPublisher).publishVehicleUpdated(argThatHasId(first));
        verify(vehicleEventPublisher).publishVehicleUpdated(argThatHasId(second));
        assertThat(result.emitted()).isEqualTo(2);
        assertThat(result.complete())
                .as("a page shorter than the limit is the end")
                .isTrue();
        assertThat(result.nextAfterId()).isNull();
    }

    @Test
    @DisplayName("hands back a cursor when the page is full")
    void returnsCursorOnFullPage() {
        UUID first = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01");
        UUID last = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a02");
        when(vehicleRecordRepository.findForReplay(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(List.of(vehicle(first), vehicle(last)));

        VehicleFactReplayResultDto result = service().replayPage(null, null, 2);

        assertThat(result.complete()).isFalse();
        assertThat(result.nextAfterId())
                .as("resume after the last vehicle of the page")
                .isEqualTo(last);
    }

    @Test
    @DisplayName("clamps the page size so a mistyped limit cannot flood the broker")
    void clampsLimit() {
        when(vehicleRecordRepository.findForReplay(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(List.of());

        service().replayPage(null, null, 10_000);

        verify(vehicleRecordRepository)
                .findForReplay(isNull(), isNull(), eq(Pageable.ofSize(VehicleFactReplayServiceImpl.MAX_LIMIT)));
    }

    private static VehicleRecord argThatHasId(UUID id) {
        return org.mockito.ArgumentMatchers.argThat(record -> record != null && id.equals(record.getVehicleId()));
    }
}
