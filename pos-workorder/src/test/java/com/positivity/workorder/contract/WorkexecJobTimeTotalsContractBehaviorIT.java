package com.positivity.workorder.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.workorder.config.TestSecurityConfig;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderLaborEntry;
import com.positivity.workorder.internal.entity.WorkorderStatus;
import com.positivity.workorder.internal.repository.TechnicianAssignmentRepository;
import com.positivity.workorder.internal.repository.WorkorderLaborEntryRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@DisplayName("CAP-121 Workexec Job Time Totals Contract Behavior Tests")
@SpringBootTest
@ActiveProfiles("test")
@Import({ ContractTestConfiguration.class, TestSecurityConfig.class })
class WorkexecJobTimeTotalsContractBehaviorIT {

    private static final List<SimpleGrantedAuthority> TEST_AUTHORITIES = List.of(
            new SimpleGrantedAuthority("workorder:labor:view"),
            new SimpleGrantedAuthority("workorder:labor:add"));

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WorkorderRepository workorderRepository;

    @Autowired
    private WorkorderLaborEntryRepository laborEntryRepository;

    @Autowired
    private TechnicianAssignmentRepository technicianAssignmentRepository;

    @BeforeEach
    void setUp() {
        mockMvc = webAppContextSetup(webApplicationContext)
                .addFilters(new OncePerRequestFilter() {
                    @Override
                    protected void doFilterInternal(
                            HttpServletRequest request,
                            HttpServletResponse response,
                            FilterChain filterChain) throws ServletException, IOException {
                        var authentication = new UsernamePasswordAuthenticationToken(
                                "workorder-test-user", null, TEST_AUTHORITIES);
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                        filterChain.doFilter(request, response);
                    }
                })
                .build();
    }

    @AfterEach
    void tearDown() {
        laborEntryRepository.deleteAll();
        technicianAssignmentRepository.deleteAll();
        workorderRepository.deleteAll();
    }

    @Test
    @DisplayName("AC1: GET job-time-totals with valid params returns grouped totals by technicianId, locationId, localDate")
    void getJobTimeTotalsWithValidParamsReturnsGroupedTotals() throws Exception {
        UUID locationA = UUID.randomUUID();
        UUID locationB = UUID.randomUUID();
        UUID technicianA = UUID.randomUUID();
        UUID technicianB = UUID.randomUUID();

        Workorder completedAtLocationA = seedWorkorder(locationA, WorkorderStatus.COMPLETED);
        Workorder completedAtLocationB = seedWorkorder(locationB, WorkorderStatus.COMPLETED);

        seedLaborEntry(completedAtLocationA, technicianA,
                LocalDateTime.of(2026, 2, 14, 8, 0),
                LocalDateTime.of(2026, 2, 14, 9, 0),
                BigDecimal.valueOf(1.00));
        seedLaborEntry(completedAtLocationA, technicianA,
                LocalDateTime.of(2026, 2, 14, 10, 0),
                LocalDateTime.of(2026, 2, 14, 10, 30),
                BigDecimal.valueOf(0.50));
        seedLaborEntry(completedAtLocationB, technicianB,
                LocalDateTime.of(2026, 2, 14, 12, 0),
                LocalDateTime.of(2026, 2, 14, 13, 0),
                BigDecimal.valueOf(1.00));

        String response = mockMvc.perform(get("/v1/workexec/job-time-totals")
                .param("startDate", "2026-02-14")
                .param("endDate", "2026-02-14")
                .param("timezone", "UTC"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        var json = objectMapper.readTree(response);
        assertThat(json).hasSize(2);

        assertThat(json.findValuesAsText("technicianId"))
                .containsExactlyInAnyOrder(technicianA.toString(), technicianB.toString());
        assertThat(json.findValuesAsText("locationId"))
                .containsExactlyInAnyOrder(locationA.toString(), locationB.toString());
        assertThat(json.findValuesAsText("localDate"))
                .containsOnly("2026-02-14");
        assertThat(json.findValues("totalJobMinutes").stream().map(node -> node.asInt()).toList())
                .containsExactlyInAnyOrder(90, 60);
    }

    @Test
    @DisplayName("AC2: GET job-time-totals missing required startDate returns 400")
    void getJobTimeTotalsMissingStartDateReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/v1/workexec/job-time-totals")
                .param("endDate", "2026-02-14")
                .param("timezone", "UTC"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("AC3: GET job-time-totals includes only finalized/completed labor and excludes in-progress work")
    void getJobTimeTotalsIncludesOnlyCompletedWorkorders() throws Exception {
        UUID sharedLocation = UUID.randomUUID();
        UUID sharedTechnician = UUID.randomUUID();

        Workorder completedWorkorder = seedWorkorder(sharedLocation, WorkorderStatus.COMPLETED);
        Workorder inProgressWorkorder = seedWorkorder(sharedLocation, WorkorderStatus.WORK_IN_PROGRESS);

        seedLaborEntry(completedWorkorder, sharedTechnician,
                LocalDateTime.of(2026, 2, 15, 8, 0),
                LocalDateTime.of(2026, 2, 15, 9, 0),
                BigDecimal.valueOf(1.00));
        seedLaborEntry(inProgressWorkorder, sharedTechnician,
                LocalDateTime.of(2026, 2, 15, 9, 0),
                LocalDateTime.of(2026, 2, 15, 10, 0),
                BigDecimal.valueOf(1.00));

        String response = mockMvc.perform(get("/v1/workexec/job-time-totals")
                .param("startDate", "2026-02-15")
                .param("endDate", "2026-02-15")
                .param("timezone", "UTC")
                .param("locationId", sharedLocation.toString())
                .param("technicianIds", sharedTechnician.toString()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        var json = objectMapper.readTree(response);
        assertThat(json).hasSize(1);
        assertThat(json.get(0).path("technicianId").asText()).isEqualTo(sharedTechnician.toString());
        assertThat(json.get(0).path("locationId").asText()).isEqualTo(sharedLocation.toString());
        assertThat(LocalDate.parse(json.get(0).path("localDate").asText())).isEqualTo(LocalDate.of(2026, 2, 15));
        assertThat(json.get(0).path("totalJobMinutes").asInt()).isEqualTo(60);
    }

    private Workorder seedWorkorder(UUID locationId, WorkorderStatus status) {
        Workorder workorder = Workorder.builder()
                .shopId(locationId)
                .customerId(UUID.randomUUID())
                .vehicleId(UUID.randomUUID())
                .status(status)
                .isReopened(false)
                .build();
        return workorderRepository.save(workorder);
    }

    private WorkorderLaborEntry seedLaborEntry(
            Workorder workorder,
            UUID technicianId,
            LocalDateTime startTimeUtc,
            LocalDateTime endTimeUtc,
            BigDecimal hoursWorked) {
        WorkorderLaborEntry entry = WorkorderLaborEntry.builder()
                .workorder(workorder)
                .workorderId(workorder.getId())
                .workorderServiceId(UUID.randomUUID())
                .technicianId(technicianId)
                .startTime(startTimeUtc)
                .endTime(endTimeUtc)
                .hoursWorked(hoursWorked)
                .notes("contract-test")
                .createdBy(UUID.randomUUID())
                .createdAt(Instant.now().atOffset(ZoneOffset.UTC).toInstant())
                .build();
        return laborEntryRepository.save(entry);
    }
}
