package com.positivity.workorder.controller;

import tools.jackson.databind.ObjectMapper;
import com.positivity.workorder.internal.controller.WorkorderController;
import com.positivity.workorder.internal.dto.ApproveWorkorderRequest;
import com.positivity.workorder.internal.dto.CompleteWorkorderRequest;
import com.positivity.workorder.internal.dto.CreateWorkorderRequest;
import com.positivity.workorder.internal.dto.StartWorkorderRequest;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderStatus;
import com.positivity.workorder.service.WorkorderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(WorkorderController.class)
class WorkorderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private WorkorderService workorderService;

    @Test
    void testGetAllWorkorders_Success() throws Exception {
        // Given
        UUID workorderId1 = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        UUID workorderId2 = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
        UUID customerId = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");
        UUID estimateId = UUID.fromString("550e8400-e29b-41d4-a716-446655440003");

        Workorder workorder1 = Workorder.builder()
                .id(workorderId1)
                .estimateId(estimateId)
                .customerId(customerId)
                .status(WorkorderStatus.DRAFT)
                .build();

        Workorder workorder2 = Workorder.builder()
                .id(workorderId2)
                .estimateId(estimateId)
                .customerId(customerId)
                .status(WorkorderStatus.DRAFT)
                .build();

        when(workorderService.getAllWorkorders()).thenReturn(List.of(workorder1, workorder2));

        // When & Then
        mockMvc.perform(get("/v1/workorders")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id", is(workorderId1.toString())))
                .andExpect(jsonPath("$[0].customerId", is(customerId.toString())))
                .andExpect(jsonPath("$[0].estimateId", is(estimateId.toString())))
                .andExpect(jsonPath("$[0].status", is("DRAFT")))
                .andExpect(jsonPath("$[1].id", is(workorderId2.toString())));
    }

    @Test
    void testGetWorkorderById_Success() throws Exception {
        // Given
        UUID workorderId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        UUID customerId = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
        UUID estimateId = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");

        Workorder mockWorkorder = Workorder.builder()
                .id(workorderId)
                .estimateId(estimateId)
                .customerId(customerId)
                .status(WorkorderStatus.DRAFT)
                .build();

        when(workorderService.getWorkorderById(workorderId)).thenReturn(Optional.of(mockWorkorder));

        // When & Then
        mockMvc.perform(get("/v1/workorders/{workorderId}", workorderId)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id", is(workorderId.toString())))
                .andExpect(jsonPath("$.customerId", is(customerId.toString())))
                .andExpect(jsonPath("$.estimateId", is(estimateId.toString())))
                .andExpect(jsonPath("$.status", is("DRAFT")));
    }

    @Test
    void testGetWorkorderById_NotFound() throws Exception {
        // Given
        UUID workorderId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

        when(workorderService.getWorkorderById(workorderId)).thenReturn(Optional.empty());

        // When & Then
        mockMvc.perform(get("/v1/workorders/{workorderId}", workorderId)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void testCreateWorkorder_Success() throws Exception {
        // Given
        UUID workorderId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        UUID customerId = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
        UUID estimateId = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");

        CreateWorkorderRequest request = CreateWorkorderRequest.builder()
                .estimateId(estimateId)
                .customerId(customerId)
                .build();

        Workorder mockWorkorder = Workorder.builder()
                .id(workorderId)
                .estimateId(estimateId)
                .customerId(customerId)
                .status(WorkorderStatus.DRAFT)
                .build();

        when(workorderService.createWorkorder(estimateId, customerId)).thenReturn(mockWorkorder);

        // When & Then
        mockMvc.perform(post("/v1/workorders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id", is(workorderId.toString())))
                .andExpect(jsonPath("$.customerId", is(customerId.toString())))
                .andExpect(jsonPath("$.estimateId", is(estimateId.toString())))
                .andExpect(jsonPath("$.status", is("DRAFT")));
    }

    @Test
    void testCreateWorkorder_MissingEstimateId_ReturnsBadRequest() throws Exception {
        // Given
        UUID customerId = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");

        CreateWorkorderRequest request = CreateWorkorderRequest.builder()
                .customerId(customerId)
                .build();

        // When & Then
        mockMvc.perform(post("/v1/workorders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testCreateWorkorder_MissingCustomerId_ReturnsBadRequest() throws Exception {
        // Given
        UUID estimateId = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");

        CreateWorkorderRequest request = CreateWorkorderRequest.builder()
                .estimateId(estimateId)
                .build();

        // When & Then
        mockMvc.perform(post("/v1/workorders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testDeleteWorkorder_Success() throws Exception {
        // Given
        UUID workorderId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

        // When & Then
        mockMvc.perform(delete("/v1/workorders/{workorderId}", workorderId)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNoContent());
    }

    @Test
    void testStartWorkorder_Success() throws Exception {
        // Given
        UUID workorderId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        UUID userId = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
        UUID customerId = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");
        UUID estimateId = UUID.fromString("550e8400-e29b-41d4-a716-446655440003");

        Workorder mockWorkorder = Workorder.builder()
                .id(workorderId)
                .estimateId(estimateId)
                .customerId(customerId)
                .status(WorkorderStatus.DRAFT)
                .build();

        StartWorkorderRequest request = StartWorkorderRequest.builder()
                .userId(userId)
                .reason("Starting work")
                .build();

        when(workorderService.getWorkorderById(workorderId)).thenReturn(Optional.of(mockWorkorder));
        // startWorkorder returns void, so we don't need to mock the return value

        // When & Then
        mockMvc.perform(post("/v1/workorders/{workorderId}/start", workorderId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workorderId", is(workorderId.toString())))
                .andExpect(jsonPath("$.message", containsString("started successfully")));
    }

    @Test
    void testApproveWorkorder_Success() throws Exception {
        // Given
        UUID workorderId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        UUID customerId = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
        UUID estimateId = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");

        ApproveWorkorderRequest request = ApproveWorkorderRequest.builder()
                .customerId(customerId)
                .signatureData("base64encodedimage")
                .signatureMimeType("image/png")
                .signerName("John Doe")
                .notes("Approved by customer")
                .build();

        Workorder mockWorkorder = Workorder.builder()
                .id(workorderId)
                .estimateId(estimateId)
                .customerId(customerId)
                .status(WorkorderStatus.APPROVED)
                .approvedAt(Instant.now())
                .build();

        when(workorderService.approveWorkorder(
                eq(workorderId),
                eq(customerId),
                anyString(),
                anyString(),
                anyString(),
                anyString()))
                .thenReturn(mockWorkorder);

        // When & Then
        mockMvc.perform(post("/v1/workorders/{workorderId}/approval", workorderId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(workorderId.toString())))
                .andExpect(jsonPath("$.status", is("APPROVED")))
                .andExpect(jsonPath("$.customerId", is(customerId.toString())));
    }

    @Test
    void testCompleteWorkorder_Success() throws Exception {
        // Given
        UUID workorderId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        UUID userId = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
        UUID customerId = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");
        UUID estimateId = UUID.fromString("550e8400-e29b-41d4-a716-446655440003");
        Instant completionTime = Instant.now();

        CompleteWorkorderRequest request = CompleteWorkorderRequest.builder()
                .userId(userId)
                .completionNotes("Work completed successfully")
                .build();

        Workorder mockWorkorder = Workorder.builder()
                .id(workorderId)
                .estimateId(estimateId)
                .customerId(customerId)
                .status(WorkorderStatus.WORK_IN_PROGRESS)
                .build();

        Workorder completedWorkorder = Workorder.builder()
                .id(workorderId)
                .estimateId(estimateId)
                .customerId(customerId)
                .status(WorkorderStatus.COMPLETED)
                .completedAt(completionTime)
                .build();

        when(workorderService.getWorkorderById(workorderId))
                .thenReturn(Optional.of(mockWorkorder))
                .thenReturn(Optional.of(completedWorkorder));

        // When & Then
        mockMvc.perform(post("/v1/workorders/{workorderId}/complete", workorderId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workorderId", is(workorderId.toString())))
                .andExpect(jsonPath("$.currentStatus", is("COMPLETED")))
                .andExpect(jsonPath("$.message", containsString("completed successfully")));
    }
}
