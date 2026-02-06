package com.positivity.workorder.controller;

import tools.jackson.databind.ObjectMapper;
import com.positivity.workorder.internal.controller.ApprovalConfigurationController;
import com.positivity.workorder.internal.dto.ApprovalConfigurationRequest;
import com.positivity.workorder.internal.dto.ApprovalConfigurationResponse;
import com.positivity.workorder.service.ApprovalConfigurationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ApprovalConfigurationController.class)
@ActiveProfiles("test")
class ApprovalConfigurationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ApprovalConfigurationService approvalConfigurationService;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID testConfigId;
    private ApprovalConfigurationResponse testResponse;
    private ApprovalConfigurationRequest testRequest;

    @BeforeEach
    void setUp() {
        testConfigId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

        testRequest = ApprovalConfigurationRequest.builder()
                .locationId(UUID.fromString("550e8400-e29b-41d4-a716-446655440001"))
                .customerId(UUID.fromString("550e8400-e29b-41d4-a716-446655440002"))
                .approvalMethod("CLICK_CONFIRM")
                .declineExpiryDays(30)
                .requireSignature(false)
                .priority(1)
                .build();

        testResponse = ApprovalConfigurationResponse.builder()
                .id(testConfigId)
                .locationId(UUID.fromString("550e8400-e29b-41d4-a716-446655440001"))
                .customerId(UUID.fromString("550e8400-e29b-41d4-a716-446655440002"))
                .approvalMethod("CLICK_CONFIRM")
                .declineExpiryDays(30)
                .requireSignature(false)
                .priority(1)
                .build();
    }

    @Test
    void testGetAllConfigurations_Success() throws Exception {
        List<ApprovalConfigurationResponse> configurations = List.of(testResponse);
        when(approvalConfigurationService.getAllConfigurations()).thenReturn(configurations);

        mockMvc.perform(get("/v1/workexec"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].id").value(testConfigId.toString()))
                .andExpect(jsonPath("$[0].approvalMethod").value("CLICK_CONFIRM"))
                .andExpect(jsonPath("$[0].locationId").value("550e8400-e29b-41d4-a716-446655440001"));
    }

    @Test
    void testGetConfigurationById_Found() throws Exception {
        when(approvalConfigurationService.getConfigurationById(testConfigId))
                .thenReturn(Optional.of(testResponse));

        mockMvc.perform(get("/v1/workexec/approvalConfigurations/{approvalId}", testConfigId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(testConfigId.toString()))
                .andExpect(jsonPath("$.approvalMethod").value("CLICK_CONFIRM"));
    }

    @Test
    void testGetConfigurationById_NotFound() throws Exception {
        when(approvalConfigurationService.getConfigurationById(testConfigId))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/v1/workexec/approvalConfigurations/{approvalId}", testConfigId))
                .andExpect(status().isNotFound());
    }

    @Test
    void testGetApplicableConfiguration_Found() throws Exception {
        UUID testLocationId = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
        UUID testCustomerId = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");
        when(approvalConfigurationService.getApplicableConfiguration(testLocationId, testCustomerId))
                .thenReturn(Optional.of(testResponse));

        mockMvc.perform(get("/v1/workexec/approvalConfigurations/applicable")
                .param("locationId", "550e8400-e29b-41d4-a716-446655440001")
                .param("customerId", "550e8400-e29b-41d4-a716-446655440002"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(testConfigId.toString()));
    }

    @Test
    void testGetApplicableConfiguration_NotFound() throws Exception {
        UUID testLocationId = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
        UUID testCustomerId = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");
        when(approvalConfigurationService.getApplicableConfiguration(testLocationId, testCustomerId))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/v1/workexec/approvalConfigurations/applicable")
                .param("locationId", "550e8400-e29b-41d4-a716-446655440001")
                .param("customerId", "550e8400-e29b-41d4-a716-446655440002"))
                .andExpect(status().isNotFound());
    }

    @Test
    void testCreateConfiguration_Success() throws Exception {
        when(approvalConfigurationService.createConfiguration(any(ApprovalConfigurationRequest.class)))
                .thenReturn(testResponse);

        mockMvc.perform(post("/v1/workexec/approvalConfigurations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(testRequest)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(testConfigId.toString()))
                .andExpect(jsonPath("$.approvalMethod").value("CLICK_CONFIRM"));
    }

    @Test
    void testUpdateConfiguration_Success() throws Exception {
        when(approvalConfigurationService.updateConfiguration(eq(testConfigId),
                any(ApprovalConfigurationRequest.class)))
                .thenReturn(testResponse);

        mockMvc.perform(put("/v1/workexec/approvalConfigurations/{approvalId}", testConfigId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(testRequest)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(testConfigId.toString()));
    }

    @Test
    void testUpdateConfiguration_NotFound() throws Exception {
        when(approvalConfigurationService.updateConfiguration(eq(testConfigId),
                any(ApprovalConfigurationRequest.class)))
                .thenThrow(new IllegalArgumentException("Configuration not found"));

        mockMvc.perform(put("/v1/workexec/approvalConfigurations/{approvalId}", testConfigId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(testRequest)))
                .andExpect(status().isNotFound());
    }

    @Test
    void testDeleteConfiguration_Success() throws Exception {
        doNothing().when(approvalConfigurationService).deleteConfiguration(testConfigId);

        mockMvc.perform(delete("/v1/workexec/approvalConfigurations/{approvalId}", testConfigId))
                .andExpect(status().isNoContent());
    }
}
