package com.positivity.location.internal.controller;

import static com.positivity.location.config.LocationScopeTestSupport.as;
import static com.positivity.location.config.LocationScopeTestSupport.clearCaller;
import static com.positivity.location.config.LocationScopeTestSupport.preRollout;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.location.config.LocationScopeTestSupport;
import com.positivity.location.internal.entity.BaySpecialtyOperationEntity;
import com.positivity.location.internal.entity.ExtCatalogServiceReplica;
import com.positivity.location.internal.repository.BaySpecialtyOperationRepository;
import com.positivity.location.internal.repository.ExtCatalogServiceReplicaRepository;
import com.positivity.location.internal.security.LocationPermissions;
import com.positivity.location.internal.service.BayTypeServiceImpl;
import com.positivity.security.common.LocationScopeAutoConfiguration;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /v1/bay-types} (#2247) through the real controller, method security and
 * {@link BayTypeServiceImpl}; only the two repositories are mocked.
 */
@WebMvcTest(BayTypeController.class)
@Import({
    BayTypeServiceImpl.class,
    LocationScopeAutoConfiguration.class,
    WebCommonErrorAutoConfiguration.class,
    LocationScopeTestSupport.SliceConfig.class
})
@ActiveProfiles("test")
class BayTypeControllerTest {

    private static final String URL = "/v1/bay-types";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BaySpecialtyOperationRepository operationRepository;

    @MockitoBean
    private ExtCatalogServiceReplicaRepository replicaRepository;

    @AfterEach
    void tearDown() {
        clearCaller();
    }

    @Test
    @DisplayName("returns every bay type in enum order with sorted, named defaults and empty lists elsewhere")
    void listsEveryBayType() throws Exception {
        as(preRollout(LocationPermissions.BAY_READ));
        when(operationRepository.findAll())
                .thenReturn(List.of(
                        row("TIRE_SERVICE", "TIRE-ROTATE"),
                        row("TIRE_SERVICE", "TIRE-BALANCE"),
                        row("ALIGNMENT", "WHEEL-ALIGNMENT-4-WHEEL"),
                        row("ALIGNMENT", "RETIRED-ALIGN")));
        activeServices(
                service("TIRE-ROTATE", "Tire Rotation"),
                service("TIRE-BALANCE", "Tire Balance"),
                service("WHEEL-ALIGNMENT-4-WHEEL", "4-Wheel Alignment"));

        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(6)))
                .andExpect(jsonPath("$[*].bayType")
                        .value(contains(
                                "GENERAL_SERVICE",
                                "ALIGNMENT",
                                "TIRE_SERVICE",
                                "HEAVY_DUTY",
                                "INSPECTION",
                                "WASH_DETAIL")))
                .andExpect(jsonPath("$[0].defaultServiceCapabilityCodes", empty()))
                .andExpect(jsonPath("$[0].defaultServices", empty()))
                .andExpect(jsonPath("$[0].acceptsGeneralWork").value(true))
                .andExpect(jsonPath("$[1].defaultServiceCapabilityCodes").value(contains("WHEEL-ALIGNMENT-4-WHEEL")))
                .andExpect(jsonPath("$[1].defaultServices[0].operationCode").value("WHEEL-ALIGNMENT-4-WHEEL"))
                .andExpect(jsonPath("$[1].defaultServices[0].name").value("4-Wheel Alignment"))
                .andExpect(
                        jsonPath("$[2].defaultServiceCapabilityCodes").value(contains("TIRE-BALANCE", "TIRE-ROTATE")))
                .andExpect(jsonPath("$[2].defaultServices[*].name").value(contains("Tire Balance", "Tire Rotation")))
                .andExpect(jsonPath("$[3].defaultServiceCapabilityCodes", empty()))
                .andExpect(jsonPath("$[4].defaultServiceCapabilityCodes", empty()))
                .andExpect(jsonPath("$[5].defaultServiceCapabilityCodes", empty()))
                .andExpect(jsonPath("$[5].acceptsGeneralWork").value(false));
    }

    @Test
    @DisplayName("an unprovisioned tenant sees every bay type with empty defaults, not a 404")
    void unprovisionedTenant() throws Exception {
        as(preRollout(LocationPermissions.BAY_READ));
        when(operationRepository.findAll()).thenReturn(List.of());

        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(6)))
                .andExpect(jsonPath("$[*].defaultServiceCapabilityCodes[*]", empty()))
                .andExpect(jsonPath("$[*].defaultServices[*]", empty()));
        verify(replicaRepository, never()).findByOperationCodeInAndActiveIsTrue(any());
    }

    @Test
    @DisplayName("without location:bay:read method security denies the call (403) and the map is never read")
    void forbiddenWithoutBayRead() {
        as(preRollout(LocationPermissions.SERVICE_AREA_READ));

        // The slice has no SecurityFilterChain (see SliceConfig), so the denial surfaces as the
        // exception the production ExceptionTranslationFilter turns into a 403.
        assertThatThrownBy(() -> mockMvc.perform(get(URL))).hasRootCauseInstanceOf(AuthorizationDeniedException.class);
        verify(operationRepository, never()).findAll();
    }

    private void activeServices(ExtCatalogServiceReplica... services) {
        List<ExtCatalogServiceReplica> all = List.of(services);
        when(replicaRepository.findByOperationCodeInAndActiveIsTrue(any())).thenAnswer(invocation -> {
            Collection<String> codes = invocation.getArgument(0);
            return all.stream()
                    .filter(service -> codes.contains(service.getOperationCode()))
                    .toList();
        });
    }

    private static BaySpecialtyOperationEntity row(String bayType, String code) {
        return BaySpecialtyOperationEntity.builder()
                .id(UUID.randomUUID())
                .bayType(bayType)
                .operationCode(code)
                .build();
    }

    private static ExtCatalogServiceReplica service(String code, String name) {
        return ExtCatalogServiceReplica.builder()
                .serviceId(UUID.randomUUID())
                .operationCode(code)
                .name(name)
                .active(true)
                .build();
    }
}
