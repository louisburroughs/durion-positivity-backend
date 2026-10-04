package com.positivity.vehiclefitment.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
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
    private static final long VARIABLE_VPIC_ID = 86L;
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

    @Mock
    private TransactionTemplate transactionTemplate;

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
        lenient()
                .doAnswer(invocation -> {
                    invocation.<Consumer<TransactionStatus>>getArgument(0).accept(null);
                    return null;
                })
                .when(transactionTemplate)
                .executeWithoutResult(any());
    }

    // ─── getVehicleVariables ───────────────────────────────────────────────────

    @Test
    void getVehicleVariables_emptyCache_callsApiAndReturnsData() {
        VehicleVariable saved = new VehicleVariable();
        saved.setName("ABS");
        saved.setCacheTimestamp(LocalDateTime.now(TEST_CLOCK));

        when(vehicleVariableRepository.findAll()).thenReturn(List.of()).thenReturn(List.of(saved));

        when(responseSpec.body(String.class))
                .thenReturn("{\"Results\":[{\"ID\":3,\"Name\":\"ABS\",\"Description\":\"Anti-lock Braking\"}]}");

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
        VehicleVariable variable = vehicleVariable(hourOld());
        VehicleVariableValue saved = new VehicleVariableValue();
        saved.setVariable(variable);
        saved.setValue("Car");
        saved.setCacheTimestamp(LocalDateTime.now(TEST_CLOCK));

        when(vehicleVariableRepository.findById(VARIABLE_ID)).thenReturn(Optional.of(variable));
        when(vehicleVariableValueRepository.findByVariable_Id(VARIABLE_ID))
                .thenReturn(List.of())
                .thenReturn(List.of(saved));

        when(responseSpec.body(String.class)).thenReturn("{\"Results\":[{\"Id\":1,\"Name\":\"Car\"}]}");

        List<VehicleVariableValue> result = service.getVehicleVariableValues(VARIABLE_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getValue()).isEqualTo("Car");
    }

    @Test
    void getVehicleVariableValues_parseError_throwsVehicleFitmentException() {
        when(vehicleVariableRepository.findById(VARIABLE_ID)).thenReturn(Optional.of(vehicleVariable(hourOld())));
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
                .thenReturn("{\"Results\":[{\"ID\":3,\"Name\":\"ABS\",\"Description\":\"Anti-lock Braking\"}]}");

        service.getVehicleVariables();

        verifySingleVpicCall(VPIC_BASE + "/GetVehicleVariableList?format=json");
    }

    @Test
    void getVehicleVariableValues_freshCache_servesCacheWithoutCallingVpic() {
        when(vehicleVariableRepository.findById(VARIABLE_ID)).thenReturn(Optional.of(vehicleVariable(hourOld())));
        VehicleVariableValue cached = vehicleVariableValue(hourOld());
        when(vehicleVariableValueRepository.findByVariable_Id(VARIABLE_ID)).thenReturn(List.of(cached));

        assertThat(service.getVehicleVariableValues(VARIABLE_ID)).containsExactly(cached);

        verify(restClient, never()).get();
    }

    @Test
    void getVehicleVariableValues_staleCache_refetchesFromVpicOnce() {
        when(vehicleVariableRepository.findById(VARIABLE_ID)).thenReturn(Optional.of(vehicleVariable(hourOld())));
        when(vehicleVariableValueRepository.findByVariable_Id(VARIABLE_ID))
                .thenReturn(List.of(vehicleVariableValue(dayOld())));
        when(responseSpec.body(String.class)).thenReturn("{\"Results\":[{\"Id\":1,\"Name\":\"Car\"}]}");

        service.getVehicleVariableValues(VARIABLE_ID);

        verifySingleVpicCall(VPIC_BASE + "/GetVehicleVariableValuesList/" + VARIABLE_VPIC_ID + "?format=json");
    }

    // ─── variables keyed by vPIC id (#2454) ─────────────────────────────────────

    @Test
    void getVehicleVariables_integerId_storesVpicIdAndDerivesLocalId() {
        when(vehicleVariableRepository.findAll()).thenReturn(List.of());
        when(responseSpec.body(String.class))
                .thenReturn("{\"Count\":1,\"Message\":\"Response returned successfully\",\"SearchCriteria\":null,"
                        + "\"Results\":[{\"DataType\":\"string\",\"Description\":\"<p>Anti-lock</p>\","
                        + "\"GroupName\":\"Active Safety System\",\"ID\":86,\"Name\":\"ABS\"}]}");

        service.getVehicleVariables();

        ArgumentCaptor<VehicleVariable> saved = ArgumentCaptor.forClass(VehicleVariable.class);
        verify(vehicleVariableRepository).save(saved.capture());
        assertThat(saved.getValue().getNhtsaId()).isEqualTo(86L);
        assertThat(saved.getValue().getId()).isEqualTo(derived("variable-86"));
        assertThat(saved.getValue().getName()).isEqualTo("ABS");
        assertThat(saved.getValue().getDescription()).isEqualTo("<p>Anti-lock</p>");
    }

    @Test
    void getVehicleVariables_longDescription_isTruncatedToColumnWidth() {
        when(vehicleVariableRepository.findAll()).thenReturn(List.of());
        when(responseSpec.body(String.class))
                .thenReturn("{\"Results\":[{\"ID\":86,\"Name\":\"ABS\",\"Description\":\"" + "x".repeat(400) + "\"}]}");

        service.getVehicleVariables();

        ArgumentCaptor<VehicleVariable> saved = ArgumentCaptor.forClass(VehicleVariable.class);
        verify(vehicleVariableRepository).save(saved.capture());
        assertThat(saved.getValue().getDescription()).hasSize(255);
    }

    @Test
    void getVehicleVariables_legacyRowWithoutVpicId_isAdoptedByName() {
        VehicleVariable legacy = vehicleVariable(dayOld());
        legacy.setNhtsaId(null);
        when(vehicleVariableRepository.findAll()).thenReturn(List.of(legacy));
        when(responseSpec.body(String.class))
                .thenReturn("{\"Results\":[{\"ID\":86,\"Name\":\"abs\",\"Description\":\"d\"}]}");

        service.getVehicleVariables();

        verify(vehicleVariableRepository).save(legacy);
        assertThat(legacy.getId()).isEqualTo(VARIABLE_ID);
        assertThat(legacy.getNhtsaId()).isEqualTo(86L);
    }

    @Test
    void getVehicleVariables_secondRefreshAfterAdoption_updatesTheAdoptedRowNotADuplicate() {
        VehicleVariable adopted = vehicleVariable(dayOld()); // random-style PK, vPIC id already stored
        when(vehicleVariableRepository.findAll()).thenReturn(List.of(adopted));
        when(responseSpec.body(String.class))
                .thenReturn("{\"Results\":[{\"ID\":86,\"Name\":\"ABS renamed\",\"Description\":\"d\"}]}");

        service.getVehicleVariables();

        verify(vehicleVariableRepository).save(adopted);
        assertThat(adopted.getId()).isEqualTo(VARIABLE_ID);
        assertThat(adopted.getName()).isEqualTo("ABS renamed");
    }

    @Test
    void getVehicleVariableValues_legacyValueWithBlankValueId_isAdoptedByNameAndKeepsItsKey() {
        UUID legacyId = UUID.randomUUID();
        VehicleVariableValue legacy = vehicleVariableValue(dayOld());
        legacy.setId(legacyId);
        legacy.setValueId("");
        when(vehicleVariableRepository.findById(VARIABLE_ID)).thenReturn(Optional.of(vehicleVariable(hourOld())));
        when(vehicleVariableValueRepository.findByVariable_Id(VARIABLE_ID)).thenReturn(List.of(legacy));
        when(responseSpec.body(String.class)).thenReturn("{\"Results\":[{\"Id\":1,\"Name\":\"car\"}]}");

        service.getVehicleVariableValues(VARIABLE_ID);

        verify(vehicleVariableValueRepository, org.mockito.Mockito.times(1)).save(any(VehicleVariableValue.class));
        verify(vehicleVariableValueRepository).save(legacy);
        assertThat(legacy.getId()).isEqualTo(legacyId);
        assertThat(legacy.getValueId()).isEqualTo("1");
    }

    @Test
    void getVehicleVariableValues_secondRefreshAfterAdoption_matchesByValueIdNotName() {
        VehicleVariableValue adopted = vehicleVariableValue(dayOld());
        adopted.setId(UUID.randomUUID());
        adopted.setValueId("1");
        when(vehicleVariableRepository.findById(VARIABLE_ID)).thenReturn(Optional.of(vehicleVariable(hourOld())));
        when(vehicleVariableValueRepository.findByVariable_Id(VARIABLE_ID)).thenReturn(List.of(adopted));
        when(responseSpec.body(String.class)).thenReturn("{\"Results\":[{\"Id\":1,\"Name\":\"Renamed\"}]}");

        service.getVehicleVariableValues(VARIABLE_ID);

        verify(vehicleVariableValueRepository).save(adopted);
        assertThat(adopted.getValue()).isEqualTo("Renamed");
    }

    @Test
    void getVehicleVariableValues_legacyValueWithOtherValueId_isLeftAlone() {
        VehicleVariableValue other = vehicleVariableValue(dayOld());
        other.setId(UUID.randomUUID());
        other.setValueId("9");
        when(vehicleVariableRepository.findById(VARIABLE_ID)).thenReturn(Optional.of(vehicleVariable(hourOld())));
        when(vehicleVariableValueRepository.findByVariable_Id(VARIABLE_ID)).thenReturn(List.of(other));
        when(responseSpec.body(String.class)).thenReturn("{\"Results\":[{\"Id\":1,\"Name\":\"Car\"}]}");

        service.getVehicleVariableValues(VARIABLE_ID);

        org.mockito.ArgumentCaptor<VehicleVariableValue> saved =
                org.mockito.ArgumentCaptor.forClass(VehicleVariableValue.class);
        verify(vehicleVariableValueRepository).save(saved.capture());
        assertThat(saved.getValue()).isNotSameAs(other);
        assertThat(saved.getValue().getId()).isEqualTo(derived("variable-value-86-1"));
    }

    @Test
    void getVehicleVariables_nonIntegerId_failsWholeRefreshWithNothingSaved() {
        when(vehicleVariableRepository.findAll()).thenReturn(List.of());
        when(responseSpec.body(String.class))
                .thenReturn("{\"Results\":[{\"ID\":86,\"Name\":\"ABS\"},{\"ID\":\"x\",\"Name\":\"Bad\"}]}");

        assertThatThrownBy(() -> service.getVehicleVariables())
                .isInstanceOf(VehicleFitmentException.class)
                .hasMessageContaining("Failed to parse vehicle variables");
        verify(vehicleVariableRepository, never()).save(any(VehicleVariable.class));
    }

    @Test
    void getVehicleVariableValues_requestsByVpicVariableIdAndDerivesLocalId() {
        when(vehicleVariableRepository.findById(VARIABLE_ID)).thenReturn(Optional.of(vehicleVariable(hourOld())));
        when(vehicleVariableValueRepository.findByVariable_Id(VARIABLE_ID)).thenReturn(List.of());
        when(responseSpec.body(String.class))
                .thenReturn("{\"Count\":1,\"Message\":\"Response returned successfully\",\"SearchCriteria\":null,"
                        + "\"Results\":[{\"ElementName\":\"ABS\",\"Id\":1,\"Name\":\"Standard\"}]}");

        service.getVehicleVariableValues(VARIABLE_ID);

        verifySingleVpicCall(VPIC_BASE + "/GetVehicleVariableValuesList/86?format=json");
        ArgumentCaptor<VehicleVariableValue> saved = ArgumentCaptor.forClass(VehicleVariableValue.class);
        verify(vehicleVariableValueRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(derived("variable-value-86-1"));
        assertThat(saved.getValue().getValueId()).isEqualTo("1");
        assertThat(saved.getValue().getValue()).isEqualTo("Standard");
        verify(vehicleVariableValueRepository, never()).deleteAll(any());
    }

    @Test
    void getVehicleVariableValues_existingRow_isUpdatedInPlace() {
        UUID id = derived("variable-value-86-1");
        VehicleVariableValue existing = vehicleVariableValue(dayOld());
        existing.setId(id);
        when(vehicleVariableRepository.findById(VARIABLE_ID)).thenReturn(Optional.of(vehicleVariable(hourOld())));
        when(vehicleVariableValueRepository.findByVariable_Id(VARIABLE_ID)).thenReturn(List.of(existing));
        when(responseSpec.body(String.class)).thenReturn("{\"Results\":[{\"Id\":1,\"Name\":\"Optional\"}]}");

        service.getVehicleVariableValues(VARIABLE_ID);

        verify(vehicleVariableValueRepository).save(existing);
        assertThat(existing.getValue()).isEqualTo("Optional");
    }

    @Test
    void getVehicleVariableValues_nonIntegerId_failsWholeRefreshWithNothingSaved() {
        when(vehicleVariableRepository.findById(VARIABLE_ID)).thenReturn(Optional.of(vehicleVariable(hourOld())));
        when(vehicleVariableValueRepository.findByVariable_Id(VARIABLE_ID)).thenReturn(List.of());
        when(responseSpec.body(String.class))
                .thenReturn("{\"Results\":[{\"Id\":1,\"Name\":\"A\"},{\"Id\":\"1\",\"Name\":\"B\"}]}");

        assertThatThrownBy(() -> service.getVehicleVariableValues(VARIABLE_ID))
                .isInstanceOf(VehicleFitmentException.class)
                .hasMessageContaining("Failed to parse vehicle variable values");
        verify(vehicleVariableValueRepository, never()).save(any(VehicleVariableValue.class));
    }

    @Test
    void getVehicleVariableValues_variableWithoutVpicId_servesCacheWithoutCallingVpic() {
        VehicleVariable local = vehicleVariable(hourOld());
        local.setNhtsaId(null);
        when(vehicleVariableRepository.findById(VARIABLE_ID)).thenReturn(Optional.of(local));
        when(vehicleVariableValueRepository.findByVariable_Id(VARIABLE_ID))
                .thenReturn(List.of(vehicleVariableValue(dayOld())));

        assertThat(service.getVehicleVariableValues(VARIABLE_ID)).hasSize(1);

        verify(restClient, never()).get();
    }

    @Test
    void getVehicleVariableValues_unknownVariable_isRejected() {
        when(vehicleVariableRepository.findById(VARIABLE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getVehicleVariableValues(VARIABLE_ID))
                .isInstanceOf(IllegalArgumentException.class);
        verify(restClient, never()).get();
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

    // ─── in-place refresh (#2416) ──────────────────────────────────────────────
    //
    // Refreshes update the row keyed by vPIC's id or insert a new one; nothing is deleted, a
    // name held by another row is skipped, and blank names never reach the unique index as "".
    // VpicReferenceRefreshIT shows the same on Postgres; these pin each branch without Docker.

    @Test
    void getManufacturers_existingRow_isUpdatedInPlace() {
        UUID id = derived("manufacturer-955");
        Manufacturer existing = new Manufacturer();
        existing.setId(id);
        existing.setNhtsaId(955L);
        existing.setName("Tesla");
        existing.setCacheTimestamp(dayOld());
        when(manufacturerRepository.findAll()).thenReturn(List.of(existing));
        when(manufacturerRepository.findAllByNameIgnoreCase("Tesla Motors")).thenReturn(List.of(existing));
        when(manufacturerRepository.findById(id)).thenReturn(Optional.of(existing));
        when(responseSpec.body(String.class))
                .thenReturn("{\"Results\":[{\"Mfr_ID\":955,\"Mfr_CommonName\":\"Tesla Motors\"}]}");

        service.getManufacturers();

        verify(manufacturerRepository).save(existing);
        verify(manufacturerRepository, never()).deleteAll();
        verify(manufacturerRepository, never()).deleteAll(any());
        assertThat(existing.getName()).isEqualTo("Tesla Motors");
        assertThat(existing.getCacheTimestamp()).isEqualTo(LocalDateTime.now(TEST_CLOCK));
    }

    @Test
    void getManufacturers_blankCommonName_fallsBackToMfrNameThenNull() {
        when(manufacturerRepository.findAll()).thenReturn(List.of());
        when(responseSpec.body(String.class))
                .thenReturn("{\"Results\":["
                        + "{\"Mfr_ID\":1,\"Mfr_CommonName\":\" Tesla \",\"Mfr_Name\":\"TESLA, INC.\"},"
                        + "{\"Mfr_ID\":2,\"Mfr_CommonName\":\"  \",\"Mfr_Name\":\"ACME COACH\"},"
                        + "{\"Mfr_ID\":3,\"Mfr_CommonName\":null,\"Mfr_Name\":\"BETA TRAILERS\"},"
                        + "{\"Mfr_ID\":4,\"Mfr_CommonName\":\"\",\"Mfr_Name\":null}]}");

        service.getManufacturers();

        ArgumentCaptor<Manufacturer> saved = ArgumentCaptor.forClass(Manufacturer.class);
        verify(manufacturerRepository, times(4)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(Manufacturer::getName)
                .containsExactly("Tesla", "ACME COACH", "BETA TRAILERS", null);
        verify(manufacturerRepository, never()).findAllByNameIgnoreCase(null);
    }

    @Test
    void getManufacturers_nameHeldByAnotherRow_skipsThatRowAndKeepsTheRest() {
        Manufacturer local = new Manufacturer();
        local.setId(UUID.randomUUID());
        local.setName("tesla");
        when(manufacturerRepository.findAll()).thenReturn(List.of());
        when(manufacturerRepository.findAllByNameIgnoreCase("Tesla")).thenReturn(List.of(local));
        when(responseSpec.body(String.class))
                .thenReturn("{\"Results\":[{\"Mfr_ID\":955,\"Mfr_CommonName\":\"Tesla\"},"
                        + "{\"Mfr_ID\":1000,\"Mfr_CommonName\":\"Rivian\"}]}");

        service.getManufacturers();

        ArgumentCaptor<Manufacturer> saved = ArgumentCaptor.forClass(Manufacturer.class);
        verify(manufacturerRepository).save(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("Rivian");
        assertThat(saved.getValue().getId()).isEqualTo(derived("manufacturer-1000"));
    }

    @Test
    void getMakesByManufacturer_updatesExisting_insertsNew_skipsCollision_keepsBlankNameNull() {
        Make existing = make(dayOld());
        existing.setId(derived("make-441"));
        Make other = make(dayOld());
        other.setId(UUID.randomUUID());
        when(manufacturerRepository.findById(MANUFACTURER_ID)).thenReturn(Optional.of(manufacturer(hourOld())));
        when(makeRepository.findByManufacturerId(MANUFACTURER_ID)).thenReturn(List.of(existing));
        when(makeRepository.findById(derived("make-441"))).thenReturn(Optional.of(existing));
        when(makeRepository.findByManufacturerIdAndNameIgnoreCase(MANUFACTURER_ID, "TESLA"))
                .thenReturn(Optional.of(existing));
        when(makeRepository.findByManufacturerIdAndNameIgnoreCase(MANUFACTURER_ID, "TAKEN"))
                .thenReturn(Optional.of(other));
        when(responseSpec.body(String.class))
                .thenReturn("{\"Results\":[{\"Make_ID\":441,\"Make_Name\":\"TESLA\"},"
                        + "{\"Make_ID\":442,\"Make_Name\":\"TAKEN\"},"
                        + "{\"Make_ID\":443,\"Make_Name\":\"NEW\"},"
                        + "{\"Make_ID\":444,\"Make_Name\":\"\"}]}");

        service.getMakesByManufacturer(MANUFACTURER_ID);

        ArgumentCaptor<Make> saved = ArgumentCaptor.forClass(Make.class);
        verify(makeRepository, times(3)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(Make::getId, Make::getName, Make::getNhtsaId)
                .containsExactly(
                        tuple(derived("make-441"), "TESLA", 441L),
                        tuple(derived("make-443"), "NEW", 443L),
                        tuple(derived("make-444"), null, 444L));
        assertThat(saved.getAllValues().getFirst()).isSameAs(existing);
        verify(makeRepository, never()).deleteAll(any());
    }

    @Test
    void getModelsByMake_updatesExisting_insertsNew_skipsCollision_keepsBlankNameNull() {
        Model existing = model(dayOld());
        existing.setId(derived("model-1685"));
        Model other = model(dayOld());
        other.setId(UUID.randomUUID());
        when(makeRepository.findById(MAKE_ID)).thenReturn(Optional.of(make(hourOld())));
        when(modelRepository.findByMakeId(MAKE_ID)).thenReturn(List.of(existing));
        when(modelRepository.findById(derived("model-1685"))).thenReturn(Optional.of(existing));
        when(modelRepository.findByMakeIdAndNameIgnoreCase(MAKE_ID, "Model S")).thenReturn(Optional.of(existing));
        when(modelRepository.findByMakeIdAndNameIgnoreCase(MAKE_ID, "Taken")).thenReturn(Optional.of(other));
        when(responseSpec.body(String.class))
                .thenReturn("{\"Results\":[{\"Model_ID\":1685,\"Model_Name\":\"Model S\"},"
                        + "{\"Model_ID\":1686,\"Model_Name\":\"Taken\"},"
                        + "{\"Model_ID\":1687,\"Model_Name\":\"Model 3\"},"
                        + "{\"Model_ID\":1688,\"Model_Name\":null}]}");

        service.getModelsByMake(MAKE_ID);

        ArgumentCaptor<Model> saved = ArgumentCaptor.forClass(Model.class);
        verify(modelRepository, times(3)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(Model::getId, Model::getName, Model::getNhtsaId)
                .containsExactly(
                        tuple(derived("model-1685"), "Model S", 1685L),
                        tuple(derived("model-1687"), "Model 3", 1687L),
                        tuple(derived("model-1688"), null, 1688L));
        assertThat(saved.getAllValues().getFirst()).isSameAs(existing);
        verify(modelRepository, never()).deleteAll(any());
    }

    @Test
    void getVehicleTypesForMake_matchesLegacyRowByTypeId_insertsNew_skipsCollision_keepsBlankNameNull() {
        VehicleType legacy = vehicleType(dayOld());
        legacy.setId(UUID.randomUUID());
        legacy.setVehicleTypeId("2");
        VehicleType dropped = vehicleType(dayOld());
        dropped.setId(UUID.randomUUID());
        dropped.setVehicleTypeId("7");
        dropped.setVehicleTypeName("MPV");
        VehicleType holder = vehicleType(dayOld());
        holder.setId(UUID.randomUUID());
        when(makeRepository.findById(MAKE_ID)).thenReturn(Optional.of(make(hourOld())));
        when(vehicleTypeRepository.findByMakeId(MAKE_ID)).thenReturn(List.of(dropped, legacy));
        when(vehicleTypeRepository.findByMakeIdAndVehicleTypeNameIgnoreCase(MAKE_ID, "Passenger Car"))
                .thenReturn(Optional.of(legacy));
        when(vehicleTypeRepository.findByMakeIdAndVehicleTypeNameIgnoreCase(MAKE_ID, "Taken"))
                .thenReturn(Optional.of(holder));
        when(responseSpec.body(String.class))
                .thenReturn("{\"Results\":[{\"VehicleTypeId\":2,\"VehicleTypeName\":\"Passenger Car\"},"
                        + "{\"VehicleTypeId\":4,\"VehicleTypeName\":\"Taken\"},"
                        + "{\"VehicleTypeId\":3,\"VehicleTypeName\":\"Truck\"},"
                        + "{\"VehicleTypeId\":5,\"VehicleTypeName\":\" \"}]}");

        service.getVehicleTypesForMake(MAKE_ID);

        ArgumentCaptor<VehicleType> saved = ArgumentCaptor.forClass(VehicleType.class);
        verify(vehicleTypeRepository, times(3)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(VehicleType::getId, VehicleType::getVehicleTypeId, VehicleType::getVehicleTypeName)
                .containsExactly(
                        tuple(legacy.getId(), "2", "Passenger Car"),
                        tuple(derived("vehicle-type-" + MAKE_VPIC_ID + "-3"), "3", "Truck"),
                        tuple(derived("vehicle-type-" + MAKE_VPIC_ID + "-5"), "5", null));
        assertThat(saved.getAllValues().getFirst()).isSameAs(legacy);
        assertThat(dropped.getVehicleTypeName()).isEqualTo("MPV");
        verify(vehicleTypeRepository, never()).deleteAll(any());
    }

    @Test
    void getMakesByManufacturer_nonIntegerMakeId_throws() {
        when(manufacturerRepository.findById(MANUFACTURER_ID)).thenReturn(Optional.of(manufacturer(hourOld())));
        when(makeRepository.findByManufacturerId(MANUFACTURER_ID)).thenReturn(List.of());
        when(responseSpec.body(String.class))
                .thenReturn("{\"Results\":[{\"Make_ID\":\"441\",\"Make_Name\":\"TESLA\"}]}");

        assertThatThrownBy(() -> service.getMakesByManufacturer(MANUFACTURER_ID))
                .isInstanceOf(VehicleFitmentException.class)
                .hasMessageContaining("Failed to parse makes");
        verify(makeRepository, never()).save(any(Make.class));
    }

    @Test
    void getModelsByMake_nonIntegerModelId_throws() {
        when(makeRepository.findById(MAKE_ID)).thenReturn(Optional.of(make(hourOld())));
        when(modelRepository.findByMakeId(MAKE_ID)).thenReturn(List.of());
        when(responseSpec.body(String.class))
                .thenReturn("{\"Results\":[{\"Model_ID\":1.5,\"Model_Name\":\"Model S\"}]}");

        assertThatThrownBy(() -> service.getModelsByMake(MAKE_ID))
                .isInstanceOf(VehicleFitmentException.class)
                .hasMessageContaining("Failed to parse models");
        verify(modelRepository, never()).save(any(Model.class));
    }

    @Test
    void getVehicleTypesForMake_missingVehicleTypeId_throws() {
        when(makeRepository.findById(MAKE_ID)).thenReturn(Optional.of(make(hourOld())));
        when(vehicleTypeRepository.findByMakeId(MAKE_ID)).thenReturn(List.of());
        when(responseSpec.body(String.class)).thenReturn("{\"Results\":[{\"VehicleTypeName\":\"Passenger Car\"}]}");

        assertThatThrownBy(() -> service.getVehicleTypesForMake(MAKE_ID))
                .isInstanceOf(VehicleFitmentException.class)
                .hasMessageContaining("Failed to parse vehicle types for make");
        verify(vehicleTypeRepository, never()).save(any(VehicleType.class));
    }

    @Test
    void getManufacturers_freshnessUsesNewestTimestamp_ignoringStaleAndNullRows() {
        Manufacturer stale = manufacturer(dayOld());
        Manufacturer local = manufacturer(null);
        Manufacturer fresh = manufacturer(hourOld());
        when(manufacturerRepository.findAll()).thenReturn(List.of(local, stale, fresh));

        assertThat(service.getManufacturers()).hasSize(3);

        verify(restClient, never()).get();
    }

    @Test
    void getManufacturers_onlyNullTimestamps_refetchesFromVpic() {
        when(manufacturerRepository.findAll()).thenReturn(List.of(manufacturer(null)));
        when(responseSpec.body(String.class)).thenReturn("{\"Results\":[]}");

        service.getManufacturers();

        verifySingleVpicCall(VPIC_BASE + "/getallmanufacturers?format=json");
    }

    @Test
    void getVehicleTypesForMake_freshnessUsesNewestTimestamp() {
        when(makeRepository.findById(MAKE_ID)).thenReturn(Optional.of(make(hourOld())));
        when(vehicleTypeRepository.findByMakeId(MAKE_ID))
                .thenReturn(List.of(vehicleType(null), vehicleType(dayOld()), vehicleType(hourOld())));

        assertThat(service.getVehicleTypesForMake(MAKE_ID)).hasSize(3);

        verify(restClient, never()).get();
    }

    // A bad row anywhere in the payload fails the refresh before anything is written: no partial
    // refresh that the newest-timestamp freshness check would then serve as current for a day.

    @Test
    void getManufacturers_validRowThenInvalidId_savesNothing() {
        when(manufacturerRepository.findAll()).thenReturn(List.of());
        when(responseSpec.body(String.class))
                .thenReturn("{\"Results\":[{\"Mfr_ID\":955,\"Mfr_CommonName\":\"Tesla\"},"
                        + "{\"Mfr_ID\":\"x\",\"Mfr_CommonName\":\"Rivian\"}]}");

        assertThatThrownBy(() -> service.getManufacturers()).isInstanceOf(VehicleFitmentException.class);

        verify(manufacturerRepository, never()).save(any(Manufacturer.class));
        verify(transactionTemplate, never()).executeWithoutResult(any());
    }

    @Test
    void getMakesByManufacturer_validRowThenInvalidId_savesNothing() {
        when(manufacturerRepository.findById(MANUFACTURER_ID)).thenReturn(Optional.of(manufacturer(hourOld())));
        when(makeRepository.findByManufacturerId(MANUFACTURER_ID)).thenReturn(List.of());
        when(responseSpec.body(String.class))
                .thenReturn("{\"Results\":[{\"Make_ID\":441,\"Make_Name\":\"TESLA\"}," + "{\"Make_Name\":\"NO ID\"}]}");

        assertThatThrownBy(() -> service.getMakesByManufacturer(MANUFACTURER_ID))
                .isInstanceOf(VehicleFitmentException.class);

        verify(makeRepository, never()).save(any(Make.class));
        verify(transactionTemplate, never()).executeWithoutResult(any());
    }

    @Test
    void getModelsByMake_validRowThenInvalidId_savesNothing() {
        when(makeRepository.findById(MAKE_ID)).thenReturn(Optional.of(make(hourOld())));
        when(modelRepository.findByMakeId(MAKE_ID)).thenReturn(List.of());
        when(responseSpec.body(String.class))
                .thenReturn("{\"Results\":[{\"Model_ID\":1685,\"Model_Name\":\"Model S\"},"
                        + "{\"Model_ID\":\"1686\",\"Model_Name\":\"Model 3\"}]}");

        assertThatThrownBy(() -> service.getModelsByMake(MAKE_ID)).isInstanceOf(VehicleFitmentException.class);

        verify(modelRepository, never()).save(any(Model.class));
        verify(transactionTemplate, never()).executeWithoutResult(any());
    }

    @Test
    void getVehicleTypesForMake_validRowThenInvalidId_savesNothing() {
        when(makeRepository.findById(MAKE_ID)).thenReturn(Optional.of(make(hourOld())));
        when(vehicleTypeRepository.findByMakeId(MAKE_ID)).thenReturn(List.of());
        when(responseSpec.body(String.class))
                .thenReturn("{\"Results\":[{\"VehicleTypeId\":2,\"VehicleTypeName\":\"Passenger Car\"},"
                        + "{\"VehicleTypeId\":null,\"VehicleTypeName\":\"Truck\"}]}");

        assertThatThrownBy(() -> service.getVehicleTypesForMake(MAKE_ID)).isInstanceOf(VehicleFitmentException.class);

        verify(vehicleTypeRepository, never()).save(any(VehicleType.class));
        verify(transactionTemplate, never()).executeWithoutResult(any());
    }

    private static UUID derived(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
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
        variable.setNhtsaId(VARIABLE_VPIC_ID);
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
