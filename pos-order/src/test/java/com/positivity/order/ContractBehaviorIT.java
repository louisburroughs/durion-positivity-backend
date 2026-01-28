package com.positivity.order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Order Management Backend Contract Behavioral Tests")
class ContractBehaviorIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ========== HAPPY PATH TESTS ==========
    @Test
    @DisplayName("CP-001: Successfully create order with valid items and totals")
    void testCreateOrder_HappyPath() throws Exception {
        String payload = createOrderPayload("CUST-001", "100.00", "10.00", "110.00");
        mockMvc.perform(post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-001"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.customerId").value("CUST-001"))
                .andExpect(jsonPath("$.total").value(110.00));
    }

    @Test
    @DisplayName("CP-002: Successfully retrieve order by ID")
    void testGetOrder_HappyPath() throws Exception {
        String payload = createOrderPayload("CUST-002", "250.50", "25.05", "275.55");
        MvcResult createResult = mockMvc.perform(post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-002"))
                .andExpect(status().isCreated())
                .andReturn();

        String orderId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(get("/api/v1/orders/{id}", orderId)
                .header("X-Correlation-Id", "test-002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId))
                .andExpect(jsonPath("$.total").value(275.55));
    }

    // ========== VALIDATION ERROR TESTS ==========
    @Test
    @DisplayName("VE-001: Reject order with missing customerId")
    void testCreateOrder_MissingCustomerId() throws Exception {
        String invalidPayload = "{\"subtotal\":\"100.00\",\"taxAmount\":\"10.00\",\"total\":\"110.00\"}";
        mockMvc.perform(post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidPayload)
                .header("X-Correlation-Id", "test-ve-001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("VE-002: Reject order with totals mismatch")
    void testCreateOrder_TotalsMismatch() throws Exception {
        String invalidPayload = createOrderPayload("CUST-003", "100.00", "10.00", "99.99");
        mockMvc.perform(post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidPayload)
                .header("X-Correlation-Id", "test-ve-002"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    @DisplayName("VE-003: Reject order with negative subtotal")
    void testCreateOrder_NegativeSubtotal() throws Exception {
        String invalidPayload = createOrderPayload("CUST-004", "-50.00", "0.00", "-50.00");
        mockMvc.perform(post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidPayload)
                .header("X-Correlation-Id", "test-ve-003"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ========== IDEMPOTENCY TEST ==========
    @Test
    @DisplayName("ID-001: Idempotent create with same Idempotency-Key returns same resource")
    void testCreateOrder_Idempotent() throws Exception {
        String idempotencyKey = "idem-order-" + System.currentTimeMillis();
        String payload = createOrderPayload("CUST-005", "500.00", "50.00", "550.00");

        MvcResult result1 = mockMvc.perform(post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-id-001")
                .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isCreated())
                .andReturn();

        String id1 = objectMapper.readTree(result1.getResponse().getContentAsString()).get("id").asText();

        MvcResult result2 = mockMvc.perform(post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-id-001")
                .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isCreated())
                .andReturn();

        String id2 = objectMapper.readTree(result2.getResponse().getContentAsString()).get("id").asText();
        assert id1.equals(id2) : "Idempotent requests should return same order ID";
    }

    // ========== CONCURRENCY INVARIANT TESTS ==========
    @Test
    @DisplayName("CC-001: Optimistic locking prevents concurrent updates with stale version")
    void testUpdateOrder_OptimisticLockingConflict() throws Exception {
        String payload = createOrderPayload("CUST-006", "300.00", "30.00", "330.00");
        MvcResult createResult = mockMvc.perform(post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-cc-001"))
                .andExpect(status().isCreated())
                .andReturn();

        String orderId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();
        String updatePayload = "{\"subtotal\":\"350.00\",\"taxAmount\":\"35.00\",\"total\":\"385.00\",\"version\":0}";

        mockMvc.perform(patch("/api/v1/orders/{id}", orderId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(updatePayload)
                .header("X-Correlation-Id", "test-cc-001"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    @DisplayName("CC-002: Order state transition respects business rules")
    void testUpdateOrder_ValidStateTransition() throws Exception {
        String payload = createOrderPayload("CUST-007", "400.00", "40.00", "440.00");
        MvcResult createResult = mockMvc.perform(post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-cc-002"))
                .andExpect(status().isCreated())
                .andReturn();

        String orderId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();
        String confirmPayload = "{\"status\":\"CONFIRMED\"}";

        mockMvc.perform(patch("/api/v1/orders/{id}/status", orderId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(confirmPayload)
                .header("X-Correlation-Id", "test-cc-002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    // ========== FIELD FORMAT VALIDATION TESTS ==========
    @Test
    @DisplayName("FF-001: ISO 8601 timestamp format validation for createdAt")
    void testOrder_TimestampFormat() throws Exception {
        String payload = createOrderPayload("CUST-008", "150.00", "15.00", "165.00");
        MvcResult result = mockMvc.perform(post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-ff-001"))
                .andExpect(status().isCreated())
                .andReturn();

        String createdAt = objectMapper.readTree(result.getResponse().getContentAsString()).get("createdAt").asText();
        assert createdAt.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?Z")
                : "createdAt must be ISO 8601 UTC format";
    }

    @Test
    @DisplayName("FF-002: Valid order status enum values")
    void testOrder_ValidStatusEnums() throws Exception {
        String payload = createOrderPayload("CUST-009", "200.00", "20.00", "220.00");
        MvcResult result = mockMvc.perform(post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-ff-002"))
                .andExpect(status().isCreated())
                .andReturn();

        String status = objectMapper.readTree(result.getResponse().getContentAsString()).get("status").asText();
        assert status.matches("DRAFT|CONFIRMED|SHIPPED|CANCELLED|COMPLETED") : "Order status must be valid enum value";
    }

    private String createOrderPayload(String customerId, String subtotal, String taxAmount, String total) {
        return String.format(
                "{\"customerId\":\"%s\",\"subtotal\":%s,\"taxAmount\":%s,\"total\":%s}",
                customerId, subtotal, taxAmount, total);
    }
}
