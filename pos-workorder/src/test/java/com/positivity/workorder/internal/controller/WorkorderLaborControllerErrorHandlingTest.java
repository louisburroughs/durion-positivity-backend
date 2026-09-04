package com.positivity.workorder.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.workorder.internal.dto.AdjustLaborRequest;
import com.positivity.workorder.internal.exception.LaborEntryNotFoundException;
import com.positivity.workorder.internal.service.WorkorderLaborService;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller-slice regression coverage for issue #1728 (PR review finding on
 * {@link WorkorderLaborController#adjustLaborHours}): a missing labor entry must answer with the
 * module's {@code ApiError} envelope and {@code X-Correlation-Id} header, not a bodyless {@code
 * 404} built directly in the controller. Modeled on {@code WorkorderControllerErrorHandlingTest}.
 */
@WebMvcTest(WorkorderLaborController.class)
class WorkorderLaborControllerErrorHandlingTest {

    private static final UUID WORKORDER_ID = UUID.fromString("019200aa-0000-7000-8000-000000000301");
    private static final UUID ENTRY_ID = UUID.fromString("019200aa-0000-7000-8000-000000000302");
    private static final String URL = "/v1/workorders/{workorderId}/labor/{entryId}/adjust";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private WorkorderLaborService laborService;

    @Test
    @WithMockUser(authorities = "workorder:labor:add")
    void adjustLaborHoursForAMissingEntryAnswersEnvelopedNotFound() throws Exception {
        doThrow(new LaborEntryNotFoundException(ENTRY_ID))
                .when(laborService)
                .adjustLaborHours(any(), any(), anyString(), anyString(), any());

        AdjustLaborRequest request = AdjustLaborRequest.builder()
                .hoursWorked(new BigDecimal("2.5"))
                .adjustmentReason("Corrected after timesheet review")
                .build();

        mockMvc.perform(put(URL, WORKORDER_ID, ENTRY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(LaborEntryNotFoundException.ERROR_CODE))
                .andExpect(jsonPath("$.message").value("Labor entry not found: " + ENTRY_ID))
                .andExpect(jsonPath("$.correlationId").exists())
                .andExpect(header().exists("X-Correlation-Id"));
    }

    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class SliceTestConfig {}
}
