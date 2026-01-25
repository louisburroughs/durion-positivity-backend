package com.positivity.workorder.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.workorder.dto.CreateEstimateRequest;
import com.positivity.workorder.dto.CreateEstimateResponse;
import com.positivity.workorder.entity.Estimate;
import com.positivity.workorder.entity.EstimateStatus;
import com.positivity.workorder.service.EstimateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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

        @Test
        void testCreateEstimate_Success() throws Exception {
                // Given
                CreateEstimateRequest request = CreateEstimateRequest.builder()
                                .customerId(1L)
                                .vehicleId(2L)
                                .build();

                Estimate mockEstimate = Estimate.builder()
                                .id(1L)
                                .estimateNumber("EST-2024-1000")
                                .customerId(1L)
                                .vehicleId(2L)
                                .locationId(1L)
                                .currencyUomId("USD")
                                .taxRegionId(1L)
                                .status(EstimateStatus.DRAFT)
                                .createdByUserId(100L)
                                .createdAt(LocalDateTime.now())
                                .build();

                when(estimateService.createEstimate(any(CreateEstimateRequest.class), anyLong()))
                                .thenReturn(mockEstimate);

                // When & Then
                mockMvc.perform(post("/api/estimates/create")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("X-User-Id", "100")
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isOk())
                                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                                .andExpect(jsonPath("$.estimateId", is(1)))
                                .andExpect(jsonPath("$.estimateNumber", is("EST-2024-1000")))
                                .andExpect(jsonPath("$.status", is("DRAFT")))
                                .andExpect(jsonPath("$.customerId", is(1)))
                                .andExpect(jsonPath("$.vehicleId", is(2)))
                                .andExpect(jsonPath("$.locationId", is(1)))
                                .andExpect(jsonPath("$.currencyUomId", is("USD")))
                                .andExpect(jsonPath("$.createdByUserId", is(100)));
        }

        @Test
        void testCreateEstimate_MissingCustomerId_ReturnsBadRequest() throws Exception {
                // Given
                CreateEstimateRequest request = CreateEstimateRequest.builder()
                                .vehicleId(2L)
                                .build();

                when(estimateService.createEstimate(any(CreateEstimateRequest.class), anyLong()))
                                .thenThrow(new IllegalArgumentException("customerId is required"));

                // When & Then
                mockMvc.perform(post("/api/estimates/create")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("X-User-Id", "100")
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isBadRequest());
        }

        @Test
        void testCreateEstimate_MissingVehicleId_ReturnsBadRequest() throws Exception {
                // Given
                CreateEstimateRequest request = CreateEstimateRequest.builder()
                                .customerId(1L)
                                .build();

                when(estimateService.createEstimate(any(CreateEstimateRequest.class), anyLong()))
                                .thenThrow(new IllegalArgumentException("vehicleId is required"));

                // When & Then
                mockMvc.perform(post("/api/estimates/create")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("X-User-Id", "100")
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isBadRequest());
        }

        @Test
        void testCreateEstimate_UnexpectedException_ReturnsInternalServerError() throws Exception {
                // Given
                CreateEstimateRequest request = CreateEstimateRequest.builder()
                                .customerId(1L)
                                .vehicleId(2L)
                                .build();

                when(estimateService.createEstimate(any(CreateEstimateRequest.class), anyLong()))
                                .thenThrow(new RuntimeException("Database connection failed"));

                // When & Then
                mockMvc.perform(post("/api/estimates/create")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("X-User-Id", "100")
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isInternalServerError())
                                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                                .andExpect(jsonPath("$.error", is("Internal Server Error")))
                                .andExpect(jsonPath("$.message", containsString("unexpected error")));
        }

        @Test
        void testCreateEstimate_WithoutUserId_UsesDefault() throws Exception {
                // Given
                CreateEstimateRequest request = CreateEstimateRequest.builder()
                                .customerId(1L)
                                .vehicleId(2L)
                                .build();

                Estimate mockEstimate = Estimate.builder()
                                .id(1L)
                                .estimateNumber("EST-2024-1000")
                                .customerId(1L)
                                .vehicleId(2L)
                                .locationId(1L)
                                .currencyUomId("USD")
                                .taxRegionId(1L)
                                .status(EstimateStatus.DRAFT)
                                .createdByUserId(1L) // Default userId
                                .createdAt(LocalDateTime.now())
                                .build();

                when(estimateService.createEstimate(any(CreateEstimateRequest.class), anyLong()))
                                .thenReturn(mockEstimate);

                // When & Then
                mockMvc.perform(post("/api/estimates/create")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isOk())
                                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                                .andExpect(jsonPath("$.estimateId", is(1)));
        }
}
