package com.positivity.workorder.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.workorder.config.TestSecurityConfig;
import com.positivity.workorder.contract.ContractTestConfiguration;
import com.positivity.workorder.internal.dto.CreateEstimateFromAppointmentRequest;
import com.positivity.workorder.internal.dto.CreateEstimateFromAppointmentResponse;
import com.positivity.workorder.service.EstimateService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * Controller-layer tests for CAP-140 Story #65: Create Draft Estimate from
 * Appointment.
 *
 * <p>
 * Covers AC6 (HTTP 201 for newly created estimate) and AC7 (HTTP 200 for
 * idempotent existing-estimate response).
 *
 * Issue: CAP-140
 */
@SpringBootTest
@Import({ TestSecurityConfig.class, ContractTestConfiguration.class })
@ActiveProfiles("test")
class EstimateFromAppointmentControllerTest {

        private static final String ENDPOINT = "/v1/workorders/estimates/from-appointment";

        private MockMvc mockMvc;

        @Autowired
        private WebApplicationContext webApplicationContext;

        @Autowired
        private ObjectMapper objectMapper;

        @MockitoBean
        private EstimateService estimateService;

        private static final List<SimpleGrantedAuthority> TEST_AUTHORITIES = List.of(
                        new SimpleGrantedAuthority("workorder:estimate:create"),
                        new SimpleGrantedAuthority("workorder:estimate:view"));

        @BeforeEach
        void setUp() {
                mockMvc = webAppContextSetup(webApplicationContext)
                                .addFilters(new OncePerRequestFilter() {
                                        @Override
                                        protected void doFilterInternal(HttpServletRequest request,
                                                        HttpServletResponse response, FilterChain filterChain)
                                                        throws ServletException, IOException {
                                                var authentication = new UsernamePasswordAuthenticationToken(
                                                                "workorder-test-user", null, TEST_AUTHORITIES);
                                                SecurityContextHolder.getContext().setAuthentication(authentication);
                                                filterChain.doFilter(request, response);
                                        }
                                })
                                .build();
        }

        private static final UUID APPOINTMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
        private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
        private static final UUID VEHICLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
        private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");

        private CreateEstimateFromAppointmentRequest validRequest() {
                return CreateEstimateFromAppointmentRequest.builder()
                                .idempotencyKey(UUID.randomUUID())
                                .appointmentId(APPOINTMENT_ID)
                                .customerId(CUSTOMER_ID)
                                .vehicleId(VEHICLE_ID)
                                .locationId(LOCATION_ID)
                                .build();
        }

        // Issue CAP-140 start: AC6 — new estimate returns HTTP 201

        /**
         * AC6: POST /v1/workorders/estimates/from-appointment with a valid body for a
         * new
         * appointment returns HTTP 201 and includes {@code estimateId} in the response
         * body.
         */
        @Test
        void postFromAppointment_newAppointment_returns201WithEstimateId() throws Exception {
                UUID newEstimateId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
                when(estimateService.createEstimateFromAppointment(any()))
                                .thenReturn(CreateEstimateFromAppointmentResponse.builder()
                                                .estimateId(newEstimateId)
                                                .status("DRAFT")
                                                .created(true)
                                                .build());

                mockMvc.perform(post(ENDPOINT)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(validRequest())))
                                .andExpect(status().isCreated()) // 201
                                .andExpect(jsonPath("$.estimateId").value(newEstimateId.toString()))
                                .andExpect(jsonPath("$.status").value("DRAFT"));
        }

        // Issue CAP-140 end

        // Issue CAP-140 start: AC7 — existing estimate (idempotent) returns HTTP 200

        /**
         * AC7: POST /v1/workorders/estimates/from-appointment with an appointmentId
         * that
         * already has an associated estimate returns HTTP 200 (idempotent hit) with the
         * existing {@code estimateId}.
         */
        @Test
        void postFromAppointment_existingEstimateForAppointmentId_returns200WithExistingEstimateId() throws Exception {
                UUID existingEstimateId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
                when(estimateService.createEstimateFromAppointment(any()))
                                .thenReturn(CreateEstimateFromAppointmentResponse.builder()
                                                .estimateId(existingEstimateId)
                                                .status("DRAFT")
                                                .created(false)
                                                .build());

                // Use the same appointmentId to express intent: existing appointment → 200
                var idempotentRequest = CreateEstimateFromAppointmentRequest.builder()
                                .idempotencyKey(UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"))
                                .appointmentId(APPOINTMENT_ID) // same appointmentId as AC6
                                .customerId(CUSTOMER_ID)
                                .vehicleId(VEHICLE_ID)
                                .locationId(LOCATION_ID)
                                .build();

                mockMvc.perform(post(ENDPOINT)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(idempotentRequest)))
                                .andExpect(status().isOk()) // 200
                                .andExpect(jsonPath("$.estimateId").value(existingEstimateId.toString()));
        }

        // Issue CAP-140 end
}
