package com.positivity.vehiclefitment.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.positivity.vehiclefitment.internal.dto.MakeResponse;
import com.positivity.vehiclefitment.internal.dto.ManufacturerResponse;
import com.positivity.vehiclefitment.internal.dto.ModelResponse;
import com.positivity.vehiclefitment.internal.dto.VehicleTypeResponse;
import com.positivity.vehiclefitment.tenancy.PostgresTenancyTestBase;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

/**
 * The vPIC reference cache against real Postgres (#2416). The cached rows are referenced by foreign keys
 * ({@code make -> manufacturer}, {@code model -> make}, {@code part_fitment_entity -> all three}) and the
 * names are unique case-insensitively, neither of which H2 with {@code ddl-auto=create-drop} and the Mockito
 * unit tests can show. vPIC itself is stubbed with payloads shaped like its own responses.
 */
@DisplayName("vPIC reference refresh on Postgres (#2416)")
class VpicReferenceRefreshIT extends PostgresTenancyTestBase {

    private static final String VPIC = "https://vpic.nhtsa.dot.gov/api/vehicles";
    private static final String MANUFACTURERS_URL = VPIC + "/getallmanufacturers?format=json";
    private static final String MAKES_URL = VPIC + "/GetMakeForManufacturer/955?format=json";
    private static final String MODELS_URL = VPIC + "/GetModelsForMakeId/441?format=json";
    private static final String VEHICLE_TYPES_URL = VPIC + "/GetVehicleTypesForMakeId/441?format=json";

    private static final String VARIABLES_URL = VPIC + "/GetVehicleVariableList?format=json";
    private static final String VARIABLE_VALUES_URL = VPIC + "/GetVehicleVariableValuesList/86?format=json";

    private static final UUID ABS = derived("variable-86");
    private static final UUID ABS_STANDARD = derived("variable-value-86-1");
    private static final UUID TESLA = derived("manufacturer-955");
    private static final UUID TESLA_MAKE = derived("make-441");
    private static final UUID MODEL_S = derived("model-1685");

    @MockitoBean
    private RestClient restClient;

    @Autowired
    private VehicleFitmentService service;

