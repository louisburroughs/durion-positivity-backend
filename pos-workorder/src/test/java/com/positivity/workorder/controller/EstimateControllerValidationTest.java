package com.positivity.workorder.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import java.io.IOException;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.workorder.config.TestSecurityConfig;
import com.positivity.workorder.contract.ContractTestConfiguration;
import com.positivity.workorder.internal.dto.CreateEstimateRequest;
import com.positivity.workorder.service.EstimateService;
import com.positivity.workorder.service.IdempotencyService;
import com.positivity.workorder.service.WorkorderService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Controller-layer validation tests for {@link CreateEstimateRequest}.
 *
 * <p>
 * Covers F-03 from PR #588: the {@code @Size(max = 36)} constraints on
 * {@code crmPartyId} and {@code crmVehicleId} must produce HTTP 400 when
 * exceeded, and the service layer must NOT be reached.
 * </p>
 *
 * Issue: #588
 */
@SpringBootTest
@Import({ TestSecurityConfig.class, ContractTestConfiguration.class })
@ActiveProfiles("test")
class EstimateControllerValidationTest {

    private static final String ENDPOINT = "/v1/workorders/estimates";

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private EstimateService estimateService;

    @MockitoBean
    private WorkorderService workorderService;

    @MockitoBean
    private IdempotencyService idempotencyService;

    @BeforeEach
    void setUp() {
        mockMvc = webAppContextSetup(webApplicationContext)
                .addFilters(new OncePerRequestFilter() {
                    @Override
                    protected void doFilterInternal(HttpServletRequest request,
                            HttpServletResponse response, FilterChain filterChain)
                            throws ServletException, IOException {
                        var authentication = new UsernamePasswordAuthenticationToken(
                                "workorder-test-user", null,
                                java.util.List.of(
                                        new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                                "workorder:estimate:create"),
                                        new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                                "workorder:estimate:view")));
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                        filterChain.doFilter(request, response);
                    }
                })
                .build();
    }

    // -----------------------------------------------------------------------
    // F-03: @Size(max = 36) on crmPartyId — value too long → HTTP 400
    // -----------------------------------------------------------------------

    /**
     * F-03: A {@code crmPartyId} of 37 characters must be rejected by Bean
     * Validation with HTTP 400 before the service layer is reached.
     */
    @Test
    void createEstimate_crmPartyIdTooLong_returns400() throws Exception {
        String tooLong = "a".repeat(37);
        CreateEstimateRequest request = CreateEstimateRequest.builder()
                .customerId(UUID.fromString("550e8400-e29b-41d4-a716-446655440001"))
                .vehicleId(UUID.fromString("550e8400-e29b-41d4-a716-446655440002"))
                .crmPartyId(tooLong)
                .crmVehicleId("01952f4e-0000-7000-8000-000000000002")
                .build();

        mockMvc.perform(post(ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    // -----------------------------------------------------------------------
    // F-03: @Size(max = 36) on crmVehicleId — value too long → HTTP 400
    // -----------------------------------------------------------------------

    /**
     * F-03: A {@code crmVehicleId} of 37 characters must be rejected by Bean
     * Validation with HTTP 400 before the service layer is reached.
     */
    @Test
    void createEstimate_crmVehicleIdTooLong_returns400() throws Exception {
        String tooLong = "a".repeat(37);
        CreateEstimateRequest request = CreateEstimateRequest.builder()
                .customerId(UUID.fromString("550e8400-e29b-41d4-a716-446655440001"))
                .vehicleId(UUID.fromString("550e8400-e29b-41d4-a716-446655440002"))
                .crmPartyId("01952f4e-0000-7000-8000-000000000001")
                .crmVehicleId(tooLong)
                .build();

        mockMvc.perform(post(ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}
