package com.positivity.nhtsa.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.positivity.nhtsa.internal.entity.Make;
import com.positivity.nhtsa.internal.entity.Manufacturer;
import com.positivity.nhtsa.internal.entity.Model;
import com.positivity.nhtsa.internal.entity.VehicleType;
import com.positivity.nhtsa.internal.entity.VehicleVariable;
import com.positivity.nhtsa.internal.entity.VehicleVariableValue;
import com.positivity.nhtsa.internal.service.VehicleReferenceService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
 * The vPIC reference cache against real Postgres (#2454). Every cached row is referenced by a foreign key
 * ({@code make -> manufacturer}, {@code model} and {@code vehicle_type -> make},
 * {@code vehicle_variable_value -> vehicle_variable}), which H2 with {@code ddl-auto=create-drop} and the Mockito
 * unit tests do not enforce the same way, and the refresh must build every dependent vPIC URL from vPIC's own
 * integer id. vPIC itself is stubbed with payloads shaped like its own responses; a request for any URL not
 * stubbed fails the test, so a URL built from a local UUID cannot pass.
 */
@DisplayName("vPIC reference refresh on Postgres (#2454)")
class VpicReferenceRefreshIT extends PostgresTenancyTestBase {

    private static final String VPIC = "https://vpic.nhtsa.dot.gov/api/vehicles";
    private static final String MANUFACTURERS_URL = VPIC + "/getallmanufacturers?format=json";
    private static final String MAKES_URL = VPIC + "/GetMakeForManufacturer/955?format=json";
    private static final String MODELS_URL = VPIC + "/GetModelsForMakeId/441?format=json";
    private static final String VEHICLE_TYPES_URL = VPIC + "/GetVehicleTypesForMakeId/441?format=json";
    private static final String VARIABLES_URL = VPIC + "/GetVehicleVariableList?format=json";
    private static final String VALUES_URL = VPIC + "/GetVehicleVariableValuesList/86?format=json";

    private static final UUID TESLA = derived("manufacturer-955");
    private static final UUID TESLA_MAKE = derived("make-441");
    private static final UUID ABS = derived("variable-86");

    @MockitoBean
    private RestClient restClient;

    @Autowired
    private VehicleReferenceService service;

    private final Map<String, String> vpic = new HashMap<>();
    private final List<String> requested = new ArrayList<>();
    private JdbcTemplate owner;

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked"})
    void stubVpicAndClearCache() {
        RestClient.RequestHeadersUriSpec uriSpec = Mockito.mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec headersSpec = Mockito.mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec responseSpec = Mockito.mock(RestClient.ResponseSpec.class);
        AtomicReference<String> current = new AtomicReference<>();
        when(restClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenAnswer(invocation -> {
            current.set(invocation.getArgument(0));
            requested.add(current.get());
            return headersSpec;
        });
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenAnswer(invocation -> {
            String body = vpic.get(current.get());
            if (body == null) {
                throw new AssertionError("Unexpected vPIC request: " + current.get());
            }
            return body;
        });

        owner = new JdbcTemplate(ownerDataSource());
        requested.clear();
        for (String table : List.of(
                "vehicle_variable_value",
                "vehicle_variable",
                "vehicle_type",
                "model",
                "make_manufacturer",
                "make",
                "manufacturer")) {
            owner.execute("DELETE FROM " + table);
        }
        vpic.put(
                MANUFACTURERS_URL,
                envelope("{\"Mfr_CommonName\":\"Tesla\",\"Mfr_ID\":955,\"Mfr_Name\":\"TESLA, INC.\"}"));
        vpic.put(MAKES_URL, envelope("{\"Make_ID\":441,\"Make_Name\":\"TESLA\",\"Mfr_Name\":\"TESLA, INC.\"}"));
        vpic.put(
                MODELS_URL,
                envelope("{\"Make_ID\":441,\"Make_Name\":\"TESLA\",\"Model_ID\":1685,\"Model_Name\":\"Model S\"}"));
        vpic.put(VEHICLE_TYPES_URL, envelope("{\"VehicleTypeId\":2,\"VehicleTypeName\":\"Passenger Car\"}"));
        vpic.put(VARIABLES_URL, envelope("{\"DataType\":\"string\",\"Description\":\"d\",\"ID\":86,\"Name\":\"ABS\"}"));
        vpic.put(VALUES_URL, envelope("{\"ElementName\":\"ABS\",\"Id\":1,\"Name\":\"Standard\"}"));
    }

    @Test
    void repeatRefreshWithChildrenPresent_requestsByVpicIdAndKeepsEveryRow() {
        Manufacturer manufacturer = service.getManufacturers().getFirst();
        assertThat(manufacturer.getId()).isEqualTo(TESLA);
        assertThat(manufacturer.getNhtsaId()).isEqualTo(955L);
        Make make = service.getMakesByManufacturer(TESLA).getFirst();
        assertThat(make.getId()).isEqualTo(TESLA_MAKE);
        assertThat(make.getNhtsaId()).isEqualTo(441L);
        assertThat(service.getModelsByMake(TESLA_MAKE))
                .extracting(Model::getId, Model::getNhtsaId)
                .containsExactly(tuple(derived("model-1685"), 1685L));
        assertThat(service.getVehicleTypesForMake(TESLA_MAKE))
                .extracting(VehicleType::getVehicleTypeId)
                .containsExactly("2");
        service.getVehicleVariables();
        assertThat(service.getVehicleVariableValues(ABS))
                .extracting(VehicleVariableValue::getId, VehicleVariableValue::getValue)
                .containsExactly(tuple(derived("variable-value-86-1"), "Standard"));

        // Second refresh, with every child still present: the delete-and-reinsert it replaced failed on the
        // foreign keys here.
        ageCache();
        requested.clear();
        vpic.put(
                MANUFACTURERS_URL,
                envelope("{\"Mfr_CommonName\":\"Tesla Motors\",\"Mfr_ID\":955,\"Mfr_Name\":\"TESLA, INC.\"}"));
        vpic.put(MAKES_URL, envelope("{\"Make_ID\":441,\"Make_Name\":\"TESLA MOTORS\"}"));
        vpic.put(
                VARIABLES_URL, envelope("{\"DataType\":\"string\",\"Description\":\"d2\",\"ID\":86,\"Name\":\"ABS\"}"));
        vpic.put(VALUES_URL, envelope("{\"ElementName\":\"ABS\",\"Id\":1,\"Name\":\"Standard (2)\"}"));

        assertThat(service.getManufacturers())
                .extracting(Manufacturer::getId, Manufacturer::getName)
                .containsExactly(tuple(TESLA, "Tesla Motors"));
        assertThat(service.getMakesByManufacturer(TESLA))
                .extracting(Make::getId, Make::getName)
                .containsExactly(tuple(TESLA_MAKE, "TESLA MOTORS"));
        assertThat(service.getModelsByMake(TESLA_MAKE)).hasSize(1);
        assertThat(service.getVehicleTypesForMake(TESLA_MAKE)).hasSize(1);
        assertThat(service.getVehicleVariables())
                .extracting(VehicleVariable::getId)
                .containsExactly(ABS);
        assertThat(service.getVehicleVariableValues(ABS))
                .extracting(VehicleVariableValue::getValue)
                .containsExactly("Standard (2)");

        assertThat(requested)
                .containsExactlyInAnyOrder(
                        MANUFACTURERS_URL, MAKES_URL, MODELS_URL, VEHICLE_TYPES_URL, VARIABLES_URL, VALUES_URL);
        assertThat(owner.queryForObject("SELECT count(*) FROM vehicle_type", Integer.class))
                .isEqualTo(1);
        assertThat(owner.queryForObject("SELECT make_id FROM model", UUID.class))
                .isEqualTo(TESLA_MAKE);
    }

    @Test
    void makeSharedByTwoManufacturers_isOneRowLinkedToBothAndListedUnderBoth() {
        // vPIC returns the same Make_ID 482 for two manufacturers (#2471).
        String manufacturersUrl = MANUFACTURERS_URL;
        vpic.put(
                manufacturersUrl,
                envelope("{\"Mfr_CommonName\":\"A\",\"Mfr_ID\":1001}", "{\"Mfr_CommonName\":\"B\",\"Mfr_ID\":1002}"));
        String makesA = VPIC + "/GetMakeForManufacturer/1001?format=json";
        String makesB = VPIC + "/GetMakeForManufacturer/1002?format=json";
        vpic.put(
                makesA,
                envelope("{\"Make_ID\":482,\"Make_Name\":\"SHARED\"}", "{\"Make_ID\":483,\"Make_Name\":\"ONLY A\"}"));
        vpic.put(makesB, envelope("{\"Make_ID\":482,\"Make_Name\":\"SHARED\"}"));
        UUID a = derived("manufacturer-1001");
        UUID b = derived("manufacturer-1002");
        UUID shared = derived("make-482");
        service.getManufacturers();

        // Refreshed A, B, A.
        assertThat(service.getMakesByManufacturer(a)).extracting(Make::getId).contains(shared);
        assertThat(service.getMakesByManufacturer(b)).extracting(Make::getId).containsExactly(shared);
        // Stale only A, so B stays fresh and is served from the join table without calling vPIC.
        owner.update(
                "UPDATE manufacturer SET makes_refreshed_at = makes_refreshed_at - interval '25 hours' WHERE id = ?",
                a);
        requested.clear();
        assertThat(service.getMakesByManufacturer(a)).extracting(Make::getId).contains(shared);
        assertThat(requested).containsExactly(makesA);

        // B's list was not touched by A's second refresh: no vPIC call, link survived, shared make still listed.
        requested.clear();
        assertThat(service.getMakesByManufacturer(b)).extracting(Make::getId).containsExactly(shared);
        assertThat(requested).isEmpty();
        assertThat(owner.queryForObject("SELECT count(*) FROM make WHERE nhtsa_id = 482", Integer.class))
                .isEqualTo(1);
        assertThat(owner.queryForList(
                        "SELECT manufacturer_id FROM make_manufacturer WHERE make_id = ?", UUID.class, shared))
                .containsExactlyInAnyOrder(a, b);
    }

    @Test
    void makeListFreshnessIsPerManufacturer() {
        vpic.put(
                MANUFACTURERS_URL,
                envelope("{\"Mfr_CommonName\":\"A\",\"Mfr_ID\":1001}", "{\"Mfr_CommonName\":\"B\",\"Mfr_ID\":1002}"));
        String makesA = VPIC + "/GetMakeForManufacturer/1001?format=json";
        String makesB = VPIC + "/GetMakeForManufacturer/1002?format=json";
        vpic.put(makesA, envelope("{\"Make_ID\":482,\"Make_Name\":\"SHARED\"}"));
        vpic.put(makesB, envelope("{\"Make_ID\":482,\"Make_Name\":\"SHARED\"}"));
        service.getManufacturers();
        requested.clear();

        service.getMakesByManufacturer(derived("manufacturer-1001"));
        assertThat(requested).containsExactly(makesA);

        // A's refresh stamped the shared make, but B's own list has never been fetched: vPIC is still called.
        requested.clear();
        service.getMakesByManufacturer(derived("manufacturer-1002"));
        assertThat(requested).containsExactly(makesB);

        requested.clear();
        service.getMakesByManufacturer(derived("manufacturer-1001"));
        service.getMakesByManufacturer(derived("manufacturer-1002"));
        assertThat(requested).isEmpty();
    }

    @Test
    void freshCache_isServedWithoutCallingVpic() {
        service.getManufacturers();
        service.getMakesByManufacturer(TESLA);
        requested.clear();

        assertThat(service.getManufacturers()).hasSize(1);
        assertThat(service.getMakesByManufacturer(TESLA)).hasSize(1);

        assertThat(requested).isEmpty();
    }

    @Test
    void manufacturerWithoutVpicId_servesItsMakesFromCacheWithoutCallingVpic() {
        UUID local = UUID.randomUUID();
        owner.update("INSERT INTO manufacturer (id, name) VALUES (?, 'Local')", local);

        assertThat(service.getMakesByManufacturer(local)).isEmpty();

        assertThat(requested).isEmpty();
    }

    @Test
    void variableCachedBeforeVpicIdsWereStored_isAdoptedNotDuplicated() {
        UUID legacy = UUID.randomUUID();
        owner.update(
                "INSERT INTO vehicle_variable (id, name, cache_timestamp)"
                        + " VALUES (?, 'abs', now() - interval '25 hours')",
                legacy);

        assertThat(service.getVehicleVariables())
                .extracting(VehicleVariable::getId, VehicleVariable::getNhtsaId)
                .containsExactly(tuple(legacy, 86L));
    }

    @Test
    void preMigrationRowsAreAdoptedInPlaceAndSurviveASecondRefresh() {
        // State as the old code left it: derived ids but no nhtsa_id for manufacturers, random ids for variables
        // and values with a blank value_id.
        UUID legacyVariable = UUID.randomUUID();
        UUID legacyValue = UUID.randomUUID();
        owner.update(
                "INSERT INTO manufacturer (id, name, cache_timestamp) VALUES (?, 'Tesla', now() - interval '25 hours')",
                TESLA);
        owner.update(
                "INSERT INTO vehicle_variable (id, name, cache_timestamp) VALUES (?, 'ABS', now() - interval '25 hours')",
                legacyVariable);
        owner.update(
                "INSERT INTO vehicle_variable_value (id, variable_id, value, value_id, cache_timestamp)"
                        + " VALUES (?, ?, 'standard', '', now() - interval '25 hours')",
                legacyValue,
                legacyVariable);

        for (int refresh = 0; refresh < 2; refresh++) {
            assertThat(service.getManufacturers())
                    .extracting(Manufacturer::getId, Manufacturer::getNhtsaId)
                    .containsExactly(tuple(TESLA, 955L));
            assertThat(service.getVehicleVariables())
                    .extracting(VehicleVariable::getId, VehicleVariable::getNhtsaId)
                    .containsExactly(tuple(legacyVariable, 86L));
            assertThat(service.getVehicleVariableValues(legacyVariable))
                    .extracting(VehicleVariableValue::getId, VehicleVariableValue::getValueId)
                    .containsExactly(tuple(legacyValue, "1"));
            ageCache();
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM vehicle_variable_value", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void badIdAnywhereInThePayload_savesNothing() {
        vpic.put(
                MANUFACTURERS_URL,
                envelope(
                        "{\"Mfr_CommonName\":\"Tesla\",\"Mfr_ID\":955}",
                        "{\"Mfr_CommonName\":\"Bad\",\"Mfr_ID\":\"not-a-number\"}"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.getManufacturers())
                .hasMessageContaining("Failed to parse manufacturers");

        assertThat(owner.queryForObject("SELECT count(*) FROM manufacturer", Integer.class))
                .isZero();
    }

    /** Pushes every cached row outside the 24-hour window, so the next read refetches from vPIC. */
    private void ageCache() {
        for (String table : List.of(
                "manufacturer", "make", "model", "vehicle_type", "vehicle_variable", "vehicle_variable_value")) {
            owner.update("UPDATE " + table + " SET cache_timestamp = cache_timestamp - interval '25 hours'");
        }
        owner.update("UPDATE manufacturer SET makes_refreshed_at = makes_refreshed_at - interval '25 hours'");
    }

    private static UUID derived(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    private static String envelope(String... rows) {
        return "{\"Count\":" + rows.length + ",\"Message\":\"Response returned successfully\",\"SearchCriteria\":null,"
                + "\"Results\":[" + String.join(",", rows) + "]}";
    }
}
