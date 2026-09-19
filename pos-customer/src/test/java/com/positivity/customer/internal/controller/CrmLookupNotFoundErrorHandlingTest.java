package com.positivity.customer.internal.controller;

import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.customer.config.WebMvcTestSecurityConfig;
import com.positivity.customer.internal.config.CrmExceptionHandler;
import com.positivity.customer.internal.service.CrmVehicleService;
import com.positivity.customer.internal.service.CustomerRequirementsService;
import com.positivity.customer.internal.service.PartyService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Issue #1720: the snapshot, vehicle and requirements lookups answered an unknown id with a
 * bodiless {@code 404} built in the controller. They now throw {@code
 * CrmResourceNotFoundException}, which {@link CrmExceptionHandler} renders as the ADR-0017 §3
 * envelope the operations declare. The mocked services return their "absent" defaults ({@code
 * null} or an empty {@code Optional}), so every request below takes the not-found branch.
 */
@WebMvcTest({CrmSnapshotController.class, CrmVehiclesController.class, CustomerRequirementsController.class})
@Import({WebMvcTestSecurityConfig.class, CrmExceptionHandler.class})
@ActiveProfiles("test")
@DisplayName("CRM lookups answer an unknown id with the 404 error envelope (#1720)")
class CrmLookupNotFoundErrorHandlingTest {

    private static final UUID ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b");
    private static final UUID OTHER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PartyService partyService;

    @MockitoBean
    private CrmVehicleService crmVehicleService;

    @MockitoBean
    private CustomerRequirementsService customerRequirementsService;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUpClock() {
        lenient().when(clock.instant()).thenReturn(Instant.parse("2026-09-19T12:00:00Z"));
        lenient().when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "/v1/crm/snapshot/party/{id}, crm:party:view",
        "/v1/crm/snapshot/party/{id}/billing-rules, crm:party:view",
        "/v1/crm/snapshot/vehicle/{id}, crm:party:view",
        "/v1/crm/{id}/vehicles, crm:vehicle:view",
        "/v1/crm/{id}/vehicles/{other}, crm:vehicle:view",
        "/v1/customers/{id}/requirements-met, crm:party:view",
    })
    @DisplayName("an unknown id answers 404 RESOURCE_NOT_FOUND with the envelope")
    void unknownIdAnswers404WithTheEnvelope(String path, String authority) throws Exception {
        String uri = path.replace("{id}", ID.toString()).replace("{other}", OTHER_ID.toString());

        mockMvc.perform(get(uri).header("X-Authorities", authority).header("X-Correlation-Id", "corr-1720"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Correlation-Id", "corr-1720"))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.correlationId").value("corr-1720"));
    }
}
