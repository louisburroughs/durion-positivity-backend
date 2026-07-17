package com.positivity.vehicle.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.positivity.shared.dto.VehicleResponse;
import com.positivity.vehicle.internal.entity.VehicleRecord;
import com.positivity.vehicle.internal.enums.OdometerUnit;
import com.positivity.vehicle.internal.repository.VehicleRecordRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Response-mapping contract for the odometer snapshot consumed by pos-warranty claim intake
 * (PRD-warranty-claims-module §3.4: vin/odometerAtClaim/odometerUnit are frozen from this
 * response). Pins the {@code odometerValue}/{@code odometerUnit} mapping added for warranty:
 * {@code Long → Integer} conversion, {@code unit.name()} serialization, and null-safety for
 * vehicles without a reading.
 */
@ExtendWith(MockitoExtension.class)
class VehicleServiceImplTest {

    private static final UUID VEHICLE_ID = UUID.fromString("018f0000-0000-7000-8000-000000000301");

    @Mock
    private VehicleRecordRepository vehicleRepository;

    @Mock
    private com.positivity.vehicle.internal.config.VehicleEventPublisher vehicleEventPublisher;

    @InjectMocks
    private VehicleServiceImpl service;

    private static VehicleRecord vehicle(VehicleRecord.OdometerReading odometer) {
        return VehicleRecord.builder()
                .vehicleId(VEHICLE_ID)
                .accountId(UUID.fromString("018f0000-0000-7000-8000-000000000302"))
                .vin("1HGCM82633A004352")
                .vinNormalized("1HGCM82633A004352")
                .unitNumber("UNIT-1024")
                .description("Test truck")
                .odometer(odometer)
                .isActive(true)
                .build();
    }

    private VehicleResponse map(VehicleRecord record) {
        when(vehicleRepository.findByVehicleId(VEHICLE_ID)).thenReturn(Optional.of(record));
        return service.getVehicle(VEHICLE_ID).orElseThrow();
    }

    @Test
    void populatedOdometerMapsValueAsIntegerAndUnitAsEnumName() {
        VehicleResponse response = map(vehicle(VehicleRecord.OdometerReading.builder()
                .value(42_000L)
                .unit(OdometerUnit.MILES)
                .asOfDateTime(Instant.parse("2026-07-01T00:00:00Z"))
                .build()));

        assertThat(response.getVin()).isEqualTo("1HGCM82633A004352");
        assertThat(response.getOdometerValue()).isEqualTo(42_000);
        assertThat(response.getOdometerUnit()).isEqualTo("MILES");
    }

    @Test
    void missingOdometerReadingMapsBothFieldsToNull() {
        VehicleResponse response = map(vehicle(null));

        assertThat(response.getOdometerValue()).isNull();
        assertThat(response.getOdometerUnit()).isNull();
        // The rest of the snapshot is still served.
        assertThat(response.getVin()).isEqualTo("1HGCM82633A004352");
    }

    @Test
    void odometerReadingWithNullValueMapsValueToNullButKeepsUnit() {
        VehicleResponse response = map(vehicle(VehicleRecord.OdometerReading.builder()
                .value(null)
                .unit(OdometerUnit.KILOMETERS)
                .build()));

        assertThat(response.getOdometerValue()).isNull();
        assertThat(response.getOdometerUnit()).isEqualTo("KILOMETERS");
    }

    @Test
    void odometerReadingWithNullUnitMapsUnitToNullButKeepsValue() {
        VehicleResponse response = map(vehicle(VehicleRecord.OdometerReading.builder()
                .value(120_500L)
                .unit(null)
                .build()));

        assertThat(response.getOdometerValue()).isEqualTo(120_500);
        assertThat(response.getOdometerUnit()).isNull();
    }
}
