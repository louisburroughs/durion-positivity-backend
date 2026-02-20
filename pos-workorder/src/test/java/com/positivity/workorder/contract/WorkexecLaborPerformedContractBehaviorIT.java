package com.positivity.workorder.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import java.math.BigDecimal;
import java.util.List;
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

import java.io.IOException;

@DisplayName("CAP-121 Workexec Labor Performed Contract Behavior Tests")
@SpringBootTest
@ActiveProfiles("test")
@Import({ ContractTestConfiguration.class, TestSecurityConfig.class })
class WorkexecLaborPerformedContractBehaviorIT {

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
    @DisplayName("AC1: POST labor-performed returns 201 and creates labor record")
    void postLaborPerformedReturnsCreatedAndPersistsRecord() throws Exception {
        Workorder workorder = seedWorkorderInProgress();
        UUID technicianId = UUID.randomUUID();
        String timeEntryId = "te-" + UUID.randomUUID();

        Map<String, Object> payload = buildLaborPerformedPayload(workorder.getId(), technicianId, timeEntryId);

        mockMvc.perform(post("/v1/workexec/labor-performed")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", timeEntryId)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.laborPerformedId").isNotEmpty())
                .andExpect(jsonPath("$.workorderId").value(workorder.getId().toString()))
                .andExpect(jsonPath("$.technicianId").value(technicianId.toString()))
                .andExpect(jsonPath("$.quantity").value(1.5))
                .andExpect(jsonPath("$.unit").value("HOURS"))
                .andExpect(jsonPath("$.sourceSystem").value("people"))
                .andExpect(jsonPath("$.sourceReferenceId").value(timeEntryId));

        assertThat(laborEntryRepository.findByWorkorderIdOrderByStartTimeDesc(workorder.getId())).hasSize(1)
                .extracting(WorkorderLaborEntry::getNotes)
                .singleElement()
                .asString()
                .contains("sourceSystem=people")
                .contains("sourceReferenceId=" + timeEntryId);
    }

    @Test
    @DisplayName("AC2: replay same Idempotency-Key returns 200 with replay header and no duplicate")
    void replayWithSameIdempotencyKeyReturnsOkAndNoDuplicate() throws Exception {
        Workorder workorder = seedWorkorderInProgress();
        UUID technicianId = UUID.randomUUID();
        String timeEntryId = "te-" + UUID.randomUUID();

        Map<String, Object> payload = buildLaborPerformedPayload(workorder.getId(), technicianId, timeEntryId);

        String firstResponseBody = mockMvc.perform(post("/v1/workexec/labor-performed")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", timeEntryId)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String firstLaborPerformedId = objectMapper.readTree(firstResponseBody)
                .path("laborPerformedId")
                .asText();

        mockMvc.perform(post("/v1/workexec/labor-performed")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", timeEntryId)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.laborPerformedId").value(firstLaborPerformedId))
                .andExpect(jsonPath("$.sourceSystem").value("people"))
                .andExpect(jsonPath("$.sourceReferenceId").value(timeEntryId));

        assertThat(laborEntryRepository.findByWorkorderIdOrderByStartTimeDesc(workorder.getId())).hasSize(1);
    }

    @Test
    @DisplayName("AC3: missing Idempotency-Key returns 400")
    void missingIdempotencyKeyReturnsBadRequest() throws Exception {
        Workorder workorder = seedWorkorderInProgress();
        UUID technicianId = UUID.randomUUID();

        Map<String, Object> payload = buildLaborPerformedPayload(workorder.getId(), technicianId,
                "te-" + UUID.randomUUID());

        mockMvc.perform(post("/v1/workexec/labor-performed")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest());

        assertThat(laborEntryRepository.findByWorkorderIdOrderByStartTimeDesc(workorder.getId())).isEmpty();
    }

    private Map<String, Object> buildLaborPerformedPayload(UUID workorderId, UUID technicianId, String timeEntryId) {
        return Map.of(
                "workorderId", workorderId.toString(),
                "technicianId", technicianId.toString(),
                "performedAt", "2026-02-16T15:00:00Z",
                "labor", Map.of("quantity", BigDecimal.valueOf(1.5), "unit", "HOURS"),
                "source", Map.of("system", "people", "sourceReferenceId", timeEntryId));
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
