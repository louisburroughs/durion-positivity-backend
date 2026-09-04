package com.positivity.vehicle.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.vehicle.internal.dao.VehicleDao;
import com.positivity.vehicle.internal.dto.VehicleLegacyRequest;
import com.positivity.vehicle.internal.dto.VehicleLegacyResponse;
import com.positivity.vehicle.internal.entity.Car;
import com.positivity.vehicle.internal.entity.CommercialTruck;
import com.positivity.vehicle.internal.entity.PassengerTruck;
import com.positivity.vehicle.internal.entity.Van;
import com.positivity.vehicle.internal.entity.VehicleEntity;
import com.positivity.vehicle.internal.exception.VehicleValidationException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for {@link VehicleLegacyServiceImpl}.
 *
 * <p>
 * The vehicle type string is the deciding input: it selects which entity
 * subclass gets persisted, and that subclass is what the type reads back as. An
 * unknown type deliberately degrades to {@code CAR} rather than failing at the
 * mapper — but the service validates the type <em>first</em>, so an unsupported
 * type is rejected at the boundary and the mapper's fallback is only reachable
 * for null.
 *
 * <p>
 * VIN handling differs by endpoint family on purpose: the id-keyed endpoints
 * treat VIN as optional (null fine, blank rejected), while the VIN-keyed
 * endpoints require it — an entity created by VIN without one would be
 * unreachable through the very endpoints that manage it. The model-year window
 * is anchored to the injected clock, allowing next year's models but not 1885.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("VehicleLegacyServiceImpl — legacy vehicle CRUD")
class VehicleLegacyServiceImplTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-11T12:00:00Z"), ZoneOffset.UTC);

    @Mock
    private VehicleDao vehicleDao;

    private VehicleLegacyServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new VehicleLegacyServiceImpl(vehicleDao, CLOCK);
        when(vehicleDao.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static VehicleLegacyRequest request(String vehicleType, String vin) {
        VehicleLegacyRequest request = new VehicleLegacyRequest();
        request.setMake("Toyota");
        request.setModel("Tacoma");
        request.setYear(2024);
        request.setVehicleType(vehicleType);
        request.setVin(vin);
        return request;
    }

    private VehicleEntity captureSaved() {
        ArgumentCaptor<VehicleEntity> captor = ArgumentCaptor.forClass(VehicleEntity.class);
        verify(vehicleDao).save(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("type mapping")
    class TypeMapping {

        @ParameterizedTest
        @CsvSource({
            "CAR, CAR",
            "VAN, VAN",
            "COMMERCIAL_TRUCK, COMMERCIAL_TRUCK",
            "PASSENGER_TRUCK, PASSENGER_TRUCK",
            "TRUCK, PASSENGER_TRUCK",
            "van, VAN",
            "'  truck  ', PASSENGER_TRUCK"
        })
        @DisplayName("persists the entity subclass for the type, normalizing case and whitespace")
        void mapsTypeToSubclass(String vehicleType, String expectedReadback) {
            VehicleLegacyResponse response = service.createVehicle(request(vehicleType, null));

            // The subclass is the persisted representation of the type, and TRUCK is an accepted
            // alias for PASSENGER_TRUCK.
            assertThat(response.getVehicleType()).isEqualTo(expectedReadback);
        }

        @Test
        @DisplayName("defaults a missing type to CAR rather than rejecting the request")
        void nullTypeDefaultsToCar() {
            service.createVehicle(request(null, null));

            assertThat(captureSaved()).isInstanceOf(Car.class);
        }

        @Test
        @DisplayName("rejects an unsupported type at the boundary, before the mapper's CAR fallback")
        void unsupportedTypeIsRejected() {
            // Without this validation, "MOTORCYCLE" would silently persist as a Car.
            assertThatThrownBy(() -> service.createVehicle(request("MOTORCYCLE", null)))
                    .isInstanceOf(VehicleValidationException.class)
                    .hasMessageContaining("MOTORCYCLE");

            verifyNoInteractions(vehicleDao);
        }

        @Test
        @DisplayName("reads each subclass back as its type")
        void readbackResolvesSubclass() {
            for (VehicleEntity entity :
                    new VehicleEntity[] {new Car(), new Van(), new CommercialTruck(), new PassengerTruck()}) {
                entity.setId(ID);
                entity.setMake("Toyota");
                entity.setModel("Tacoma");
                entity.setYear(2024);
                when(vehicleDao.findById(ID)).thenReturn(Optional.of(entity));

                assertThat(service.getVehicle(ID).orElseThrow().getVehicleType())
                        .isEqualTo(
                                switch (entity) {
                                    case Van v -> "VAN";
                                    case CommercialTruck c -> "COMMERCIAL_TRUCK";
                                    case PassengerTruck p -> "PASSENGER_TRUCK";
                                    default -> "CAR";
                                });
            }
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @ParameterizedTest
        @CsvSource({"1885, false", "1886, true", "2027, true", "2028, false"})
        @DisplayName("accepts model years from 1886 through next year, anchored to the clock")
        void yearWindow(int year, boolean accepted) {
            VehicleLegacyRequest request = request("CAR", null);
            request.setYear(year);

            if (accepted) {
                assertThat(service.createVehicle(request)).isNotNull();
            } else {
                // 1886 is the first model year; the +1 buffer admits next year's models mid-cycle.
                assertThatThrownBy(() -> service.createVehicle(request))
                        .isInstanceOf(VehicleValidationException.class)
                        .hasMessageContaining("year");
            }
        }

        @Test
        @DisplayName("requires make, model, and year")
        void requiredFields() {
            VehicleLegacyRequest noMake = request("CAR", null);
            noMake.setMake("  ");
            assertThatThrownBy(() -> service.createVehicle(noMake))
                    .isInstanceOf(VehicleValidationException.class)
                    .hasMessageContaining("make");

            VehicleLegacyRequest noModel = request("CAR", null);
            noModel.setModel(null);
            assertThatThrownBy(() -> service.createVehicle(noModel))
                    .isInstanceOf(VehicleValidationException.class)
                    .hasMessageContaining("model");

            VehicleLegacyRequest noYear = request("CAR", null);
            noYear.setYear(null);
            assertThatThrownBy(() -> service.createVehicle(noYear))
                    .isInstanceOf(VehicleValidationException.class)
                    .hasMessageContaining("year");
        }

        @Test
        @DisplayName("treats a null VIN as absent but rejects a blank one on the id-keyed create")
        void optionalVinSemantics() {
            service.createVehicle(request("CAR", null));
            assertThat(captureSaved().getVIN()).isNull();

            // Null means "no VIN recorded"; blank means the caller sent something broken.
            assertThatThrownBy(() -> service.createVehicle(request("CAR", "   ")))
                    .isInstanceOf(VehicleValidationException.class)
                    .hasMessageContaining("blank");
        }

        @Test
        @DisplayName("trims the VIN it stores")
        void vinIsTrimmed() {
            service.createVehicle(request("CAR", "  1HGCM82633A004352  "));

            assertThat(captureSaved().getVIN()).isEqualTo("1HGCM82633A004352");
        }
    }

    @Nested
    @DisplayName("VIN-keyed endpoints")
    class VinKeyed {

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        @DisplayName("requires a VIN on the VIN-keyed create")
        void vinRequired(String vin) {
            // A vehicle created by VIN without one would be unreachable through these endpoints.
            assertThatThrownBy(() -> service.createVehicleByVin(request("CAR", vin.isEmpty() ? null : vin)))
                    .isInstanceOf(VehicleValidationException.class)
                    .hasMessageContaining("vin");

            verifyNoInteractions(vehicleDao);
        }

        @Test
        @DisplayName("normalizes the VIN before lookup so a padded query still matches")
        void lookupNormalizesVin() {
            Car car = new Car();
            car.setVIN("1HGCM82633A004352");
            when(vehicleDao.findByVIN("1HGCM82633A004352")).thenReturn(Optional.of(car));

            assertThat(service.getVehicleByVin("  1HGCM82633A004352  ")).isPresent();
        }

        @Test
        @DisplayName("updates through the VIN without letting the update change identity fields")
        void updateByVin() {
            Car existing = new Car();
            existing.setVIN("1HGCM82633A004352");
            existing.setMake("Honda");
            when(vehicleDao.findByVIN("1HGCM82633A004352")).thenReturn(Optional.of(existing));

            VehicleLegacyRequest update = request("CAR", "SOMETHING-ELSE");
            update.setMake("Toyota");
            service.updateVehicleByVin("1HGCM82633A004352", update);

            // Core fields move; the VIN key itself is not overwritten by the payload.
            assertThat(existing.getMake()).isEqualTo("Toyota");
            assertThat(existing.getVIN()).isEqualTo("1HGCM82633A004352");
        }

        @Test
        @DisplayName("reports a delete of an unknown VIN as false without deleting")
        void deleteUnknownVin() {
            when(vehicleDao.findByVIN("1HGCM82633A004352")).thenReturn(Optional.empty());

            assertThat(service.deleteVehicleByVin("1HGCM82633A004352")).isFalse();

            verify(vehicleDao, never()).deleteByVIN(any());
        }

        @Test
        @DisplayName("deletes a known VIN and reports true")
        void deleteKnownVin() {
            Car car = new Car();
            when(vehicleDao.findByVIN("1HGCM82633A004352")).thenReturn(Optional.of(car));

            assertThat(service.deleteVehicleByVin(" 1HGCM82633A004352 ")).isTrue();

            verify(vehicleDao).deleteByVIN("1HGCM82633A004352");
        }
    }

    @Nested
    @DisplayName("id-keyed endpoints")
    class IdKeyed {

        @Test
        @DisplayName("updates core fields in place and reports an unknown id as empty")
        void updateSemantics() {
            Car existing = new Car();
            existing.setId(ID);
            existing.setMake("Honda");
            when(vehicleDao.findById(ID)).thenReturn(Optional.of(existing));

            assertThat(service.updateVehicle(ID, request("CAR", null))).isPresent();
            assertThat(existing.getMake()).isEqualTo("Toyota");

            when(vehicleDao.findById(ID)).thenReturn(Optional.empty());
            assertThat(service.updateVehicle(ID, request("CAR", null))).isEmpty();
        }

        @Test
        @DisplayName("deletes only what exists")
        void deleteSemantics() {
            when(vehicleDao.findById(ID)).thenReturn(Optional.empty());
            assertThat(service.deleteVehicle(ID)).isFalse();
            verify(vehicleDao, never()).deleteById(any());

            when(vehicleDao.findById(ID)).thenReturn(Optional.of(new Car()));
            assertThat(service.deleteVehicle(ID)).isTrue();
            verify(vehicleDao).deleteById(ID);
        }

        @Test
        @DisplayName("lists all vehicles mapped to responses")
        void listsAll() {
            Car car = new Car();
            car.setMake("Honda");
            when(vehicleDao.findAll()).thenReturn(java.util.List.of(car, new Van()));

            assertThat(service.getAllVehicles()).hasSize(2);
        }
    }
}
