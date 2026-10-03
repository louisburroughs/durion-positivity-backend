package com.positivity.vehiclefitment.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.vehiclefitment.internal.dto.MakeResponse;
import com.positivity.vehiclefitment.internal.dto.ManufacturerResponse;
import com.positivity.vehiclefitment.internal.dto.ModelResponse;
import com.positivity.vehiclefitment.internal.dto.VehicleTypeResponse;
import com.positivity.vehiclefitment.internal.entity.Make;
import com.positivity.vehiclefitment.internal.entity.Manufacturer;
import com.positivity.vehiclefitment.internal.entity.Model;
import com.positivity.vehiclefitment.internal.entity.PartFitmentEntity;
import com.positivity.vehiclefitment.internal.entity.VehicleType;
import com.positivity.vehiclefitment.internal.entity.VehicleVariable;
import com.positivity.vehiclefitment.internal.entity.VehicleVariableValue;
import com.positivity.vehiclefitment.internal.exception.VehicleFitmentException;
import com.positivity.vehiclefitment.internal.repository.MakeRepository;
import com.positivity.vehiclefitment.internal.repository.ManufacturerRepository;
import com.positivity.vehiclefitment.internal.repository.ModelRepository;
import com.positivity.vehiclefitment.internal.repository.PartFitmentRepository;
import com.positivity.vehiclefitment.internal.repository.VehicleTypeRepository;
import com.positivity.vehiclefitment.internal.repository.VehicleVariableRepository;
import com.positivity.vehiclefitment.internal.repository.VehicleVariableValueRepository;
import com.positivity.vehiclefitment.internal.service.dto.CreatePartFitmentRequest;
import com.positivity.vehiclefitment.internal.service.dto.PartFitmentResponse;
import java.nio.charset.StandardCharsets;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

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
    private static final UUID MODEL_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    /** vPIC's own numeric ids (#2416): vPIC returns integers and resolves its paths by them. */
    private static final long MFR_VPIC_ID = 955L;

    private static final long MAKE_VPIC_ID = 441L;
    private static final long MODEL_VPIC_ID = 1685L;
    /**
     * Must match {@code VehicleFitmentServiceImpl.NHTSA_API_BASE}. Spelled out here rather than
     * read from the service so a wrong base path there fails the suite instead of agreeing with it.
     */
    private static final String VPIC_BASE = "https://vpic.nhtsa.dot.gov/api/vehicles";

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
    private PartFitmentRepository partFitmentRepository;

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

        when(vehicleVariableRepository.findAll()).thenReturn(List.of()).thenReturn(List.of(saved));

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
        VehicleVariable variable = new VehicleVariable();
        variable.setId(VARIABLE_ID);
        VehicleVariableValue saved = new VehicleVariableValue();
        saved.setVariable(variable);
        saved.setValue("Car");
        saved.setCacheTimestamp(LocalDateTime.now(TEST_CLOCK));

        when(vehicleVariableValueRepository.findByVariable_Id(VARIABLE_ID))
                .thenReturn(List.of())
                .thenReturn(List.of(saved));

        when(responseSpec.body(String.class)).thenReturn("{\"Results\":[{\"Value\":\"Car\",\"ValueId\":\"1\"}]}");

        List<VehicleVariableValue> result = service.getVehicleVariableValues(VARIABLE_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getValue()).isEqualTo("Car");
    }

    @Test
    void getVehicleVariableValues_parseError_throwsVehicleFitmentException() {
        when(vehicleVariableValueRepository.findByVariable_Id(VARIABLE_ID)).thenReturn(List.of());
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

        when(manufacturerRepository.findAll()).thenReturn(List.of()).thenReturn(List.of(saved));

        when(responseSpec.body(String.class))
                .thenReturn(
                        "{\"Results\":[{\"Mfr_ID\":" + MFR_VPIC_ID + ",\"Mfr_CommonName\":\"Toyota Motor Corp\"}]}");

        List<ManufacturerResponse> result = service.getManufacturers();

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
        manufacturer.setNhtsaId(MFR_VPIC_ID);
        manufacturer.setName("Toyota");
        manufacturer.setCacheTimestamp(LocalDateTime.now(TEST_CLOCK));

        Make savedMake = new Make();
        savedMake.setName("Toyota");
        savedMake.setCacheTimestamp(LocalDateTime.now(TEST_CLOCK));

        when(manufacturerRepository.findById(MANUFACTURER_ID)).thenReturn(Optional.of(manufacturer));
        when(makeRepository.findByManufacturerId(MANUFACTURER_ID))
                .thenReturn(List.of())
                .thenReturn(List.of(savedMake));

        when(responseSpec.body(String.class))
                .thenReturn("{\"Results\":[{\"Make_ID\":" + MAKE_VPIC_ID + ",\"Make_Name\":\"Toyota\"}]}");

        List<MakeResponse> result = service.getMakesByManufacturer(MANUFACTURER_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Toyota");
    }

    @Test
    void getMakesByManufacturer_parseError_throwsVehicleFitmentException() {
        Manufacturer manufacturer = new Manufacturer();
        manufacturer.setId(MANUFACTURER_ID);
        manufacturer.setNhtsaId(MFR_VPIC_ID);
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
        make.setNhtsaId(MAKE_VPIC_ID);
        make.setName("Toyota");
        make.setCacheTimestamp(LocalDateTime.now(TEST_CLOCK));

        Model savedModel = new Model();
        savedModel.setName("Camry");
        savedModel.setCacheTimestamp(LocalDateTime.now(TEST_CLOCK));

        when(makeRepository.findById(MAKE_ID)).thenReturn(Optional.of(make));
        when(modelRepository.findByMakeId(MAKE_ID)).thenReturn(List.of()).thenReturn(List.of(savedModel));

        when(responseSpec.body(String.class))
                .thenReturn("{\"Results\":[{\"Model_ID\":" + MODEL_VPIC_ID + ",\"Model_Name\":\"Camry\"}]}");

        List<ModelResponse> result = service.getModelsByMake(MAKE_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Camry");
    }

    @Test
    void getModelsByMake_parseError_throwsVehicleFitmentException() {
        Make make = new Make();
        make.setId(MAKE_ID);
        make.setNhtsaId(MAKE_VPIC_ID);
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
        make.setNhtsaId(MAKE_VPIC_ID);
        make.setName("Toyota");
        make.setCacheTimestamp(LocalDateTime.now(TEST_CLOCK));

        VehicleType savedType = new VehicleType();
        savedType.setVehicleTypeName("Passenger Car");
        savedType.setCacheTimestamp(LocalDateTime.now(TEST_CLOCK));

        when(makeRepository.findById(MAKE_ID)).thenReturn(Optional.of(make));
        when(vehicleTypeRepository.findByMakeId(MAKE_ID)).thenReturn(List.of()).thenReturn(List.of(savedType));

        when(responseSpec.body(String.class))
                .thenReturn("{\"Results\":[{\"VehicleTypeId\":1,\"VehicleTypeName\":\"Passenger Car\"}]}");

        List<VehicleTypeResponse> result = service.getVehicleTypesForMake(MAKE_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getVehicleTypeName()).isEqualTo("Passenger Car");
    }

    @Test
    void getVehicleTypesForMake_parseError_throwsVehicleFitmentException() {
        Make make = new Make();
        make.setId(MAKE_ID);
        make.setNhtsaId(MAKE_VPIC_ID);
        make.setName("Toyota");
        make.setCacheTimestamp(LocalDateTime.now(TEST_CLOCK));

        when(makeRepository.findById(MAKE_ID)).thenReturn(Optional.of(make));
        when(vehicleTypeRepository.findByMakeId(MAKE_ID)).thenReturn(List.of());
        when(responseSpec.body(String.class)).thenReturn("{bad-json}");

        assertThatThrownBy(() -> service.getVehicleTypesForMake(MAKE_ID))
                .isInstanceOf(VehicleFitmentException.class)
                .hasMessageContaining("Failed to parse vehicle types for make");
    }

    // ─── 24h vPIC cache (#2395) ────────────────────────────────────────────────
    //
    // All six lookups share one rule: serve the cached rows while they are inside the
    // 24-hour window, otherwise refetch from vPIC. The helper behind that rule used to be
    // named isCacheExpired while answering "is still fresh", and three call sites negated
    // it on top of that — those three called vPIC on every request while the cache was
    // warm and never again once it went stale. Each method is pinned on both sides of the
    // window so the name and the call sites cannot drift apart again.
    //
    // The stale-side tests also pin the request URL: vPIC serves under /api/vehicles, and
    // /v1/vehicles redirects to its NotFound page.

    @Test
    void getVehicleVariables_freshCache_servesCacheWithoutCallingVpic() {
        VehicleVariable cached = vehicleVariable(hourOld());
        when(vehicleVariableRepository.findAll()).thenReturn(List.of(cached));

        assertThat(service.getVehicleVariables()).containsExactly(cached);

        verify(restClient, never()).get();
    }

    @Test
    void getVehicleVariables_staleCache_refetchesFromVpicOnce() {
        when(vehicleVariableRepository.findAll()).thenReturn(List.of(vehicleVariable(dayOld())));
        when(responseSpec.body(String.class))
                .thenReturn("{\"Results\":[{\"Name\":\"ABS\",\"Description\":\"Anti-lock Braking\"}]}");

        service.getVehicleVariables();

        verifySingleVpicCall(VPIC_BASE + "/GetVehicleVariableList?format=json");
    }

    @Test
    void getVehicleVariableValues_freshCache_servesCacheWithoutCallingVpic() {
        VehicleVariableValue cached = vehicleVariableValue(hourOld());
        when(vehicleVariableValueRepository.findByVariable_Id(VARIABLE_ID)).thenReturn(List.of(cached));

        assertThat(service.getVehicleVariableValues(VARIABLE_ID)).containsExactly(cached);

        verify(restClient, never()).get();
    }

    @Test
    void getVehicleVariableValues_staleCache_refetchesFromVpicOnce() {
        when(vehicleVariableValueRepository.findByVariable_Id(VARIABLE_ID))
                .thenReturn(List.of(vehicleVariableValue(dayOld())));
        when(responseSpec.body(String.class)).thenReturn("{\"Results\":[{\"Value\":\"Car\",\"ValueId\":\"1\"}]}");

        service.getVehicleVariableValues(VARIABLE_ID);

        verifySingleVpicCall(VPIC_BASE + "/GetVehicleVariableValuesList/" + VARIABLE_ID + "?format=json");
    }

    @Test
    void getManufacturers_freshCache_servesCacheWithoutCallingVpic() {
        when(manufacturerRepository.findAll()).thenReturn(List.of(manufacturer(hourOld())));

        assertThat(service.getManufacturers())
                .singleElement()
                .extracting(ManufacturerResponse::getName)
                .isEqualTo("Toyota");

        verify(restClient, never()).get();
    }

    @Test
    void getManufacturers_staleCache_refetchesFromVpicOnce() {
        when(manufacturerRepository.findAll()).thenReturn(List.of(manufacturer(dayOld())));
        when(responseSpec.body(String.class))
                .thenReturn("{\"Results\":[{\"Mfr_ID\":" + MFR_VPIC_ID + ",\"Mfr_CommonName\":\"Toyota\"}]}");

        service.getManufacturers();

        verifySingleVpicCall(VPIC_BASE + "/getallmanufacturers?format=json");
    }

    @Test
    void getMakesByManufacturer_freshCache_servesCacheWithoutCallingVpic() {
        when(manufacturerRepository.findById(MANUFACTURER_ID)).thenReturn(Optional.of(manufacturer(hourOld())));
        when(makeRepository.findByManufacturerId(MANUFACTURER_ID)).thenReturn(List.of(make(hourOld())));

        assertThat(service.getMakesByManufacturer(MANUFACTURER_ID))
                .singleElement()
                .extracting(MakeResponse::getName)
                .isEqualTo("Toyota");

        verify(restClient, never()).get();
    }

    @Test
    void getMakesByManufacturer_staleCache_refetchesFromVpicOnce() {
        when(manufacturerRepository.findById(MANUFACTURER_ID)).thenReturn(Optional.of(manufacturer(hourOld())));
        when(makeRepository.findByManufacturerId(MANUFACTURER_ID)).thenReturn(List.of(make(dayOld())));
        when(responseSpec.body(String.class))
                .thenReturn("{\"Results\":[{\"Make_ID\":" + MAKE_VPIC_ID + ",\"Make_Name\":\"Toyota\"}]}");

        service.getMakesByManufacturer(MANUFACTURER_ID);

        verifySingleVpicCall(VPIC_BASE + "/GetMakeForManufacturer/" + MFR_VPIC_ID + "?format=json");
    }

    @Test
    void getModelsByMake_freshCache_servesCacheWithoutCallingVpic() {
        when(makeRepository.findById(MAKE_ID)).thenReturn(Optional.of(make(hourOld())));
        when(modelRepository.findByMakeId(MAKE_ID)).thenReturn(List.of(model(hourOld())));

        assertThat(service.getModelsByMake(MAKE_ID))
                .singleElement()
                .extracting(ModelResponse::getName)
                .isEqualTo("Camry");

        verify(restClient, never()).get();
    }

    @Test
    void getModelsByMake_staleCache_refetchesFromVpicOnce() {
        when(makeRepository.findById(MAKE_ID)).thenReturn(Optional.of(make(hourOld())));
        when(modelRepository.findByMakeId(MAKE_ID)).thenReturn(List.of(model(dayOld())));
        when(responseSpec.body(String.class))
                .thenReturn("{\"Results\":[{\"Model_ID\":" + MODEL_VPIC_ID + ",\"Model_Name\":\"Camry\"}]}");

        service.getModelsByMake(MAKE_ID);

        verifySingleVpicCall(VPIC_BASE + "/GetModelsForMakeId/" + MAKE_VPIC_ID + "?format=json");
    }

    @Test
    void getVehicleTypesForMake_freshCache_servesCacheWithoutCallingVpic() {
        when(makeRepository.findById(MAKE_ID)).thenReturn(Optional.of(make(hourOld())));
        when(vehicleTypeRepository.findByMakeId(MAKE_ID)).thenReturn(List.of(vehicleType(hourOld())));

        assertThat(service.getVehicleTypesForMake(MAKE_ID))
                .singleElement()
                .extracting(VehicleTypeResponse::getVehicleTypeName)
                .isEqualTo("Passenger Car");

        verify(restClient, never()).get();
    }

    @Test
    void getVehicleTypesForMake_staleCache_refetchesFromVpicOnce() {
        when(makeRepository.findById(MAKE_ID)).thenReturn(Optional.of(make(hourOld())));
        when(vehicleTypeRepository.findByMakeId(MAKE_ID)).thenReturn(List.of(vehicleType(dayOld())));
        when(responseSpec.body(String.class))
                .thenReturn("{\"Results\":[{\"VehicleTypeId\":1,\"VehicleTypeName\":\"Passenger Car\"}]}");

        service.getVehicleTypesForMake(MAKE_ID);

        verifySingleVpicCall(VPIC_BASE + "/GetVehicleTypesForMakeId/" + MAKE_VPIC_ID + "?format=json");
    }

    // ─── vPIC numeric ids (#2416) ──────────────────────────────────────────────
    //
    // vPIC identifies manufacturers, makes and models by integers (Mfr_ID 955, not a UUID)
    // and resolves its dependent paths by those integers. The service used to parse them
    // with UUID.fromString, which throws on every real payload, and to put the local UUID
    // into the dependent URLs, which vPIC cannot resolve. The payloads below are shaped
    // like vPIC's own responses, integer ids included.

    @Test
    void getManufacturers_integerMfrId_keepsVpicIdAndDerivesLocalId() {
        when(manufacturerRepository.findAll()).thenReturn(List.of());
        when(responseSpec.body(String.class))
                .thenReturn("{\"Count\":1,\"Message\":\"Response returned successfully\",\"SearchCriteria\":null,"
                        + "\"Results\":[{\"Country\":\"UNITED STATES (USA)\",\"Mfr_CommonName\":\"Tesla\","
                        + "\"Mfr_ID\":955,\"Mfr_Name\":\"TESLA, INC.\",\"VehicleTypes\":[]}]}");

        service.getManufacturers();

        ArgumentCaptor<Manufacturer> saved = ArgumentCaptor.forClass(Manufacturer.class);
        verify(manufacturerRepository).save(saved.capture());
        assertThat(saved.getValue().getNhtsaId()).isEqualTo(955L);
        assertThat(saved.getValue().getId())
                .isEqualTo(UUID.nameUUIDFromBytes("manufacturer-955".getBytes(StandardCharsets.UTF_8)));
        assertThat(saved.getValue().getName()).isEqualTo("Tesla");
    }

    @Test
    void getMakesByManufacturer_requestsByVpicIdAndKeepsIntegerMakeId() {
        when(manufacturerRepository.findById(MANUFACTURER_ID)).thenReturn(Optional.of(manufacturer(hourOld())));
        when(makeRepository.findByManufacturerId(MANUFACTURER_ID)).thenReturn(List.of());
        when(responseSpec.body(String.class))
                .thenReturn("{\"Count\":1,\"Message\":\"Results returned successfully\",\"SearchCriteria\":"
                        + "\"Manufacturer:955\",\"Results\":[{\"Make_ID\":441,\"Make_Name\":\"TESLA\","
                        + "\"Mfr_Name\":\"TESLA, INC.\"}]}");

        service.getMakesByManufacturer(MANUFACTURER_ID);

        verifySingleVpicCall(VPIC_BASE + "/GetMakeForManufacturer/955?format=json");
        ArgumentCaptor<Make> saved = ArgumentCaptor.forClass(Make.class);
        verify(makeRepository).save(saved.capture());
        assertThat(saved.getValue().getNhtsaId()).isEqualTo(441L);
        assertThat(saved.getValue().getId())
                .isEqualTo(UUID.nameUUIDFromBytes("make-441".getBytes(StandardCharsets.UTF_8)));
        assertThat(saved.getValue().getName()).isEqualTo("TESLA");
    }

    @Test
    void getModelsByMake_requestsByVpicIdAndKeepsIntegerModelId() {
        when(makeRepository.findById(MAKE_ID)).thenReturn(Optional.of(make(hourOld())));
        when(modelRepository.findByMakeId(MAKE_ID)).thenReturn(List.of());
        when(responseSpec.body(String.class))
                .thenReturn("{\"Count\":1,\"Message\":\"Response returned successfully\",\"SearchCriteria\":"
                        + "\"Make ID:441\",\"Results\":[{\"Make_ID\":441,\"Make_Name\":\"TESLA\","
                        + "\"Model_ID\":1685,\"Model_Name\":\"Model S\"}]}");

        service.getModelsByMake(MAKE_ID);

        verifySingleVpicCall(VPIC_BASE + "/GetModelsForMakeId/441?format=json");
        ArgumentCaptor<Model> saved = ArgumentCaptor.forClass(Model.class);
        verify(modelRepository).save(saved.capture());
        assertThat(saved.getValue().getNhtsaId()).isEqualTo(1685L);
        assertThat(saved.getValue().getId())
                .isEqualTo(UUID.nameUUIDFromBytes("model-1685".getBytes(StandardCharsets.UTF_8)));
        assertThat(saved.getValue().getName()).isEqualTo("Model S");
    }

    @Test
    void getVehicleTypesForMake_requestsByVpicId() {
        when(makeRepository.findById(MAKE_ID)).thenReturn(Optional.of(make(hourOld())));
        when(vehicleTypeRepository.findByMakeId(MAKE_ID)).thenReturn(List.of());
        when(responseSpec.body(String.class))
                .thenReturn(
                        "{\"Count\":1,\"Message\":\"Response returned successfully\",\"SearchCriteria\":"
                                + "\"Make ID: 441\",\"Results\":[{\"VehicleTypeId\":2,\"VehicleTypeName\":\"Passenger Car\"}]}");

        service.getVehicleTypesForMake(MAKE_ID);

        verifySingleVpicCall(VPIC_BASE + "/GetVehicleTypesForMakeId/441?format=json");
        ArgumentCaptor<VehicleType> saved = ArgumentCaptor.forClass(VehicleType.class);
        verify(vehicleTypeRepository).save(saved.capture());
        assertThat(saved.getValue().getVehicleTypeId()).isEqualTo("2");
    }

    @Test
    void getManufacturers_nonIntegerMfrId_throwsAndSavesNothing() {
        when(manufacturerRepository.findAll()).thenReturn(List.of());
        when(responseSpec.body(String.class))
                .thenReturn("{\"Results\":[{\"Mfr_ID\":\"" + MANUFACTURER_ID + "\",\"Mfr_CommonName\":\"Tesla\"}]}");

        assertThatThrownBy(() -> service.getManufacturers())
                .isInstanceOf(VehicleFitmentException.class)
                .hasMessageContaining("Failed to parse manufacturers");
        verify(manufacturerRepository, never()).save(any(Manufacturer.class));
    }

    @Test
    void getMakesByManufacturer_manufacturerWithoutVpicId_servesCacheWithoutCallingVpic() {
        Manufacturer local = manufacturer(hourOld());
        local.setNhtsaId(null);
        when(manufacturerRepository.findById(MANUFACTURER_ID)).thenReturn(Optional.of(local));
        when(makeRepository.findByManufacturerId(MANUFACTURER_ID)).thenReturn(List.of(make(dayOld())));

        assertThat(service.getMakesByManufacturer(MANUFACTURER_ID))
                .singleElement()
                .extracting(MakeResponse::getName)
                .isEqualTo("Toyota");

        verify(restClient, never()).get();
        verify(makeRepository, never()).deleteAll(any());
    }

    @Test
    void getModelsByMake_makeWithoutVpicId_servesCacheWithoutCallingVpic() {
        Make local = make(hourOld());
        local.setNhtsaId(null);
        when(makeRepository.findById(MAKE_ID)).thenReturn(Optional.of(local));
        when(modelRepository.findByMakeId(MAKE_ID)).thenReturn(List.of(model(dayOld())));

        assertThat(service.getModelsByMake(MAKE_ID))
                .singleElement()
                .extracting(ModelResponse::getName)
                .isEqualTo("Camry");

        verify(restClient, never()).get();
        verify(modelRepository, never()).deleteAll(any());
    }

    @Test
    void getVehicleTypesForMake_makeWithoutVpicId_servesCacheWithoutCallingVpic() {
        Make local = make(hourOld());
        local.setNhtsaId(null);
        when(makeRepository.findById(MAKE_ID)).thenReturn(Optional.of(local));
        when(vehicleTypeRepository.findByMakeId(MAKE_ID)).thenReturn(List.of(vehicleType(dayOld())));

        assertThat(service.getVehicleTypesForMake(MAKE_ID))
                .singleElement()
                .extracting(VehicleTypeResponse::getVehicleTypeName)
                .isEqualTo("Passenger Car");

        verify(restClient, never()).get();
        verify(vehicleTypeRepository, never()).deleteAll(any());
    }

    /** Cached an hour before {@link #TEST_CLOCK}: inside the 24-hour window. */
    private static LocalDateTime hourOld() {
        return LocalDateTime.now(TEST_CLOCK).minusHours(1);
    }

    /** Cached 25 hours before {@link #TEST_CLOCK}: outside the 24-hour window. */
    private static LocalDateTime dayOld() {
        return LocalDateTime.now(TEST_CLOCK).minusHours(25);
    }

    /** Exactly one outbound request, and to the given vPIC URL. */
    private void verifySingleVpicCall(String expectedUrl) {
        verify(restClient).get();
        verify(requestUriSpec).uri(expectedUrl);
    }

    private static VehicleVariable vehicleVariable(LocalDateTime cachedAt) {
        VehicleVariable variable = new VehicleVariable();
        variable.setId(VARIABLE_ID);
        variable.setName("ABS");
        variable.setCacheTimestamp(cachedAt);
        return variable;
    }

    private static VehicleVariableValue vehicleVariableValue(LocalDateTime cachedAt) {
        VehicleVariableValue value = new VehicleVariableValue();
        value.setValue("Car");
        value.setCacheTimestamp(cachedAt);
        return value;
    }

    private static Manufacturer manufacturer(LocalDateTime cachedAt) {
        Manufacturer manufacturer = new Manufacturer();
        manufacturer.setId(MANUFACTURER_ID);
        manufacturer.setNhtsaId(MFR_VPIC_ID);
        manufacturer.setName("Toyota");
        manufacturer.setCacheTimestamp(cachedAt);
        return manufacturer;
    }

    private static Make make(LocalDateTime cachedAt) {
        Make make = new Make();
        make.setId(MAKE_ID);
        make.setNhtsaId(MAKE_VPIC_ID);
        make.setName("Toyota");
        make.setCacheTimestamp(cachedAt);
        return make;
    }

    private static Model model(LocalDateTime cachedAt) {
        Model model = new Model();
        model.setId(MODEL_ID);
        model.setNhtsaId(MODEL_VPIC_ID);
        model.setName("Camry");
        model.setCacheTimestamp(cachedAt);
        return model;
    }

    private static VehicleType vehicleType(LocalDateTime cachedAt) {
        VehicleType vehicleType = new VehicleType();
        vehicleType.setVehicleTypeName("Passenger Car");
        vehicleType.setCacheTimestamp(cachedAt);
        return vehicleType;
    }

    // ─── createFitment ─────────────────────────────────────────────────────────

    @Test
    void createFitment_usesParentScopedLookup_forMake() {
        Manufacturer manufacturer = new Manufacturer();
        manufacturer.setId(MANUFACTURER_ID);
        manufacturer.setName("Toyota");

        Make savedMake = new Make();
        savedMake.setId(MAKE_ID);
        savedMake.setName("Camry");

        PartFitmentEntity savedEntity = new PartFitmentEntity();
        savedEntity.setId(UUID.randomUUID());
        savedEntity.setPartNumberId(1L);

        when(manufacturerRepository.findAllByNameIgnoreCase("Toyota")).thenReturn(List.of(manufacturer));
        when(makeRepository.findByManufacturerIdAndNameIgnoreCase(MANUFACTURER_ID, "Camry"))
                .thenReturn(Optional.empty());
        when(makeRepository.saveAndFlush(any(Make.class))).thenReturn(savedMake);
        when(partFitmentRepository.save(any(PartFitmentEntity.class))).thenReturn(savedEntity);

        CreatePartFitmentRequest request = new CreatePartFitmentRequest(1L);
        request.setManufacturerName("Toyota");
        request.setMakeName("Camry");

        PartFitmentResponse response = service.createFitment(request);

        verify(makeRepository).findByManufacturerIdAndNameIgnoreCase(MANUFACTURER_ID, "Camry");
        verify(makeRepository).saveAndFlush(any(Make.class));
        assertThat(response.getMakeName()).isEqualTo("Camry");
    }

    @Test
    void createFitment_usesParentScopedLookup_forModel() {
        Manufacturer manufacturer = new Manufacturer();
        manufacturer.setId(MANUFACTURER_ID);
        manufacturer.setName("Toyota");

        Make existingMake = new Make();
        existingMake.setId(MAKE_ID);
        existingMake.setName("Camry");

        UUID modelId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
        Model savedModel = new Model();
        savedModel.setId(modelId);
        savedModel.setName("Corolla");

        PartFitmentEntity savedEntity = new PartFitmentEntity();
        savedEntity.setId(UUID.randomUUID());
        savedEntity.setPartNumberId(1L);

        when(manufacturerRepository.findAllByNameIgnoreCase("Toyota")).thenReturn(List.of(manufacturer));
        when(makeRepository.findByManufacturerIdAndNameIgnoreCase(MANUFACTURER_ID, "Camry"))
                .thenReturn(Optional.of(existingMake));
        when(modelRepository.findByMakeIdAndNameIgnoreCase(MAKE_ID, "Corolla")).thenReturn(Optional.empty());
        when(modelRepository.saveAndFlush(any(Model.class))).thenReturn(savedModel);
        when(partFitmentRepository.save(any(PartFitmentEntity.class))).thenReturn(savedEntity);

        CreatePartFitmentRequest request = new CreatePartFitmentRequest(1L);
        request.setManufacturerName("Toyota");
        request.setMakeName("Camry");
        request.setModelName("Corolla");

        PartFitmentResponse response = service.createFitment(request);

        verify(modelRepository).findByMakeIdAndNameIgnoreCase(MAKE_ID, "Corolla");
        verify(modelRepository).saveAndFlush(any(Model.class));
        assertThat(response.getModelName()).isEqualTo("Corolla");
    }

    @Test
    void createFitment_usesParentScopedLookup_forVehicleType() {
        Manufacturer manufacturer = new Manufacturer();
        manufacturer.setId(MANUFACTURER_ID);
        manufacturer.setName("Toyota");

        Make existingMake = new Make();
        existingMake.setId(MAKE_ID);
        existingMake.setName("Camry");

        VehicleType savedType = new VehicleType();
        savedType.setVehicleTypeName("Passenger Car");

        PartFitmentEntity savedEntity = new PartFitmentEntity();
        savedEntity.setId(UUID.randomUUID());
        savedEntity.setPartNumberId(1L);

        when(manufacturerRepository.findAllByNameIgnoreCase("Toyota")).thenReturn(List.of(manufacturer));
        when(makeRepository.findByManufacturerIdAndNameIgnoreCase(MANUFACTURER_ID, "Camry"))
                .thenReturn(Optional.of(existingMake));
        when(vehicleTypeRepository.findByMakeIdAndVehicleTypeNameIgnoreCase(MAKE_ID, "Passenger Car"))
                .thenReturn(Optional.empty());
        when(vehicleTypeRepository.saveAndFlush(any(VehicleType.class))).thenReturn(savedType);
        when(partFitmentRepository.save(any(PartFitmentEntity.class))).thenReturn(savedEntity);

        CreatePartFitmentRequest request = new CreatePartFitmentRequest(1L);
        request.setManufacturerName("Toyota");
        request.setMakeName("Camry");
        request.setVehicleTypeName("Passenger Car");

        PartFitmentResponse response = service.createFitment(request);

        verify(vehicleTypeRepository).findByMakeIdAndVehicleTypeNameIgnoreCase(MAKE_ID, "Passenger Car");
        verify(vehicleTypeRepository).saveAndFlush(any(VehicleType.class));
        assertThat(response.getVehicleTypeName()).isEqualTo("Passenger Car");
    }

    @Test
    void createFitment_reuseExistingManufacturer_whenNameMatchesExisting() {
        Manufacturer existingManufacturer = new Manufacturer();
        existingManufacturer.setId(MANUFACTURER_ID);
        existingManufacturer.setName("Toyota");

        PartFitmentEntity savedEntity = new PartFitmentEntity();
        savedEntity.setId(UUID.randomUUID());
        savedEntity.setPartNumberId(1L);

        when(manufacturerRepository.findAllByNameIgnoreCase("Toyota")).thenReturn(List.of(existingManufacturer));
        when(partFitmentRepository.save(any(PartFitmentEntity.class))).thenReturn(savedEntity);

        CreatePartFitmentRequest request = new CreatePartFitmentRequest(1L);
        request.setManufacturerName("Toyota");

        PartFitmentResponse response = service.createFitment(request);

        verify(manufacturerRepository, never()).save(any(Manufacturer.class));
        assertThat(response.getManufacturerName()).isEqualTo("Toyota");
    }

    @Test
    void createFitment_reuseExistingMake_whenParentScopedLookupReturnsMatch() {
        Manufacturer manufacturer = new Manufacturer();
        manufacturer.setId(MANUFACTURER_ID);
        manufacturer.setName("Toyota");

        Make existingMake = new Make();
        existingMake.setId(MAKE_ID);
        existingMake.setName("Camry");

        PartFitmentEntity savedEntity = new PartFitmentEntity();
        savedEntity.setId(UUID.randomUUID());
        savedEntity.setPartNumberId(1L);

        when(manufacturerRepository.findAllByNameIgnoreCase("Toyota")).thenReturn(List.of(manufacturer));
        when(makeRepository.findByManufacturerIdAndNameIgnoreCase(MANUFACTURER_ID, "Camry"))
                .thenReturn(Optional.of(existingMake));
        when(partFitmentRepository.save(any(PartFitmentEntity.class))).thenReturn(savedEntity);

        CreatePartFitmentRequest request = new CreatePartFitmentRequest(1L);
        request.setManufacturerName("Toyota");
        request.setMakeName("Camry");

        PartFitmentResponse response = service.createFitment(request);

        verify(makeRepository, never()).save(any(Make.class));
        assertThat(response.getMakeName()).isEqualTo("Camry");
    }

    @Test
    void createFitment_usesNullParentScopedLookup_forMakeWhenNoManufacturer() {
        Make savedMake = new Make();
        savedMake.setId(MAKE_ID);
        savedMake.setName("Generic");

        PartFitmentEntity savedEntity = new PartFitmentEntity();
        savedEntity.setId(UUID.randomUUID());
        savedEntity.setPartNumberId(1L);

        when(makeRepository.findByManufacturerIsNullAndNameIgnoreCase("Generic"))
                .thenReturn(Optional.empty());
        when(makeRepository.saveAndFlush(any(Make.class))).thenReturn(savedMake);
        when(partFitmentRepository.save(any(PartFitmentEntity.class))).thenReturn(savedEntity);

        CreatePartFitmentRequest request = new CreatePartFitmentRequest(1L);
        request.setMakeName("Generic");
        // no manufacturerName set — null-parent path

        PartFitmentResponse response = service.createFitment(request);

        verify(makeRepository).findByManufacturerIsNullAndNameIgnoreCase("Generic");
        verify(makeRepository, never()).findByManufacturerIdAndNameIgnoreCase(any(), any());
        assertThat(response.getMakeName()).isEqualTo("Generic");
    }

    @Test
    void createFitment_usesNullParentScopedLookup_forModelWhenNoMake() {
        UUID modelId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
        Model savedModel = new Model();
        savedModel.setId(modelId);
        savedModel.setName("UnknownModel");

        PartFitmentEntity savedEntity = new PartFitmentEntity();
        savedEntity.setId(UUID.randomUUID());
        savedEntity.setPartNumberId(1L);

        when(modelRepository.findByMakeIsNullAndNameIgnoreCase("UnknownModel")).thenReturn(Optional.empty());
        when(modelRepository.saveAndFlush(any(Model.class))).thenReturn(savedModel);
        when(partFitmentRepository.save(any(PartFitmentEntity.class))).thenReturn(savedEntity);

        CreatePartFitmentRequest request = new CreatePartFitmentRequest(1L);
        request.setModelName("UnknownModel");
        // no makeName set — null-parent path

        PartFitmentResponse response = service.createFitment(request);

        verify(modelRepository).findByMakeIsNullAndNameIgnoreCase("UnknownModel");
        verify(modelRepository, never()).findByMakeIdAndNameIgnoreCase(any(), any());
        assertThat(response.getModelName()).isEqualTo("UnknownModel");
    }

    @Test
    void createFitment_usesNullParentScopedLookup_forVehicleTypeWhenNoMake() {
        VehicleType savedType = new VehicleType();
        savedType.setVehicleTypeName("Trailer");

        PartFitmentEntity savedEntity = new PartFitmentEntity();
        savedEntity.setId(UUID.randomUUID());
        savedEntity.setPartNumberId(1L);

        when(vehicleTypeRepository.findByMakeIsNullAndVehicleTypeNameIgnoreCase("Trailer"))
                .thenReturn(Optional.empty());
        when(vehicleTypeRepository.saveAndFlush(any(VehicleType.class))).thenReturn(savedType);
        when(partFitmentRepository.save(any(PartFitmentEntity.class))).thenReturn(savedEntity);

        CreatePartFitmentRequest request = new CreatePartFitmentRequest(1L);
        request.setVehicleTypeName("Trailer");
        // no makeName set — null-parent path

        PartFitmentResponse response = service.createFitment(request);

        verify(vehicleTypeRepository).findByMakeIsNullAndVehicleTypeNameIgnoreCase("Trailer");
        verify(vehicleTypeRepository, never()).findByMakeIdAndVehicleTypeNameIgnoreCase(any(), any());
        assertThat(response.getVehicleTypeName()).isEqualTo("Trailer");
    }
}
