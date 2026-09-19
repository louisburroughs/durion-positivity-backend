package com.positivity.invoice.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.invoice.internal.exception.ElevationDeniedException;
import com.positivity.invoice.internal.security.InvoicePermissions;
import com.positivity.invoice.internal.service.ElevationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Issue #1720: a denied manager-approval elevation answered a bodiless 401. It now answers the
 * ADR-0017 §3 envelope with one fixed {@code ELEVATION_DENIED} code and message, whatever the
 * reason for the denial, so the response still does not reveal which check failed.
 *
 * <p>Full context rather than a web slice, for the reason given on {@link
 * BillingRulesControllerErrorHandlingTest} (issue #1723).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Manager-approval elevation answers its 401 with the error envelope (#1720)")
class ElevationControllerErrorHandlingTest {

    private static final String BODY = """
            {"managerEmployeeNumber":"EMP-0001",
             "invoiceId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a20"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ElevationService elevationService;

    @Test
    @DisplayName("a denied elevation answers 401 ELEVATION_DENIED with a fixed message")
    void deniedElevationAnswers401WithTheEnvelope() throws Exception {
        when(elevationService.elevate(anyString(), any()))
                .thenThrow(new ElevationDeniedException("detail that must not reach the caller"));

        mockMvc.perform(post("/v1/billing/auth/elevate")
                        .header("X-User", "test-user")
                        .header("X-Authorities", InvoicePermissions.FINALIZE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ELEVATION_DENIED"))
                .andExpect(jsonPath("$.message").value("Manager approval denied"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(header().exists("X-Correlation-Id"));
    }
}
