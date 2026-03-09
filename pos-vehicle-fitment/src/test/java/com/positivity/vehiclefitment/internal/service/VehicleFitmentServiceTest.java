package com.positivity.vehiclefitment.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import com.positivity.vehiclefitment.internal.entity.Make;
import com.positivity.vehiclefitment.internal.entity.Manufacturer;
import com.positivity.vehiclefitment.internal.entity.Model;
import com.positivity.vehiclefitment.internal.entity.VehicleType;
import com.positivity.vehiclefitment.internal.entity.VehicleVariable;
import com.positivity.vehiclefitment.internal.entity.VehicleVariableValue;
import com.positivity.vehiclefitment.internal.exception.VehicleFitmentException;
import com.positivity.vehiclefitment.internal.repository.MakeRepository;
import com.positivity.vehiclefitment.internal.repository.ManufacturerRepository;
import com.positivity.vehiclefitment.internal.repository.ModelRepository;
import com.positivity.vehiclefitment.internal.repository.VehicleTypeRepository;
import com.positivity.vehiclefitment.internal.repository.VehicleVariableRepository;
import com.positivity.vehiclefitment.internal.repository.VehicleVariableValueRepository;

/**
 * Unit tests for VehicleFitmentServiceImpl.
 * Mocks RestClient to avoid real HTTP calls to NHTSA API.
 */
@ExtendWith(MockitoExtension.class)
class VehicleFitmentServiceTest {

        private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T12:00:00Z"), ZoneOffset.UTC);
        private static final UUID MANUFACTURER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        private static final UUID MAKE_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        private static final UUID VARIABLE_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

        @Spy
        Clock clock = TEST_CLOCK;

        @Mock
        private ManufacturerRepository manufacturerRepository;

        @Mock
        private MakeRepository makeRepository;

        @Mock
        private ModelRepository modelRepository;

        @Mock
        private VehicleTypeRepository vehicleTypeRepository;

        @Mock
        private RestClient restClient;

        @Mock
        private VehicleVariableRepository vehicleVariableRepository;

        @Mock
        private VehicleVariableValueRepository vehicleVariableValueRepository;

        @InjectMocks
        private VehicleFitmentServiceImpl service;

        @SuppressWarnings("rawtypes")
        @Mock
        private RestClient.RequestHeadersUriSpec requestUriSpec;

        @SuppressWarnings("rawtypes")
        @Mock
        private RestClient.RequestHeadersSpec requestHeadersSpec;

        @Mock
        private RestClient.ResponseSpec responseSpec;

        @BeforeEach
        @SuppressWarnings("unchecked")
        void setUpRestClient() {
                lenient().when(restClient.get()).thenReturn(requestUriSpec);
                lenient().when(requestUriSpec.uri(anyString())).thenReturn(requestHeadersSpec);
                lenient().when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        }

        // ─── getVehicleVariables ───────────────────────────────────────────────────

        @Test
        void getVehicleVariables_emptyCache_callsApiAndReturnsData() {
                VehicleVariable saved = new VehicleVariable();
                saved.setName("ABS");
                saved.setCacheTimestamp(LocalDateTime.now(TEST_CLOCK));

                when(vehicleVariableRepository.findAll())
                                .thenReturn(List.of())
                                .thenReturn(List.of(saved));

                when(responseSpec.body(String.class))
                                .thenReturn("{\"Results\":[{\"Name\":\"ABS\",\"Description\":\"Anti-lock Braking\"}]}");

                List<VehicleVariable> result = service.getVehicleVariables();

                assertThat(result).hasSize(1);
                assertThat(result.get(0).getName()).isEqualTo("ABS");
        }

        @Test
        void getVehicleVariables_parseError_throwsVehicleFitmentException() {
                when(vehicleVariableRepository.findAll()).thenReturn(List.of());
                when(responseSpec.body(String.class)).thenReturn("not-valid-json{{{");

                assertThatThrownBy(() -> service.getVehicleVariables())
                                .isInstanceOf(VehicleFitmentException.class)
                                .hasMessageContaining("Failed to parse vehicle variables");
        }

        // ─── getVehicleVariableValues ──────────────────────────────────────────────

        @Test
        void getVehicleVariableValues_emptyCache_callsApiAndReturnsData() {
                VehicleVariableValue saved = new VehicleVariableValue();
                saved.setVariableId(VARIABLE_ID);
                saved.setValue("Car");
                saved.setCacheTimestamp(LocalDateTime.now(TEST_CLOCK));

                when(vehicleVariableValueRepository.findByVariableId(VARIABLE_ID))
                                .thenReturn(List.of())
                                .thenReturn(List.of(saved));

                when(responseSpec.body(String.class))
                                .thenReturn("{\"Results\":[{\"Value\":\"Car\",\"ValueId\":\"1\"}]}");

                List<VehicleVariableValue> result = service.getVehicleVariableValues(VARIABLE_ID);

                assertThat(result).hasSize(1);
                assertThat(result.get(0).getValue()).isEqualTo("Car");
        }

