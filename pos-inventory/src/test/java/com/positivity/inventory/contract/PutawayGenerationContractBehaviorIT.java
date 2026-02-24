package com.positivity.inventory.contract;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.positivity.inventory.internal.dto.putaway.GeneratePutawayTasksRequest;
import com.positivity.inventory.internal.dto.putaway.PutawayTaskResponse;
import com.positivity.inventory.service.PutawayGenerationService;
import com.positivity.inventory.service.contract.BaseContractIntegrationTest;

import tools.jackson.databind.ObjectMapper;

/**
 * Contract behavior integration tests for the Putaway Generation API.
 *
 * <p>
 * Verifies task generation from staging, fallback routing, manual-selection
 * status, list retrieval, and task claiming lifecycle per ADR-0011, ADR-0014,
 * ADR-0017, and ADR-0023.
 *
 * <p>
 * Tests are intentionally RED: the PutawayController does not yet exist,
 * so all requests will receive 404 instead of the expected status codes.
 *
 * Issue: #32
 */
@DisplayName("Putaway Generation Contract Behavior")
class PutawayGenerationContractBehaviorIT extends BaseContractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PutawayGenerationService putawayGenerationService;

    // ─── AC1: Successful task generation with product/category rule ───────────

    /**
     * Verifies that generating putaway tasks for a valid sourceReceiptId
     * returns 201 with a list of tasks each having status UNASSIGNED and a
     * non-null suggestedDestinationLocationId.
     *
     * Issue: #32
     */
    @Test
    @DisplayName("AC1: generate putaway tasks for valid sourceReceiptId returns 201 with UNASSIGNED tasks")
    void generatePutawayTasks_validReceiptId_returns201WithUnassignedTasks() throws Exception {
        // Issue #32: mock service to return a task with product/category rule match
        String receiptId = UUID.randomUUID().toString();
        String destinationId = UUID.randomUUID().toString();

        PutawayTaskResponse task = PutawayTaskResponse.builder()
                .taskId(UUID.randomUUID().toString())
                .sourceReceiptId(receiptId)
                .productId(UUID.randomUUID().toString())
                .quantity(10)
                .suggestedDestinationLocationId(destinationId)
                .status("UNASSIGNED")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(putawayGenerationService.generateTasksForReceipt(any(GeneratePutawayTasksRequest.class)))
                .thenReturn(List.of(task));

        String requestBody = objectMapper.writeValueAsString(
                objectMapper.createObjectNode().put("sourceReceiptId", receiptId));

        mockMvc.perform(withGatewayAuth(post("/api/v1/inventory/putaway/tasks/generate"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].status").value("UNASSIGNED"))
                .andExpect(jsonPath("$[0].suggestedDestinationLocationId").isNotEmpty());
    }

    // ─── AC2: No matching specific rule uses defaults/fallback ────────────────

    /**
     * Verifies that when no specific product/category rule matches, a task is
     * still generated using system fallback with a non-null
     * suggestedDestinationLocationId.
     *
     * Issue: #32
     */
    @Test
    @DisplayName("AC2: no matching rule generates task with system-fallback suggestedDestinationLocationId")
    void generatePutawayTasks_noMatchingRule_returns201WithFallbackDestination() throws Exception {
        // Issue #32: even with no specific rule, fallback destination must be non-null
        String receiptId = UUID.randomUUID().toString();
        String fallbackDestinationId = UUID.randomUUID().toString();

        PutawayTaskResponse task = PutawayTaskResponse.builder()
                .taskId(UUID.randomUUID().toString())
                .sourceReceiptId(receiptId)
                .productId(UUID.randomUUID().toString())
                .quantity(5)
                .suggestedDestinationLocationId(fallbackDestinationId)
                .status("UNASSIGNED")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(putawayGenerationService.generateTasksForReceipt(any(GeneratePutawayTasksRequest.class)))
                .thenReturn(List.of(task));

        String requestBody = objectMapper.writeValueAsString(
                objectMapper.createObjectNode().put("sourceReceiptId", receiptId));

        mockMvc.perform(withGatewayAuth(post("/api/v1/inventory/putaway/tasks/generate"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].suggestedDestinationLocationId").isNotEmpty())
                .andExpect(jsonPath("$[0].status").value("UNASSIGNED"));
    }

    // ─── AC3: Destination unavailable triggers auto-fallback ─────────────────

    /**
     * Verifies that when the originally suggested destination is unavailable,
     * the task records originalSuggestedLocationId, finalSuggestedLocationId
     * (different from original), and a fallbackReason.
     *
     * Issue: #32
     */
    @Test
    @DisplayName("AC3: unavailable destination triggers auto-fallback with originalSuggestedLocationId and fallbackReason")
    void generatePutawayTasks_destinationUnavailable_returns201WithFallbackFields() throws Exception {
        // Issue #32: auto-fallback path must populate originalSuggestedLocationId,
        // finalSuggestedLocationId (different), and fallbackReason
        String receiptId = UUID.randomUUID().toString();
        String originalLocationId = UUID.randomUUID().toString();
        String finalLocationId = UUID.randomUUID().toString();

        PutawayTaskResponse task = PutawayTaskResponse.builder()
                .taskId(UUID.randomUUID().toString())
                .sourceReceiptId(receiptId)
                .productId(UUID.randomUUID().toString())
                .quantity(8)
                .suggestedDestinationLocationId(finalLocationId)
                .originalSuggestedLocationId(originalLocationId)
                .finalSuggestedLocationId(finalLocationId)
                .fallbackReason("ORIGINAL_LOCATION_FULL")
                .status("UNASSIGNED")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(putawayGenerationService.generateTasksForReceipt(any(GeneratePutawayTasksRequest.class)))
                .thenReturn(List.of(task));

        String requestBody = objectMapper.writeValueAsString(
                objectMapper.createObjectNode().put("sourceReceiptId", receiptId));

        mockMvc.perform(withGatewayAuth(post("/api/v1/inventory/putaway/tasks/generate"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].originalSuggestedLocationId").value(originalLocationId))
                .andExpect(jsonPath("$[0].finalSuggestedLocationId").value(finalLocationId))
                .andExpect(jsonPath("$[0].fallbackReason").isNotEmpty());
    }

    // ─── AC4: No valid location requires manual selection ────────────────────

    /**
     * Verifies that when no valid destination location is found, the generated
     * task has status REQUIRES_LOCATION_SELECTION.
     *
     * Issue: #32
     */
    @Test
    @DisplayName("AC4: no valid location found produces task with REQUIRES_LOCATION_SELECTION status")
    void generatePutawayTasks_noValidLocation_returns201WithRequiresLocationSelection() throws Exception {
        // Issue #32: when no valid location exists, status must be
        // REQUIRES_LOCATION_SELECTION
        String receiptId = UUID.randomUUID().toString();

        PutawayTaskResponse task = PutawayTaskResponse.builder()
                .taskId(UUID.randomUUID().toString())
                .sourceReceiptId(receiptId)
                .productId(UUID.randomUUID().toString())
                .quantity(3)
                .status("REQUIRES_LOCATION_SELECTION")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(putawayGenerationService.generateTasksForReceipt(any(GeneratePutawayTasksRequest.class)))
                .thenReturn(List.of(task));

        String requestBody = objectMapper.writeValueAsString(
                objectMapper.createObjectNode().put("sourceReceiptId", receiptId));

        mockMvc.perform(withGatewayAuth(post("/api/v1/inventory/putaway/tasks/generate"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].status").value("REQUIRES_LOCATION_SELECTION"));
    }

    // ─── AC5: Get available tasks ─────────────────────────────────────────────

    /**
     * Verifies that GET /api/v1/inventory/putaway/tasks returns 200 with a
     * list of available putaway tasks.
     *
     * Issue: #32
     */
    @Test
    @DisplayName("AC5: get available putaway tasks returns 200 with task list")
    void getAvailablePutawayTasks_returns200WithList() throws Exception {
        // Issue #32: available tasks endpoint must return 200 with list
        PutawayTaskResponse task1 = PutawayTaskResponse.builder()
                .taskId(UUID.randomUUID().toString())
                .sourceReceiptId(UUID.randomUUID().toString())
                .status("UNASSIGNED")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        PutawayTaskResponse task2 = PutawayTaskResponse.builder()
                .taskId(UUID.randomUUID().toString())
                .sourceReceiptId(UUID.randomUUID().toString())
                .status("UNASSIGNED")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(putawayGenerationService.getAvailableTasks()).thenReturn(List.of(task1, task2));

        mockMvc.perform(withGatewayAuth(get("/api/v1/inventory/putaway/tasks")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2));
    }

    // ─── AC6: Claim a task ────────────────────────────────────────────────────

    /**
     * Verifies that claiming a putaway task returns 200 with the task status
     * updated to ASSIGNED.
     *
     * Issue: #32
     */
    @Test
    @DisplayName("AC6: claim putaway task returns 200 with status ASSIGNED")
    void claimPutawayTask_validTaskId_returns200WithAssignedStatus() throws Exception {
        // Issue #32: claiming a task must return ASSIGNED status
        String taskId = UUID.randomUUID().toString();
        String userId = "warehouse-user-1";

        PutawayTaskResponse claimedTask = PutawayTaskResponse.builder()
                .taskId(taskId)
                .sourceReceiptId(UUID.randomUUID().toString())
                .status("ASSIGNED")
                .assigneeId(userId)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(putawayGenerationService.claimTask(eq(taskId), any(String.class)))
                .thenReturn(claimedTask);

        mockMvc.perform(withGatewayAuth(post("/api/v1/inventory/putaway/tasks/{taskId}/claim", taskId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(taskId))
                .andExpect(jsonPath("$.status").value("ASSIGNED"));
    }

    // ─── AC7: Missing permission returns 403 ──────────────────────────────────

    /**
     * Verifies that a request without the required inventory authority header
     * returns 403 Forbidden per ADR-0011 and ADR-0014.
     *
     * Issue: #32
     */
    @Test
    @DisplayName("AC7: missing inventory authority in X-Authorities header returns 403")
    void generatePutawayTasks_missingAuthority_returns403() throws Exception {
        // Issue #32: ADR-0011/ADR-0014 require authority check; absent header → 403
        String requestBody = objectMapper.writeValueAsString(
                objectMapper.createObjectNode().put("sourceReceiptId", UUID.randomUUID().toString()));

        mockMvc.perform(post("/api/v1/inventory/putaway/tasks/generate")
                .header("X-User", "unauthorized-user")
                // Deliberately omit X-Authorities to trigger 403
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isForbidden());
    }
}