    private final Map<String, String> vpic = new HashMap<>();
    private JdbcTemplate owner;

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked"})
    void stubVpicAndClearCache() {
        RestClient.RequestHeadersUriSpec uriSpec = Mockito.mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec headersSpec = Mockito.mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec responseSpec = Mockito.mock(RestClient.ResponseSpec.class);
        AtomicReference<String> requested = new AtomicReference<>();
        when(restClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenAnswer(invocation -> {
            requested.set(invocation.getArgument(0));
            return headersSpec;
        });
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenAnswer(invocation -> {
            String body = vpic.get(requested.get());
            if (body == null) {
                throw new AssertionError("Unexpected vPIC request: " + requested.get());
            }
            return body;
        });

        owner = new JdbcTemplate(ownerDataSource());
        owner.execute("DELETE FROM part_fitment_entity_vehicle_variable_values");
        owner.execute("DELETE FROM part_fitment_entity");
        owner.execute("DELETE FROM vehicle_variable_value");
        owner.execute("DELETE FROM vehicle_variable");
        owner.execute("DELETE FROM vehicle_type");
        owner.execute("DELETE FROM model");
        owner.execute("DELETE FROM make");
        owner.execute("DELETE FROM manufacturer");

        vpic.put(
                MANUFACTURERS_URL,
                manufacturers("{\"Mfr_CommonName\":\"Tesla\",\"Mfr_ID\":955,\"Mfr_Name\":\"TESLA, INC.\"}"));
        vpic.put(MAKES_URL, makes("{\"Make_ID\":441,\"Make_Name\":\"TESLA\",\"Mfr_Name\":\"TESLA, INC.\"}"));
        vpic.put(
                MODELS_URL,
                models("{\"Make_ID\":441,\"Make_Name\":\"TESLA\",\"Model_ID\":1685,\"Model_Name\":\"Model S\"}"));
    }

    @Test
    void staleCacheWithChildren_refreshesManufacturersAndMakesInPlace() {
        fillManufacturerMakeModel();
        ageCache();
        vpic.put(
                MANUFACTURERS_URL,
                manufacturers("{\"Mfr_CommonName\":\"Tesla Motors\",\"Mfr_ID\":955,\"Mfr_Name\":\"TESLA, INC.\"}"));
        vpic.put(MAKES_URL, makes("{\"Make_ID\":441,\"Make_Name\":\"TESLA MOTORS\",\"Mfr_Name\":\"TESLA, INC.\"}"));

        assertThat(service.getManufacturers())
                .extracting(ManufacturerResponse::getId, ManufacturerResponse::getName)
                .containsExactly(tuple(TESLA, "Tesla Motors"));
        assertThat(service.getMakesByManufacturer(TESLA))
                .extracting(MakeResponse::getId, MakeResponse::getName)
                .containsExactly(tuple(TESLA_MAKE, "TESLA MOTORS"));
        assertThat(owner.queryForObject("SELECT make_id FROM model WHERE id = ?", UUID.class, MODEL_S))
                .isEqualTo(TESLA_MAKE);
    }

    @Test
    void rowVpicNoLongerReturns_isKept() {
        vpic.put(
                MANUFACTURERS_URL,
                manufacturers(
                        "{\"Mfr_CommonName\":\"Tesla\",\"Mfr_ID\":955,\"Mfr_Name\":\"TESLA, INC.\"}",
                        "{\"Mfr_CommonName\":\"Rivian\",\"Mfr_ID\":1000,\"Mfr_Name\":\"RIVIAN AUTOMOTIVE, LLC\"}"));
        service.getManufacturers();
        ageCache();
        vpic.put(
                MANUFACTURERS_URL,
                manufacturers("{\"Mfr_CommonName\":\"Tesla\",\"Mfr_ID\":955,\"Mfr_Name\":\"TESLA, INC.\"}"));

        assertThat(service.getManufacturers())
                .extracting(ManufacturerResponse::getName)
                .containsExactlyInAnyOrder("Tesla", "Rivian");
    }

    @Test
    void blankCommonNames_fallBackToMfrNameAndDoNotCollide() {
        vpic.put(
                MANUFACTURERS_URL,
                manufacturers(
                        "{\"Mfr_CommonName\":null,\"Mfr_ID\":1001,\"Mfr_Name\":\"ACME COACH, INC.\"}",
                        "{\"Mfr_CommonName\":\"\",\"Mfr_ID\":1002,\"Mfr_Name\":\"BETA TRAILERS LLC\"}"));

        assertThat(service.getManufacturers())
                .extracting(ManufacturerResponse::getName)
                .containsExactlyInAnyOrder("ACME COACH, INC.", "BETA TRAILERS LLC");
    }

    @Test
    void nameTakenByAnotherRow_skipsThatRowAndKeepsTheRest() {
        UUID local = UUID.randomUUID();
        owner.update(
                "INSERT INTO manufacturer (id, name, created_at, updated_at) VALUES (?, 'tesla', now(), now())", local);
        vpic.put(
                MANUFACTURERS_URL,
                manufacturers(
                        "{\"Mfr_CommonName\":\"Tesla\",\"Mfr_ID\":955,\"Mfr_Name\":\"TESLA, INC.\"}",
                        "{\"Mfr_CommonName\":\"Rivian\",\"Mfr_ID\":1000,\"Mfr_Name\":\"RIVIAN AUTOMOTIVE, LLC\"}"));

        assertThat(service.getManufacturers())
                .extracting(ManufacturerResponse::getId, ManufacturerResponse::getName)
                .containsExactlyInAnyOrder(tuple(local, "tesla"), tuple(derived("manufacturer-1000"), "Rivian"));
    }

    @Test
    void vehicleTypesReferencedByAPartFitment_refreshInPlaceAndDroppedTypesAreKept() {
        fillManufacturerMakeModel();
        vpic.put(
                VEHICLE_TYPES_URL,
                vehicleTypes(
                        "{\"VehicleTypeId\":2,\"VehicleTypeName\":\"Passenger Car\"}",
                        "{\"VehicleTypeId\":7,\"VehicleTypeName\":\"Multipurpose Passenger Vehicle (MPV)\"}"));
        UUID passengerCar = service.getVehicleTypesForMake(TESLA_MAKE).stream()
                .filter(type -> "2".equals(type.getVehicleTypeId()))
                .findFirst()
                .orElseThrow()
                .getId();
        owner.update(
                "INSERT INTO part_fitment_entity (id, part_number_id, vehicle_manufacturer_id, vehicle_make_id,"
                        + " vehicle_model_id, vehicle_type_id, created_at, updated_at)"
                        + " VALUES (?, 1, ?, ?, ?, ?, now(), now())",
                UUID.randomUUID(),
                TESLA,
                TESLA_MAKE,
                MODEL_S,
                passengerCar);
        ageCache();
        vpic.put(
                VEHICLE_TYPES_URL,
                vehicleTypes(
                        "{\"VehicleTypeId\":2,\"VehicleTypeName\":\"Passenger Car (Sedan)\"}",
                        "{\"VehicleTypeId\":3,\"VehicleTypeName\":\"Truck\"}"));

        assertThat(service.getVehicleTypesForMake(TESLA_MAKE))
                .extracting(VehicleTypeResponse::getVehicleTypeId, VehicleTypeResponse::getVehicleTypeName)
                .containsExactlyInAnyOrder(
                        tuple("2", "Passenger Car (Sedan)"),
                        tuple("7", "Multipurpose Passenger Vehicle (MPV)"),
                        tuple("3", "Truck"));
        assertThat(owner.queryForObject("SELECT vehicle_type_id FROM part_fitment_entity", UUID.class))
                .isEqualTo(passengerCar);
        assertThat(owner.queryForObject(
                        "SELECT vehicle_type_name FROM vehicle_type WHERE id = ?", String.class, passengerCar))
                .isEqualTo("Passenger Car (Sedan)");
    }

    @Test
    void vehicleTypeCachedBeforeIdsWereDerived_isUpdatedNotDuplicated() {
        fillManufacturerMakeModel();
        UUID legacy = UUID.randomUUID();
        owner.update(
                "INSERT INTO vehicle_type (id, make_id, vehicle_type_name, vehicle_type_id, cache_timestamp,"
                        + " created_at, updated_at) VALUES (?, ?, 'passenger car', '2', now() - interval '25 hours',"
                        + " now(), now())",
                legacy,
                TESLA_MAKE);
        vpic.put(VEHICLE_TYPES_URL, vehicleTypes("{\"VehicleTypeId\":2,\"VehicleTypeName\":\"Passenger Car\"}"));

        assertThat(service.getVehicleTypesForMake(TESLA_MAKE))
                .extracting(VehicleTypeResponse::getId, VehicleTypeResponse::getVehicleTypeName)
                .containsExactly(tuple(legacy, "Passenger Car"));
    }

    @Test
    void variableValuesRefreshedTwice_requestVpicIdAndKeepValueReferencedByAPartFitment() {
        fillVariablesAndValues("Standard");
        owner.update(
                "INSERT INTO part_fitment_entity (id, part_number_id, created_at, updated_at)"
                        + " VALUES (?, 1, now(), now())",
                UUID.randomUUID());
        owner.update(
                "INSERT INTO part_fitment_entity_vehicle_variable_values (part_fitment_entity_id,"
                        + " vehicle_variable_values_id) SELECT id, ? FROM part_fitment_entity",
                ABS_STANDARD);
        ageVariableCache();
        vpic.put(
                VARIABLES_URL,
                variables("{\"DataType\":\"string\",\"Description\":\"Anti-lock brakes\",\"ID\":86,\"Name\":\"ABS\"}"));
        vpic.put(
                VARIABLE_VALUES_URL,
                variableValues(
                        "{\"ElementName\":\"ABS\",\"Id\":1,\"Name\":\"Standard (2)\"}",
                        "{\"ElementName\":\"ABS\",\"Id\":2,\"Name\":\"Optional\"}"));

        assertThat(service.getVehicleVariables()).extracting(v -> v.getId()).containsExactly(ABS);
        assertThat(service.getVehicleVariableValues(ABS))
                .extracting(v -> v.getId(), v -> v.getValue())
                .containsExactlyInAnyOrder(
                        tuple(ABS_STANDARD, "Standard (2)"), tuple(derived("variable-value-86-2"), "Optional"));
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM part_fitment_entity_vehicle_variable_values", Integer.class))
                .isEqualTo(1);
        assertThat(owner.queryForObject("SELECT nhtsa_id FROM vehicle_variable WHERE id = ?", Long.class, ABS))
                .isEqualTo(86L);
    }

    @Test
    void variableCachedBeforeVpicIdsWereStored_isAdoptedNotDuplicated() {
        UUID legacy = UUID.randomUUID();
        owner.update(
                "INSERT INTO vehicle_variable (id, name, cache_timestamp, created_at, updated_at)"
                        + " VALUES (?, 'abs', now() - interval '25 hours', now(), now())",
                legacy);
        vpic.put(VARIABLES_URL, variables("{\"Description\":\"d\",\"ID\":86,\"Name\":\"ABS\"}"));

        assertThat(service.getVehicleVariables()).extracting(v -> v.getId()).containsExactly(legacy);
        assertThat(owner.queryForObject("SELECT nhtsa_id FROM vehicle_variable WHERE id = ?", Long.class, legacy))
                .isEqualTo(86L);
    }

    @Test
    void preMigrationValueReferencedByAPartFitment_isAdoptedKeepsItsFitmentAndSurvivesASecondRefresh() {
        UUID legacyVariable = UUID.randomUUID();
        UUID legacyValue = UUID.randomUUID();
        owner.update(
                "INSERT INTO vehicle_variable (id, name, cache_timestamp, created_at, updated_at)"
                        + " VALUES (?, 'ABS', now() - interval '25 hours', now(), now())",
                legacyVariable);
        owner.update(
                "INSERT INTO vehicle_variable_value (id, variable_id, variable_value, value_id, cache_timestamp,"
                        + " created_at, updated_at) VALUES (?, ?, 'standard', '', now() - interval '25 hours',"
                        + " now(), now())",
                legacyValue,
                legacyVariable);
        owner.update(
                "INSERT INTO part_fitment_entity (id, part_number_id, created_at, updated_at)"
                        + " VALUES (?, 1, now(), now())",
                UUID.randomUUID());
        owner.update(
                "INSERT INTO part_fitment_entity_vehicle_variable_values (part_fitment_entity_id,"
                        + " vehicle_variable_values_id) SELECT id, ? FROM part_fitment_entity",
                legacyValue);
        vpic.put(VARIABLES_URL, variables("{\"Description\":\"d\",\"ID\":86,\"Name\":\"ABS\"}"));
        vpic.put(VARIABLE_VALUES_URL, variableValues("{\"ElementName\":\"ABS\",\"Id\":1,\"Name\":\"Standard\"}"));

        for (int refresh = 0; refresh < 2; refresh++) {
            assertThat(service.getVehicleVariables()).extracting(v -> v.getId()).containsExactly(legacyVariable);
            assertThat(service.getVehicleVariableValues(legacyVariable))
                    .extracting(v -> v.getId(), v -> v.getValueId())
                    .containsExactly(tuple(legacyValue, "1"));
            assertThat(owner.queryForObject(
                            "SELECT vehicle_variable_values_id FROM part_fitment_entity_vehicle_variable_values",
                            UUID.class))
                    .isEqualTo(legacyValue);
            ageVariableCache();
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM vehicle_variable", Integer.class))
                .isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM vehicle_variable_value", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void variableWithoutVpicId_servesItsValuesFromCacheWithoutCallingVpic() {
        UUID local = UUID.randomUUID();
        owner.update(
                "INSERT INTO vehicle_variable (id, name, created_at, updated_at) VALUES (?, 'local', now(), now())",
                local);

        assertThat(service.getVehicleVariableValues(local)).isEmpty();
    }

    private void fillVariablesAndValues(String valueName) {
        vpic.put(VARIABLES_URL, variables("{\"Description\":\"d\",\"ID\":86,\"Name\":\"ABS\"}"));
        vpic.put(
                VARIABLE_VALUES_URL,
                variableValues("{\"ElementName\":\"ABS\",\"Id\":1,\"Name\":\"" + valueName + "\"}"));
        assertThat(service.getVehicleVariables()).extracting(v -> v.getId()).containsExactly(ABS);
        assertThat(service.getVehicleVariableValues(ABS))
                .extracting(v -> v.getId())
                .containsExactly(ABS_STANDARD);
    }

    private void ageVariableCache() {
        for (String table : List.of("vehicle_variable", "vehicle_variable_value")) {
            owner.update("UPDATE " + table + " SET cache_timestamp = cache_timestamp - interval '25 hours'");
        }
    }

    private void fillManufacturerMakeModel() {
        assertThat(service.getManufacturers())
                .extracting(ManufacturerResponse::getId)
                .containsExactly(TESLA);
        assertThat(service.getMakesByManufacturer(TESLA))
                .extracting(MakeResponse::getId)
                .containsExactly(TESLA_MAKE);
        assertThat(service.getModelsByMake(TESLA_MAKE))
                .extracting(ModelResponse::getId)
                .containsExactly(MODEL_S);
    }

    /** Pushes every cached row outside the 24-hour window, so the next read refetches from vPIC. */
    private void ageCache() {
        for (String table : List.of("manufacturer", "make", "model", "vehicle_type")) {
            owner.update("UPDATE " + table + " SET cache_timestamp = cache_timestamp - interval '25 hours'");
        }
    }

    private static UUID derived(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    private static String variables(String... rows) {
        return envelope("null", rows);
    }

    private static String variableValues(String... rows) {
        return envelope("\"Variable:86\"", rows);
    }

    private static String manufacturers(String... rows) {
        return envelope("null", rows);
    }

    private static String makes(String... rows) {
        return envelope("\"Manufacturer:955\"", rows);
    }

    private static String models(String... rows) {
        return envelope("\"Make ID:441\"", rows);
    }

    private static String vehicleTypes(String... rows) {
        return envelope("\"Make ID: 441\"", rows);
    }

    private static String envelope(String searchCriteria, String... rows) {
        return "{\"Count\":" + rows.length + ",\"Message\":\"Response returned successfully\",\"SearchCriteria\":"
                + searchCriteria + ",\"Results\":[" + String.join(",", rows) + "]}";
    }
}