        @Test
        void getVehicleVariableValues_parseError_throwsVehicleFitmentException() {
                when(vehicleVariableValueRepository.findByVariableId(VARIABLE_ID)).thenReturn(List.of());
                when(responseSpec.body(String.class)).thenReturn("{bad-json}");

                assertThatThrownBy(() -> service.getVehicleVariableValues(VARIABLE_ID))
                                .isInstanceOf(VehicleFitmentException.class)
                                .hasMessageContaining("Failed to parse vehicle variable values");
        }

        // ─── getManufacturers ──────────────────────────────────────────────────────

        @Test
        void getManufacturers_emptyCache_callsApiAndReturnsData() {
                Manufacturer saved = new Manufacturer();
                saved.setName("Toyota Motor Corp");
                saved.setCacheTimestamp(LocalDateTime.now(TEST_CLOCK));

                when(manufacturerRepository.findAll())
                                .thenReturn(List.of())
                                .thenReturn(List.of(saved));

                // Mfr_ID must be a valid UUID string (service calls UUID.fromString on it)
                when(responseSpec.body(String.class))
                                .thenReturn("{\"Results\":[{\"Mfr_ID\":\"" + MANUFACTURER_ID
                                                + "\",\"Mfr_CommonName\":\"Toyota Motor Corp\"}]}");

                List<Manufacturer> result = service.getManufacturers();

                assertThat(result).hasSize(1);
                assertThat(result.get(0).getName()).isEqualTo("Toyota Motor Corp");
        }

        @Test
        void getManufacturers_parseError_throwsVehicleFitmentException() {
                when(manufacturerRepository.findAll()).thenReturn(List.of());
                when(responseSpec.body(String.class)).thenReturn("{bad-json}");

                assertThatThrownBy(() -> service.getManufacturers())
                                .isInstanceOf(VehicleFitmentException.class)
                                .hasMessageContaining("Failed to parse manufacturers");
        }

        // ─── getMakesByManufacturer ────────────────────────────────────────────────

        @Test
        void getMakesByManufacturer_manufacturerNotFound_throwsIllegalArgumentException() {
                when(manufacturerRepository.findById(MANUFACTURER_ID)).thenReturn(Optional.empty());

                assertThatThrownBy(() -> service.getMakesByManufacturer(MANUFACTURER_ID))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessageContaining("Manufacturer not found");
        }

        @Test
        void getMakesByManufacturer_emptyCache_callsApiAndReturnsData() {
                Manufacturer manufacturer = new Manufacturer();
                manufacturer.setId(MANUFACTURER_ID);
                manufacturer.setName("Toyota");
                manufacturer.setCacheTimestamp(LocalDateTime.now(TEST_CLOCK));

                Make savedMake = new Make();
                savedMake.setName("Toyota");
                savedMake.setCacheTimestamp(LocalDateTime.now(TEST_CLOCK));

                when(manufacturerRepository.findById(MANUFACTURER_ID)).thenReturn(Optional.of(manufacturer));
                when(makeRepository.findByManufacturerId(MANUFACTURER_ID))
                                .thenReturn(List.of())
                                .thenReturn(List.of(savedMake));

                // Make_ID must be a valid UUID string
                when(responseSpec.body(String.class))
                                .thenReturn("{\"Results\":[{\"Make_ID\":\"" + MAKE_ID
                                                + "\",\"Make_Name\":\"Toyota\"}]}");

                List<Make> result = service.getMakesByManufacturer(MANUFACTURER_ID);

                assertThat(result).hasSize(1);
                assertThat(result.get(0).getName()).isEqualTo("Toyota");
        }

        @Test
        void getMakesByManufacturer_parseError_throwsVehicleFitmentException() {
                Manufacturer manufacturer = new Manufacturer();
                manufacturer.setId(MANUFACTURER_ID);
                manufacturer.setName("Toyota");
                manufacturer.setCacheTimestamp(LocalDateTime.now(TEST_CLOCK));

                when(manufacturerRepository.findById(MANUFACTURER_ID)).thenReturn(Optional.of(manufacturer));
                when(makeRepository.findByManufacturerId(MANUFACTURER_ID)).thenReturn(List.of());
                when(responseSpec.body(String.class)).thenReturn("{bad-json}");

                assertThatThrownBy(() -> service.getMakesByManufacturer(MANUFACTURER_ID))
                                .isInstanceOf(VehicleFitmentException.class)
                                .hasMessageContaining("Failed to parse makes");
        }

