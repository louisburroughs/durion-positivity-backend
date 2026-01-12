package com.positivity.workorder.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.workorder.dto.CreateEstimateRequest;
import com.positivity.workorder.entity.Estimate;
import com.positivity.workorder.service.EstimateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(EstimateController.class)
class EstimateControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private EstimateService estimateService;

    private CreateEstimateRequest testRequest;
    private Estimate testEstimate;
    private Long testUserId;

    @BeforeEach
    void setUp() {
        testUserId = 789L;

        testRequest = CreateEstimateRequest.builder()
                .customerId(123L)
                .vehicleId(456L)
                .shopId(1L)
                .build();

        testEstimate = Estimate.builder()
                .id(1L)
                .estimateNumber("EST-10021")
                .customerId(123L)
                .vehicleId(456L)
                .shopId(1L)
                .status(Estimate.EstimateStatus.DRAFT)
                .createdById(testUserId)
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Test
    void createDraftEstimate_Success() throws Exception {
        // Arrange
        when(estimateService.createDraftEstimate(any(CreateEstimateRequest.class), eq(testUserId)))
                .thenReturn(testEstimate);

        // Act & Assert
        mockMvc.perform(post("/api/estimates/draft")
                        .header("X-User-Id", testUserId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(testRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estimateId").value(1))
                .andExpect(jsonPath("$.estimateNumber").value("EST-10021"))
                .andExpect(jsonPath("$.customerId").value(123))
                .andExpect(jsonPath("$.vehicleId").value(456))
                .andExpect(jsonPath("$.shopId").value(1))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.createdById").value(testUserId));
    }

    @Test
    void createDraftEstimate_CustomerNotFound() throws Exception {
        // Arrange
        when(estimateService.createDraftEstimate(any(CreateEstimateRequest.class), eq(testUserId)))
                .thenThrow(new IllegalArgumentException("Customer with ID 123 not found"));

        // Act & Assert
        mockMvc.perform(post("/api/estimates/draft")
                        .header("X-User-Id", testUserId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(testRequest)))
                .andExpect(status().isNotFound());
    }

    @Test
    void createDraftEstimate_VehicleNotFound() throws Exception {
        // Arrange
        when(estimateService.createDraftEstimate(any(CreateEstimateRequest.class), eq(testUserId)))
                .thenThrow(new IllegalArgumentException("Vehicle with ID 456 not found"));

        // Act & Assert
        mockMvc.perform(post("/api/estimates/draft")
                        .header("X-User-Id", testUserId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(testRequest)))
                .andExpect(status().isNotFound());
    }

    @Test
    void createDraftEstimate_MissingCustomerId() throws Exception {
        // Arrange
        CreateEstimateRequest invalidRequest = CreateEstimateRequest.builder()
                .vehicleId(456L)
                .shopId(1L)
                .build();

        // Act & Assert
        mockMvc.perform(post("/api/estimates/draft")
                        .header("X-User-Id", testUserId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createDraftEstimate_MissingVehicleId() throws Exception {
        // Arrange
        CreateEstimateRequest invalidRequest = CreateEstimateRequest.builder()
                .customerId(123L)
                .shopId(1L)
                .build();

        // Act & Assert
        mockMvc.perform(post("/api/estimates/draft")
                        .header("X-User-Id", testUserId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createDraftEstimate_MissingUserIdHeader() throws Exception {
        // Act & Assert
        mockMvc.perform(post("/api/estimates/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(testRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createDraftEstimate_InternalServerError() throws Exception {
        // Arrange
        when(estimateService.createDraftEstimate(any(CreateEstimateRequest.class), eq(testUserId)))
                .thenThrow(new RuntimeException("Database connection failed"));

        // Act & Assert
        mockMvc.perform(post("/api/estimates/draft")
                        .header("X-User-Id", testUserId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(testRequest)))
                .andExpect(status().isInternalServerError());
    }
}
