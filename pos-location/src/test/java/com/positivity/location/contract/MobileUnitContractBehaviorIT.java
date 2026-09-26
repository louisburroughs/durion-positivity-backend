package com.positivity.location.contract;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.location.BaseContractIntegrationTest;
import com.positivity.location.config.TestSecurityConfig;
import com.positivity.location.internal.entity.ExtCatalogServiceReplica;
import com.positivity.location.internal.entity.Location;
import com.positivity.location.internal.repository.ExtCatalogServiceReplicaRepository;
import com.positivity.location.internal.repository.LocationRepository;
import com.positivity.location.internal.repository.MobileUnitCoverageRuleRepository;
import com.positivity.location.internal.repository.MobileUnitRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Contract RED tests for mobile units, service areas, and travel buffer
 * policies.
 *
 * These tests encode Story #76 endpoint contracts and expected HTTP semantics.
 *
 * Issue: #76
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
class MobileUnitContractBehaviorIT extends BaseContractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ExtCatalogServiceReplicaRepository extCatalogServiceReplicaRepository;

    @Autowired
    private LocationRepository locationRepository;

    /** A mobile unit's base location must exist (#2252), so each test that creates a unit makes one. */
    private String createBaseLocation() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID id = locationRepository
                .save(Location.builder()
                        .name("Mobile Hub " + suffix)
                        .code("MU-HUB-" + suffix)
                        .status("ACTIVE")
                        .active(true)
                        .build())
                .getId();
        createdLocations.add(id);
        return id.toString();
    }

    @Autowired
    private MobileUnitRepository mobileUnitRepository;

    @Autowired
    private MobileUnitCoverageRuleRepository coverageRuleRepository;

    private final List<UUID> createdLocations = new ArrayList<>();

    /**
     * The H2 context is shared with the other contract ITs, and a unit's base location is a real
     * foreign key now (#2252): units left behind would stop BayContractBehaviorIT from clearing
     * its locations. So the units, their rules and the locations this class made all go.
     */
    @AfterEach
    void clearCatalogReplica() {
        extCatalogServiceReplicaRepository.deleteAll();
        coverageRuleRepository.deleteAll();
        mobileUnitRepository.deleteAll();
        locationRepository.deleteAllById(createdLocations);
        createdLocations.clear();
    }

    @Test
    @DisplayName("#76 - POST /v1/mobile-units returns 201")
    void shouldCreateMobileUnit() throws Exception {
        String payload = """
                {
                  "name": "MU-101",
                  "baseLocationId": "%s",
                  "status": "INACTIVE",
                  "notes": "new unit"
                }
                """.formatted(createBaseLocation());

        mockMvc.perform(withGatewayAuth(post("/v1/mobile-units")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("#76 - GET /v1/mobile-units returns 200")
    void shouldListMobileUnits() throws Exception {
        mockMvc.perform(withGatewayAuth(get("/v1/mobile-units"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("#76 - GET /v1/mobile-units/{id} returns 404 when missing")
    void shouldReturnNotFoundWhenMobileUnitMissing() throws Exception {
        mockMvc.perform(withGatewayAuth(get("/v1/mobile-units/{id}", "018f1f5a-a222-7333-8222-222222222222")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("#2252 - PATCH /v1/mobile-units/{id} returns 404 when missing, as GET and DELETE do")
    void shouldReturnNotFoundWhenPatchingMissingMobileUnit() throws Exception {
        String patchPayload = """
                {
                  "status": "ACTIVE",
                  "travelBufferPolicyId": "018f1f5a-a333-7333-8222-333333333333"
                }
                """;

        mockMvc.perform(withGatewayAuth(patch("/v1/mobile-units/{id}", "018f1f5a-a222-7333-8222-222222222222")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchPayload)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("#76 - PUT /v1/mobile-units/{id}/coverage-rules returns 404 when mobile unit missing")
    void shouldReturnNotFoundWhenReplacingCoverageRulesForMissingMobileUnit() throws Exception {
        String payload = """
                {
                  "rules": [
                    {
                      "ruleType": "POSTAL_CODE_LIST",
                      "priority": 1,
                      "serviceAreaId": "018f1f5a-a444-7333-8222-444444444444",
                      "validFrom": "2026-01-01",
                      "validTo": null
                    }
                  ]
                }
                """;

        mockMvc.perform(withGatewayAuth(
                        put("/v1/mobile-units/{id}/coverage-rules", "018f1f5a-a222-7333-8222-222222222222")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("#76 - GET /v1/mobile-units/{id}/coverage-rules returns 200")
    void shouldGetCoverageRules() throws Exception {
        mockMvc.perform(withGatewayAuth(
                        get("/v1/mobile-units/{id}/coverage-rules", "018f1f5a-a222-7333-8222-222222222222")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("#76 - POST /v1/service-areas returns 201")
    void shouldCreateServiceArea() throws Exception {
        String payload = """
                {
                  "name": "North Zone",
                  "description": "metro north",
                  "active": true,
                  "postalCodes": [
                    { "postalCode": "98101", "countryCode": "US" }
                  ]
                }
                """;

        mockMvc.perform(withGatewayAuth(post("/v1/service-areas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("#76 - GET /v1/service-areas returns 200")
    void shouldListServiceAreas() throws Exception {
        mockMvc.perform(withGatewayAuth(get("/v1/service-areas"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("#76 - PATCH /v1/service-areas/{id} returns 404 when missing")
    void shouldReturnNotFoundWhenPatchingMissingServiceArea() throws Exception {
        String payload = """
                {
                  "active": false
                }
                """;

        mockMvc.perform(withGatewayAuth(patch("/v1/service-areas/{id}", "018f1f5a-a555-7333-8222-555555555555")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("#76 - PATCH /v1/service-areas/{id} returns 400 for invalid id")
    void shouldReturnBadRequestForInvalidServiceAreaId() throws Exception {
        String payload = """
                {
                  "active": false
                }
                """;

        mockMvc.perform(withGatewayAuth(patch("/v1/service-areas/{id}", "bad-id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("#1991 - PUT /v1/service-areas/{id}/postal-codes replaces the set end to end")
    void shouldReplaceServiceAreaPostalCodes() throws Exception {
        String created = mockMvc.perform(withGatewayAuth(post("/v1/service-areas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Coverage Amendment Zone",
                                  "postalCodes": [
                                    { "postalCode": "98101", "countryCode": "US" },
                                    { "postalCode": "98102", "countryCode": "US" }
                                  ]
                                }
                                """)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = new ObjectMapper().readTree(created).get("id").asText();

        // 98101 is dropped and 98103 added: the set sent is the set that remains.
        mockMvc.perform(withGatewayAuth(put("/v1/service-areas/{id}/postal-codes", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "postalCodes": [
                                    { "postalCode": "98102", "countryCode": "US" },
                                    { "postalCode": "98103", "countryCode": "US" }
                                  ]
                                }
                                """)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postalCodes.length()").value(2))
                .andExpect(jsonPath("$.postalCodes[*].postalCode", containsInAnyOrder("98102", "98103")));
    }

    @Test
    @DisplayName("#1991 - PUT /v1/service-areas/{id}/postal-codes returns 404 when the area is missing")
    void shouldReturnNotFoundWhenReplacingPostalCodesOnMissingArea() throws Exception {
        mockMvc.perform(withGatewayAuth(
                        put("/v1/service-areas/{id}/postal-codes", "018f1f5a-a666-7333-8222-666666666666")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        { "postalCodes": [ { "postalCode": "98101", "countryCode": "US" } ] }
                                        """)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("#1991 - PUT /v1/service-areas/{id}/postal-codes returns 400 for an invalid id")
    void shouldReturnBadRequestForInvalidIdWhenReplacingPostalCodes() throws Exception {
        mockMvc.perform(withGatewayAuth(put("/v1/service-areas/{id}/postal-codes", "bad-id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "postalCodes": [ { "postalCode": "98101", "countryCode": "US" } ] }
                                """)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("#1991 - PUT /v1/service-areas/{id}/postal-codes refuses an empty set")
    void shouldReturnBadRequestWhenReplacementSetIsEmpty() throws Exception {
        mockMvc.perform(withGatewayAuth(
                        put("/v1/service-areas/{id}/postal-codes", "018f1f5a-a777-7333-8222-777777777777")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{ \"postalCodes\": [] }")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("#1991 - an entry missing its countryCode is a 400, not a 500")
    void shouldReturnBadRequestWhenAReplacementEntryHasNoCountryCode() throws Exception {
        String id = createServiceArea("Country Code Guard Zone", "98120");

        mockMvc.perform(withGatewayAuth(put("/v1/service-areas/{id}/postal-codes", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"postalCodes\": [ { \"postalCode\": \"98121\" } ] }")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("#1991 - a null entry in the replacement set is a 400, not a 500")
    void shouldReturnBadRequestWhenAReplacementEntryIsNull() throws Exception {
        String id = createServiceArea("Null Entry Guard Zone", "98130");

        // A cascaded @Valid skips null elements, so this reaches the service validator; before
        // #1991 it dereferenced the null and the envelope rendered the NPE as a 500.
        mockMvc.perform(withGatewayAuth(put("/v1/service-areas/{id}/postal-codes", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"postalCodes\": [ null ] }")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("#1991 - createServiceArea also rejects a missing countryCode with 400")
    void shouldReturnBadRequestWhenCreateEntryHasNoCountryCode() throws Exception {
        mockMvc.perform(withGatewayAuth(post("/v1/service-areas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Create Guard Zone",
                                  "postalCodes": [ { "postalCode": "98140" } ]
                                }
                                """)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("#1991 - replacing postal codes changes which units findEligibleMobileUnits returns")
    void shouldChangeEligibilityWhenPostalCodesAreReplaced() throws Exception {
        // The acceptance criterion of #1991 is not "the DTO comes back changed" but "coverage
        // resolution follows", so this drives the real query either side of the replacement.
        // The unit is created INACTIVE and then flipped, and going ACTIVE now requires a complete
        // unit (CAP-325 D14): a travel buffer policy, a capability claim and coverage rules. The
        // claim validates against the catalog replica, which Flyway would seed and the H2 test
        // profile does not, so the IT plants the operation code itself. Eligibility still reads
        // status, coverage rules and postal codes only.
        String areaId = createServiceArea("Eligibility Shift Zone", "98160");
        String policyId = createTravelBufferPolicy("Eligibility Shift Buffer");
        seedCatalogOperationCode("CAP-MOBILE-DIAGNOSTIC");

        String unit = mockMvc.perform(withGatewayAuth(post("/v1/mobile-units")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "MU-ELIGIBILITY-1991",
                                  "baseLocationId": "%s",
                                  "status": "INACTIVE",
                                  "travelBufferPolicyId": "%s",
                                  "serviceCapabilityCodes": [ "CAP-MOBILE-DIAGNOSTIC" ],
                                  "coverageRules": [
                                    { "serviceAreaId": "%s", "ruleType": "SERVICE_AREA", "priority": 1 }
                                  ]
                                }
                                """.formatted(createBaseLocation(), policyId, areaId))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String unitId = new ObjectMapper().readTree(unit).get("id").asText();
        String unitName = new ObjectMapper().readTree(unit).get("name").asText();

        mockMvc.perform(withGatewayAuth(patch("/v1/mobile-units/{id}", unitId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"status\": \"ACTIVE\" }")))
                .andExpect(status().isOk());

        assertEligible("98160", unitName, true);
        assertEligible("98161", unitName, false);

        // Move the area's coverage from 98160 to 98161.
        mockMvc.perform(withGatewayAuth(put("/v1/service-areas/{id}/postal-codes", areaId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"postalCodes\": [ { \"postalCode\": \"98161\", \"countryCode\": \"US\" } ] }")))
                .andExpect(status().isOk());

        // Same unit, same coverage rule: only the area's postal codes moved.
        assertEligible("98160", unitName, false);
        assertEligible("98161", unitName, true);
    }

    @Test
    @DisplayName("#2253 / #2248 - list by base location embeds rules; an ACTIVE unit cannot lose its last rule")
    void shouldListOneLocationsUnitsWithRulesAndGuardTheLastRule() throws Exception {
        String areaId = createServiceArea("Filter Zone 2253", "98170");
        String policyId = createTravelBufferPolicy("Filter Buffer 2253");
        seedCatalogOperationCode("CAP-MOBILE-FILTER");
        String hubA = createBaseLocation();
        String hubB = createBaseLocation();
        String unitA = createActiveUnit("MU-FILTER-A", hubA, policyId, areaId);
        createActiveUnit("MU-FILTER-B", hubB, policyId, areaId);

        mockMvc.perform(withGatewayAuth(
                        get("/v1/mobile-units").param("baseLocationId", hubA).param("include", "coverageRules")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", containsInAnyOrder(unitA)))
                .andExpect(
                        jsonPath("$.content[0].coverageRules[0].serviceAreaId").value(areaId));

        mockMvc.perform(withGatewayAuth(put("/v1/mobile-units/{id}/coverage-rules", unitA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"rules\": [] }")))
                .andExpect(status().is(422));

        // The refused replace changed nothing: the rule is still there.
        mockMvc.perform(withGatewayAuth(get("/v1/mobile-units/{id}/coverage-rules", unitA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].serviceAreaId").value(areaId));
    }

    private String createActiveUnit(String name, String baseLocationId, String policyId, String areaId)
            throws Exception {
        String body = mockMvc.perform(withGatewayAuth(post("/v1/mobile-units")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "%s",
                                  "baseLocationId": "%s",
                                  "status": "ACTIVE",
                                  "travelBufferPolicyId": "%s",
                                  "serviceCapabilityCodes": [ "CAP-MOBILE-FILTER" ],
                                  "coverageRules": [
                                    { "serviceAreaId": "%s", "ruleType": "SERVICE_AREA", "priority": 1 }
                                  ]
                                }
                                """.formatted(name, baseLocationId, policyId, areaId))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return new ObjectMapper().readTree(body).get("id").asText();
    }

    private void assertEligible(String postalCode, String unitName, boolean expected) throws Exception {
        mockMvc.perform(withGatewayAuth(get("/v1/mobile-units:eligible")
                        .param("postalCode", postalCode)
                        .param("countryCode", "US")
                        .param("at", "2026-09-14T12:00:00Z")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", expected ? hasItem(unitName) : not(hasItem(unitName))));
    }

    private String createServiceArea(String name, String postalCode) throws Exception {
        String body = mockMvc.perform(withGatewayAuth(post("/v1/service-areas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "%s",
                                  "postalCodes": [ { "postalCode": "%s", "countryCode": "US" } ]
                                }
                                """.formatted(name, postalCode))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return new ObjectMapper().readTree(body).get("id").asText();
    }

    private String createTravelBufferPolicy(String name) throws Exception {
        String body = mockMvc.perform(withGatewayAuth(post("/v1/travel-buffer-policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "%s",
                                  "bufferType": "FLAT_MINUTES",
                                  "bufferValue": 15
                                }
                                """.formatted(name))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return new ObjectMapper().readTree(body).get("id").asText();
    }

    /** Plants an active catalog operation code as pos-catalog's fact would (CAP-325 D14). */
    private void seedCatalogOperationCode(String operationCode) {
        extCatalogServiceReplicaRepository.save(ExtCatalogServiceReplica.builder()
                .serviceId(UUID.randomUUID())
                .operationCode(operationCode)
                .name(operationCode)
                .active(true)
                .aggregateVersion(1L)
                .updatedAt(Instant.now())
                .build());
    }

    @Test
    @DisplayName("#76 - POST /v1/travel-buffer-policies returns 201")
    void shouldCreateTravelBufferPolicy() throws Exception {
        String payload = """
                {
                  "name": "Standard Travel Buffer",
                  "bufferType": "FLAT_MINUTES",
                  "bufferValue": 15,
                  "notes": "default"
                }
                """;

        mockMvc.perform(withGatewayAuth(post("/v1/travel-buffer-policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("#76 - GET /v1/travel-buffer-policies returns 200")
    void shouldListTravelBufferPolicies() throws Exception {
        mockMvc.perform(withGatewayAuth(get("/v1/travel-buffer-policies"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("#76 - PATCH /v1/travel-buffer-policies/{id} returns 404 when missing")
    void shouldReturnNotFoundWhenPatchingMissingTravelBufferPolicy() throws Exception {
        String payload = """
                {
                  "bufferValue": 20
                }
                """;

        mockMvc.perform(withGatewayAuth(patch("/v1/travel-buffer-policies/{id}", "018f1f5a-a666-7333-8222-666666666666")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("#76 - PATCH /v1/travel-buffer-policies/{id} returns 400 for invalid id")
    void shouldReturnBadRequestForInvalidTravelBufferPolicyId() throws Exception {
        String payload = """
                {
                  "bufferValue": 20
                }
                """;

        mockMvc.perform(withGatewayAuth(patch("/v1/travel-buffer-policies/{id}", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("#76 - GET /v1/mobile-units:eligible returns 200")
    void shouldReturnEligibleMobileUnits() throws Exception {
        mockMvc.perform(withGatewayAuth(get("/v1/mobile-units:eligible")
                        .param("postalCode", "98101")
                        .param("countryCode", "US")
                        .param("at", "2026-02-22T10:00:00Z")))
                .andExpect(status().isOk());
    }
}