        // ─── getModelsByMake ───────────────────────────────────────────────────────

        @Test
        void getModelsByMake_makeNotFound_throwsIllegalArgumentException() {
                when(makeRepository.findById(MAKE_ID)).thenReturn(Optional.empty());

                assertThatThrownBy(() -> service.getModelsByMake(MAKE_ID))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessageContaining("Make not found");
        }

        @Test
        void getModelsByMake_emptyCache_callsApiAndReturnsData() {
                Make make = new Make();
                make.setId(MAKE_ID);
                make.setName("Toyota");
                make.setCacheTimestamp(LocalDateTime.now(TEST_CLOCK));

                UUID modelId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
                Model savedModel = new Model();
                savedModel.setName("Camry");
                savedModel.setCacheTimestamp(LocalDateTime.now(TEST_CLOCK));

                when(makeRepository.findById(MAKE_ID)).thenReturn(Optional.of(make));
                when(modelRepository.findByMakeId(MAKE_ID))
                                .thenReturn(List.of())
                                .thenReturn(List.of(savedModel));

                // Model_ID must be a valid UUID string
                when(responseSpec.body(String.class))
                                .thenReturn("{\"Results\":[{\"Model_ID\":\"" + modelId
                                                + "\",\"Model_Name\":\"Camry\"}]}");

                List<Model> result = service.getModelsByMake(MAKE_ID);

                assertThat(result).hasSize(1);
                assertThat(result.get(0).getName()).isEqualTo("Camry");
        }

        @Test
        void getModelsByMake_parseError_throwsVehicleFitmentException() {
                Make make = new Make();
                make.setId(MAKE_ID);
                make.setName("Toyota");
                make.setCacheTimestamp(LocalDateTime.now(TEST_CLOCK));

                when(makeRepository.findById(MAKE_ID)).thenReturn(Optional.of(make));
                when(modelRepository.findByMakeId(MAKE_ID)).thenReturn(List.of());
                when(responseSpec.body(String.class)).thenReturn("{bad-json}");

                assertThatThrownBy(() -> service.getModelsByMake(MAKE_ID))
                                .isInstanceOf(VehicleFitmentException.class)
                                .hasMessageContaining("Failed to parse models");
        }

        // ─── getVehicleTypesForMake ────────────────────────────────────────────────

        @Test
        void getVehicleTypesForMake_makeNotFound_throwsIllegalArgumentException() {
                when(makeRepository.findById(MAKE_ID)).thenReturn(Optional.empty());

                assertThatThrownBy(() -> service.getVehicleTypesForMake(MAKE_ID))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessageContaining("Make not found");
        }

        @Test
        void getVehicleTypesForMake_emptyCache_callsApiAndReturnsData() {
                Make make = new Make();
                make.setId(MAKE_ID);
                make.setName("Toyota");
                make.setCacheTimestamp(LocalDateTime.now(TEST_CLOCK));

                VehicleType savedType = new VehicleType();
                savedType.setVehicleTypeName("Passenger Car");
                savedType.setCacheTimestamp(LocalDateTime.now(TEST_CLOCK));

                when(makeRepository.findById(MAKE_ID)).thenReturn(Optional.of(make));
                when(vehicleTypeRepository.findByMakeId(MAKE_ID))
                                .thenReturn(List.of())
                                .thenReturn(List.of(savedType));

                when(responseSpec.body(String.class))
                                .thenReturn("{\"Results\":[{\"VehicleTypeId\":\"1\",\"VehicleTypeName\":\"Passenger Car\"}]}");

                List<VehicleType> result = service.getVehicleTypesForMake(MAKE_ID);

                assertThat(result).hasSize(1);
                assertThat(result.get(0).getVehicleTypeName()).isEqualTo("Passenger Car");
        }

        @Test
        void getVehicleTypesForMake_parseError_throwsVehicleFitmentException() {
                Make make = new Make();
                make.setId(MAKE_ID);
                make.setName("Toyota");
                make.setCacheTimestamp(LocalDateTime.now(TEST_CLOCK));

                when(makeRepository.findById(MAKE_ID)).thenReturn(Optional.of(make));
                when(vehicleTypeRepository.findByMakeId(MAKE_ID)).thenReturn(List.of());
                when(responseSpec.body(String.class)).thenReturn("{bad-json}");

                assertThatThrownBy(() -> service.getVehicleTypesForMake(MAKE_ID))
                                .isInstanceOf(VehicleFitmentException.class)
                                .hasMessageContaining("Failed to parse vehicle types for make");
        }
}
