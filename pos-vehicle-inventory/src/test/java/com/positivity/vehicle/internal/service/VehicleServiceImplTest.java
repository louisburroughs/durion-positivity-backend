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
import com.positivity.shared.dto.CreateVehicleRequest;
import com.positivity.shared.dto.UpdateVehicleRequest;
import com.positivity.vehicle.internal.enums.GvwrClassSource;
import static org.mockito.ArgumentMatchers.any;

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

    // ── CAP-327 (spec D13): gvwr_class is operator-set, its source travels with it, duty category is derived ──

    private static VehicleRecord classified(Integer gvwrClass, GvwrClassSource source) {
        VehicleRecord record = vehicle(null);
        record.setGvwrClass(gvwrClass);
        record.setGvwrClassSource(source);
        return record;
    }

    @Test
    void createWithGvwrClassRecordsOperatorSetAndDerivesTheDutyCategory() {
        when(vehicleRepository.existsByVinNormalizedAndIsActiveTrue(any())).thenReturn(false);
        when(vehicleRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        VehicleResponse response = service.createVehicle(CreateVehicleRequest.builder()
                .accountId(UUID.fromString("018f0000-0000-7000-8000-000000000302"))
                .vin("1HGCM82633A004352")
                .gvwrClass(6)
                .build());

        assertThat(response.getGvwrClass()).isEqualTo(6);
        assertThat(response.getGvwrClassSource()).isEqualTo("OPERATOR_SET");
        assertThat(response.getDutyCategory()).isEqualTo("MEDIUM");
    }

    @Test
    void createWithoutGvwrClassLeavesItUndetermined() {
        when(vehicleRepository.existsByVinNormalizedAndIsActiveTrue(any())).thenReturn(false);
        when(vehicleRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        VehicleResponse response = service.createVehicle(CreateVehicleRequest.builder()
                .accountId(UUID.fromString("018f0000-0000-7000-8000-000000000302"))
                .vin("1HGCM82633A004352")
                .build());

        assertThat(response.getGvwrClass()).isNull();
        assertThat(response.getGvwrClassSource()).isNull();
        assertThat(response.getDutyCategory()).isNull();
    }

    @Test
    void undeterminedClassMapsAllThreeFieldsToNull() {
        VehicleResponse response = map(vehicle(null));

        assertThat(response.getGvwrClass()).isNull();
        assertThat(response.getGvwrClassSource()).isNull();
        assertThat(response.getDutyCategory()).isNull();
    }

    @Test
    void classThreeIsLightDutyOnTheResponse() {
        VehicleResponse response = map(classified(3, GvwrClassSource.OPERATOR_SET));

        assertThat(response.getDutyCategory()).isEqualTo("LIGHT");
    }

    @Test
    void updateWithGvwrClassReplacesTheValueAndMarksItOperatorSet() {
        when(vehicleRepository.findByVehicleId(VEHICLE_ID))
                .thenReturn(Optional.of(classified(2, GvwrClassSource.DECODED)));
        when(vehicleRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        VehicleResponse response = service.updateVehicle(
                VEHICLE_ID, UpdateVehicleRequest.builder().gvwrClass(7).build());

        assertThat(response.getGvwrClass()).isEqualTo(7);
        assertThat(response.getGvwrClassSource()).isEqualTo("OPERATOR_SET");
        assertThat(response.getDutyCategory()).isEqualTo("HEAVY");
    }

    @Test
    void updateWithoutGvwrClassLeavesADecodedValueUntouched() {
        when(vehicleRepository.findByVehicleId(VEHICLE_ID))
                .thenReturn(Optional.of(classified(2, GvwrClassSource.DECODED)));
        when(vehicleRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        VehicleResponse response = service.updateVehicle(
                VEHICLE_ID, UpdateVehicleRequest.builder().trim("XLT").build());

        assertThat(response.getGvwrClass()).isEqualTo(2);
        assertThat(response.getGvwrClassSource()).isEqualTo("DECODED");
        assertThat(response.getDutyCategory()).isEqualTo("LIGHT");
    }
}
