package com.positivity.workorder.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.OncePerRequestFilter;

import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.workorder.config.TestSecurityConfig;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderStatus;
import com.positivity.workorder.internal.repository.TechnicianAssignmentRepository;
import com.positivity.workorder.internal.repository.WorkorderLaborEntryRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;

@DisplayName("CAP-121 Workexec Timer Contract Behavior Tests")
@SpringBootTest
@ActiveProfiles("test")
@Import({ ContractTestConfiguration.class, TestSecurityConfig.class })
class WorkexecTimerContractBehaviorIT {

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
    @DisplayName("AC1: start then stop transitions timer from ACTIVE to COMPLETED")
    void startThenStopTransitionsActiveToCompleted() throws Exception {
        Workorder workorder = seedWorkorderInProgress();
        UUID mechanicId = UUID.randomUUID();

        Map<String, Object> startPayload = Map.of(
                "workorderId", workorder.getId().toString(),
                "laborCode", "DIAG");

        mockMvc.perform(post("/v1/workexec/time-entries/timer/start")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-User-Id", mechanicId.toString())
                .content(objectMapper.writeValueAsString(startPayload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.mechanicId").value(mechanicId.toString()))
                .andExpect(jsonPath("$.workorderId").value(workorder.getId().toString()));

        mockMvc.perform(get("/v1/workexec/time-entries/timer/active")
                .header("X-User-Id", mechanicId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));

        mockMvc.perform(post("/v1/workexec/time-entries/timer/stop")
                .header("X-User-Id", mechanicId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stopped[0].status").value("COMPLETED"));

        mockMvc.perform(get("/v1/workexec/time-entries/timer/active")
                .header("X-User-Id", mechanicId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*]").isEmpty());
    }

    @Test
    @DisplayName("AC2: second timer start for same mechanic returns 409 TIMER_ALREADY_ACTIVE")
    void secondStartReturnsTimerAlreadyActive() throws Exception {
        Workorder workorder = seedWorkorderInProgress();
        UUID mechanicId = UUID.randomUUID();

        Map<String, Object> startPayload = Map.of(
                "workorderId", workorder.getId().toString());

        mockMvc.perform(post("/v1/workexec/time-entries/timer/start")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-User-Id", mechanicId.toString())
                .content(objectMapper.writeValueAsString(startPayload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(post("/v1/workexec/time-entries/timer/start")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-User-Id", mechanicId.toString())
                .content(objectMapper.writeValueAsString(startPayload)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TIMER_ALREADY_ACTIVE"));
    }

    @Test
    @DisplayName("AC3: stop with no active timer returns 409 NO_ACTIVE_TIMER")
    void stopWithoutActiveTimerReturnsConflict() throws Exception {
        UUID mechanicId = UUID.randomUUID();

        mockMvc.perform(post("/v1/workexec/time-entries/timer/stop")
                .header("X-User-Id", mechanicId.toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_ACTIVE_TIMER"));
    }

    private Workorder seedWorkorderInProgress() {
        Workorder workorder = Workorder.builder()
                .shopId(UUID.randomUUID())
                .customerId(UUID.randomUUID())
                .vehicleId(UUID.randomUUID())
                .status(WorkorderStatus.WORK_IN_PROGRESS)
                .isReopened(false)
                .build();
        return workorderRepository.save(workorder);
    }
}
