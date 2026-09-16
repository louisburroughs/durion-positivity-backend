package com.positivity.catalog.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.catalog.config.TestSecurityConfig;
import com.positivity.catalog.internal.dto.RequiredSkillDto;
import com.positivity.catalog.internal.dto.ServiceDto;
import com.positivity.catalog.internal.dto.ServiceRequirementsRequest;
import com.positivity.catalog.internal.exception.CatalogNotFoundException;
import com.positivity.catalog.internal.exception.CatalogUnprocessableException;
import com.positivity.catalog.internal.security.CatalogPermissions;
import com.positivity.catalog.internal.service.ServiceRequirementService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** CAP-329: PUT /v1/products/services/{serviceId}/requirements — gate, body, and the 404/422 envelopes. */
@WebMvcTest(ServiceRequirementController.class)
@Import({TestSecurityConfig.class, CatalogExceptionHandler.class})
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100"})
class ServiceRequirementControllerTest {

    private static final String AUTHORITIES = "X-Authorities";
    private static final UUID SERVICE_ID = UUID.fromString("0196cf6f-c8dd-7ee0-93e7-f48a5698a535");
    private static final UUID HEAVY_BRAKES = UUID.fromString("01960011-0000-7000-8000-000000000041");
    private static final String BODY = """
            {"requiredSkills": [{"skillId": "01960011-0000-7000-8000-000000000041", "minGvwrClass": 4, "maxGvwrClass": 8}]}
            """;

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    java.time.Clock clock;

    @MockitoBean
    org.springframework.cache.CacheManager cacheManager;

    @MockitoBean
    ServiceRequirementService serviceRequirementService;

    @org.junit.jupiter.api.BeforeEach
    void setUpClock() {
        // CatalogExceptionHandler stamps every envelope from the clock; a bare mock would make the
        // handler itself throw and the original exception surface unhandled.
        org.mockito.Mockito.lenient().when(clock.instant()).thenReturn(Instant.parse("2026-09-16T12:00:00Z"));
        org.mockito.Mockito.lenient().when(clock.getZone()).thenReturn(java.time.ZoneOffset.UTC);
    }

    @Test
    void declaresRequirementsAndReturnsTheService() throws Exception {
        ServiceDto dto = new ServiceDto();
        dto.setId(SERVICE_ID);
        dto.setRequirementsConfiguredAt(Instant.parse("2026-09-16T12:00:00Z"));
        dto.setRequiredSkills(List.of(RequiredSkillDto.builder()
                .skillId(HEAVY_BRAKES)
                .skillCode("BRAKES-MEDIUM_HEAVY")
                .minGvwrClass(4)
                .maxGvwrClass(8)
                .skillActive(true)
                .build()));
        when(serviceRequirementService.setRequirements(eq(SERVICE_ID), any(), any()))
                .thenReturn(dto);

        mockMvc.perform(put("/v1/products/services/{id}/requirements", SERVICE_ID)
                        .header(AUTHORITIES, CatalogPermissions.SERVICE_REQUIREMENT_MANAGE)
                        .header("X-User", "maya")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requirementsConfiguredAt").value("2026-09-16T12:00:00Z"))
                .andExpect(jsonPath("$.requiredSkills[0].skillCode").value("BRAKES-MEDIUM_HEAVY"))
                .andExpect(jsonPath("$.requiredSkills[0].minGvwrClass").value(4));

        ArgumentCaptor<ServiceRequirementsRequest> request = ArgumentCaptor.forClass(ServiceRequirementsRequest.class);
        verify(serviceRequirementService).setRequirements(eq(SERVICE_ID), request.capture(), any());
        org.assertj.core.api.Assertions.assertThat(request.getValue().getRequiredSkills())
                .singleElement()
                .satisfies(skill -> {
                    org.assertj.core.api.Assertions.assertThat(skill.getSkillId())
                            .isEqualTo(HEAVY_BRAKES);
                    org.assertj.core.api.Assertions.assertThat(skill.getMinGvwrClass())
                            .isEqualTo(4);
                });
    }

    @Test
    void withoutTheManagePermissionIs403() throws Exception {
        mockMvc.perform(put("/v1/products/services/{id}/requirements", SERVICE_ID)
                        .header(AUTHORITIES, CatalogPermissions.SERVICE_TYPE_VIEW)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(serviceRequirementService);
    }

    @Test
    void missingRequiredSkillsListIs400() throws Exception {
        mockMvc.perform(put("/v1/products/services/{id}/requirements", SERVICE_ID)
                        .header(AUTHORITIES, CatalogPermissions.SERVICE_REQUIREMENT_MANAGE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(serviceRequirementService);
    }

    @Test
    void unknownSkillIs422WithItsCode() throws Exception {
        when(serviceRequirementService.setRequirements(eq(SERVICE_ID), any(), any()))
                .thenThrow(new CatalogUnprocessableException("SKILL_UNKNOWN", "Skill x is not in the skill registry"));

        mockMvc.perform(put("/v1/products/services/{id}/requirements", SERVICE_ID)
                        .header(AUTHORITIES, CatalogPermissions.SERVICE_REQUIREMENT_MANAGE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SKILL_UNKNOWN"))
                .andExpect(jsonPath("$.message").value("Skill x is not in the skill registry"));
    }

    @Test
    void unknownServiceIs404() throws Exception {
        when(serviceRequirementService.setRequirements(eq(SERVICE_ID), any(), any()))
                .thenThrow(new CatalogNotFoundException("Service not found: " + SERVICE_ID));

        mockMvc.perform(put("/v1/products/services/{id}/requirements", SERVICE_ID)
                        .header(AUTHORITIES, CatalogPermissions.SERVICE_REQUIREMENT_MANAGE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNotFound());
    }
}
