package com.positivity.inventory.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.internal.entity.CountEntry;
import com.positivity.inventory.internal.entity.CycleCountTask;
import com.positivity.inventory.internal.enums.TaskStatus;
import com.positivity.inventory.internal.repository.CountEntryRepository;
import com.positivity.inventory.internal.repository.CycleCountTaskRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Contract behavior integration tests for the Cycle Count execution endpoints.
 *
 * <p>
 * Verifies behavioral contracts for Story #27: Execute Cycle Count and Record
 * Variances.
 * Covers count submission, recount permission rules, recount limit enforcement,
 * and
 * input validation per ADR-0017 HTTP response code standards.
 *
 * Issue: #27
 */
@DisplayName("Cycle Count Contract Behavior")
class CycleCountContractBehaviorIT extends BaseContractIntegrationTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CycleCountTaskRepository cycleCountTaskRepository;

    @Autowired
    private CountEntryRepository countEntryRepository;

    @BeforeEach
    void setUp() {
        countEntryRepository.deleteAll();
        cycleCountTaskRepository.deleteAll();
    }

    // ─── AC-1: Initial count creates entry and computes variance ─────────────

    @Test
    @DisplayName("AC-27-1: submitting an initial count returns 200 OK with computed variance")
    void submitCount_assignedTask_returns200WithVariance() throws Exception {
        CycleCountTask task = cycleCountTaskRepository.save(CycleCountTask.builder()
                .binLocation("BIN-A1")
                .itemSku("SKU-001")
                .itemDescription("Test Item")
                .expectedQuantity(BigDecimal.valueOf(100))
                .auditorId("auditor-1")
                .status(TaskStatus.ASSIGNED)
                .countEntriesCount(0)
                .build());

        String body = objectMapper.writeValueAsString(new SubmitCountBody(task.getTaskId(), "auditor-1", 102));

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/cycleCount/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(task.getTaskId().toString()))
                .andExpect(jsonPath("$.actualQuantity").value(102))
                .andExpect(jsonPath("$.expectedQuantity").value(100))
                .andExpect(jsonPath("$.variance").value(2))
                .andExpect(jsonPath("$.recountSequenceNumber").value(0));
    }

    // ─── AC-2: Recount creates a new entry referencing prior ─────────────────

    @Test
    @DisplayName("AC-27-2: auditor recount on pending-review task creates entry with recountSequenceNumber=1")
    void submitRecount_countedPendingReview_auditorSelfPermission_returns200WithSequence1() throws Exception {
        CycleCountTask task = cycleCountTaskRepository.save(CycleCountTask.builder()
                .binLocation("BIN-B2")
                .itemSku("SKU-002")
                .itemDescription("Test Item 2")
                .expectedQuantity(BigDecimal.valueOf(50))
                .auditorId("auditor-2")
                .status(TaskStatus.COUNTED_PENDING_REVIEW)
                .countEntriesCount(0)
                .build());

        CountEntry existingEntry = countEntryRepository.save(CountEntry.builder()
                .cycleCountTask(task)
                .auditorId("auditor-2")
                .actualQuantity(BigDecimal.valueOf(48))
                .measuredQuantity(BigDecimal.valueOf(48))
                .expectedQuantity(BigDecimal.valueOf(50))
                .variance(BigDecimal.valueOf(-2))
                .recountSequenceNumber(0)
                .countedAt(Instant.now(TEST_CLOCK))
                .build());

        task.setLatestCountEntryId(existingEntry.getCountEntryId());
        task.setCountEntriesCount(1);
        cycleCountTaskRepository.save(task);

        String body = objectMapper.writeValueAsString(
                new SubmitRecountBody(task.getTaskId(), "auditor-2", 49, "TRIGGER_RECOUNT_SELF"));

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/cycleCount/recount")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(task.getTaskId().toString()))
                .andExpect(jsonPath("$.recountSequenceNumber").value(1))
                .andExpect(jsonPath("$.actualQuantity").value(49));
    }

    // ─── AC-3: Auditor cannot trigger second recount (403) ───────────────────

    @Test
    @DisplayName("AC-27-3: auditor TRIGGER_RECOUNT_SELF with 2 existing counts returns 403 Forbidden")
    void submitRecount_twoExistingCounts_auditorSelfPermission_returns403() throws Exception {
        CycleCountTask task = cycleCountTaskRepository.save(CycleCountTask.builder()
                .binLocation("BIN-C3")
                .itemSku("SKU-003")
                .itemDescription("Test Item 3")
                .expectedQuantity(BigDecimal.valueOf(75))
                .auditorId("auditor-3")
                .status(TaskStatus.COUNTED_PENDING_REVIEW)
                .countEntriesCount(0)
                .build());

        CountEntry first = countEntryRepository.save(CountEntry.builder()
                .cycleCountTask(task)
                .auditorId("auditor-3")
                .actualQuantity(BigDecimal.valueOf(70))
                .measuredQuantity(BigDecimal.valueOf(70))
                .expectedQuantity(BigDecimal.valueOf(75))
                .variance(BigDecimal.valueOf(-5))
                .recountSequenceNumber(0)
                .countedAt(Instant.now(TEST_CLOCK))
                .build());

        CountEntry second = countEntryRepository.save(CountEntry.builder()
                .cycleCountTask(task)
                .auditorId("auditor-3")
                .actualQuantity(BigDecimal.valueOf(72))
                .measuredQuantity(BigDecimal.valueOf(72))
                .expectedQuantity(BigDecimal.valueOf(75))
                .variance(BigDecimal.valueOf(-3))
                .recountSequenceNumber(1)
                .recountOfCountEntry(first)
                .countedAt(Instant.now(TEST_CLOCK))
                .build());

        task.setLatestCountEntryId(second.getCountEntryId());
        task.setCountEntriesCount(2);
        cycleCountTaskRepository.save(task);

        String body = objectMapper.writeValueAsString(
                new SubmitRecountBody(task.getTaskId(), "auditor-3", 74, "TRIGGER_RECOUNT_SELF"));

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/cycleCount/recount")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)))
                .andExpect(status().isForbidden());
    }

    // ─── AC-4: Manager can trigger recounts beyond auditor allowance ──────────

    @Test
    @DisplayName("AC-27-4: manager TRIGGER_RECOUNT_ANY with 2 existing counts returns 200 OK")
    void submitRecount_twoExistingCounts_managerPermission_returns200() throws Exception {
        CycleCountTask task = cycleCountTaskRepository.save(CycleCountTask.builder()
                .binLocation("BIN-D4")
                .itemSku("SKU-004")
                .itemDescription("Test Item 4")
                .expectedQuantity(BigDecimal.valueOf(200))
                .auditorId("auditor-4")
                .status(TaskStatus.COUNTED_PENDING_REVIEW)
                .countEntriesCount(0)
                .build());

        CountEntry first = countEntryRepository.save(CountEntry.builder()
                .cycleCountTask(task)
                .auditorId("auditor-4")
                .actualQuantity(BigDecimal.valueOf(195))
                .measuredQuantity(BigDecimal.valueOf(195))
                .expectedQuantity(BigDecimal.valueOf(200))
                .variance(BigDecimal.valueOf(-5))
                .recountSequenceNumber(0)
                .countedAt(Instant.now(TEST_CLOCK))
                .build());

        CountEntry second = countEntryRepository.save(CountEntry.builder()
                .cycleCountTask(task)
                .auditorId("auditor-4")
                .actualQuantity(BigDecimal.valueOf(197))
                .measuredQuantity(BigDecimal.valueOf(197))
                .expectedQuantity(BigDecimal.valueOf(200))
                .variance(BigDecimal.valueOf(-3))
                .recountSequenceNumber(1)
                .recountOfCountEntry(first)
                .countedAt(Instant.now(TEST_CLOCK))
                .build());

        task.setLatestCountEntryId(second.getCountEntryId());
        task.setCountEntriesCount(2);
        cycleCountTaskRepository.save(task);

        String body = objectMapper.writeValueAsString(
                new SubmitRecountBody(task.getTaskId(), "manager-1", 199, "TRIGGER_RECOUNT_ANY"));

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/cycleCount/recount")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recountSequenceNumber").value(2))
                .andExpect(jsonPath("$.actualQuantity").value(199));
    }

    // ─── AC-5: Cap enforcement — 3 total counts reached → 400 ───────────────

    @Test
    @DisplayName("AC-27-5: recount attempt with 3 existing counts returns 400 Bad Request")
    void submitRecount_threeExistingCounts_returns400RecountLimitExceeded() throws Exception {
        CycleCountTask task = cycleCountTaskRepository.save(CycleCountTask.builder()
                .binLocation("BIN-E5")
                .itemSku("SKU-005")
                .itemDescription("Test Item 5")
                .expectedQuantity(BigDecimal.valueOf(30))
                .auditorId("auditor-5")
                .status(TaskStatus.COUNTED_PENDING_REVIEW)
                .countEntriesCount(0)
                .build());

        CountEntry first = countEntryRepository.save(CountEntry.builder()
                .cycleCountTask(task)
                .auditorId("auditor-5")
                .actualQuantity(BigDecimal.valueOf(28))
                .measuredQuantity(BigDecimal.valueOf(28))
                .expectedQuantity(BigDecimal.valueOf(30))
                .variance(BigDecimal.valueOf(-2))
                .recountSequenceNumber(0)
                .countedAt(Instant.now(TEST_CLOCK))
                .build());

        CountEntry second = countEntryRepository.save(CountEntry.builder()
                .cycleCountTask(task)
                .auditorId("auditor-5")
                .actualQuantity(BigDecimal.valueOf(29))
                .measuredQuantity(BigDecimal.valueOf(29))
                .expectedQuantity(BigDecimal.valueOf(30))
                .variance(BigDecimal.valueOf(-1))
                .recountSequenceNumber(1)
                .recountOfCountEntry(first)
                .countedAt(Instant.now(TEST_CLOCK))
                .build());

        CountEntry third = countEntryRepository.save(CountEntry.builder()
                .cycleCountTask(task)
                .auditorId("manager-2")
                .actualQuantity(BigDecimal.valueOf(30))
                .measuredQuantity(BigDecimal.valueOf(30))
                .expectedQuantity(BigDecimal.valueOf(30))
                .variance(BigDecimal.valueOf(0))
                .recountSequenceNumber(2)
                .recountOfCountEntry(second)
                .countedAt(Instant.now(TEST_CLOCK))
                .build());

        task.setLatestCountEntryId(third.getCountEntryId());
        task.setCountEntriesCount(3);
        cycleCountTaskRepository.save(task);

        String body = objectMapper.writeValueAsString(
                new SubmitRecountBody(task.getTaskId(), "manager-2", 30, "TRIGGER_RECOUNT_ANY"));

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/cycleCount/recount")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)))
                .andExpect(status().isBadRequest());
    }

    // ─── AC-6: Invalid input rejected — negative quantity → 400 ─────────────

    @Test
    @DisplayName("AC-27-6: submitting a count with negative actualQuantity returns 400 Bad Request")
    void submitCount_negativeQuantity_returns400() throws Exception {
        CycleCountTask task = cycleCountTaskRepository.save(CycleCountTask.builder()
                .binLocation("BIN-F6")
                .itemSku("SKU-006")
                .itemDescription("Test Item 6")
                .expectedQuantity(BigDecimal.valueOf(10))
                .auditorId("auditor-6")
                .status(TaskStatus.ASSIGNED)
                .countEntriesCount(0)
                .build());

        String body = objectMapper.writeValueAsString(new SubmitCountBody(task.getTaskId(), "auditor-6", -1));

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/cycleCount/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)))
                .andExpect(status().isBadRequest());
    }

    // ─── AC-7: Task not found returns 404 ────────────────────────────────────

    @Test
    @DisplayName("AC-27-7: submitting a count for a non-existent taskId returns 404 Not Found")
    void submitCount_unknownTaskId_returns404() throws Exception {
        UUID randomTaskId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        String body = objectMapper.writeValueAsString(new SubmitCountBody(randomTaskId, "auditor-7", 50));

        mockMvc.perform(withGatewayAuth(post("/v1/inventory/cycleCount/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)))
                .andExpect(status().isNotFound());
    }

    // ─── Helper: getTask returns task details ────────────────────────────────

    @Test
    @DisplayName("AC-27-8: getTask for existing task returns 200 OK with task details")
    void getTask_existingTask_returns200WithTaskDetails() throws Exception {
        CycleCountTask task = cycleCountTaskRepository.save(CycleCountTask.builder()
                .binLocation("BIN-G7")
                .itemSku("SKU-007")
                .itemDescription("Test Item 7")
                .expectedQuantity(BigDecimal.valueOf(60))
                .auditorId("auditor-7")
                .status(TaskStatus.ASSIGNED)
                .countEntriesCount(0)
                .build());

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/cycleCount/task/{taskId}", task.getTaskId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(task.getTaskId().toString()))
                .andExpect(jsonPath("$.itemSku").value("SKU-007"))
                .andExpect(jsonPath("$.status").value("ASSIGNED"));
    }

    // ─── Helper: getTask with unknown ID returns 404 ─────────────────────────

    @Test
    @DisplayName("AC-27-9: getTask for non-existent taskId returns 404 Not Found")
    void getTask_unknownTaskId_returns404() throws Exception {
        mockMvc.perform(withGatewayAuth(get(
                        "/v1/inventory/cycleCount/task/{taskId}",
                        UUID.fromString("00000000-0000-0000-0000-000000000001"))))
                .andExpect(status().isNotFound());
    }

    // ─── Helper: getCountHistory ─────────────────────────────────────────────

    @Test
    @DisplayName("AC-27-10: getCountHistory returns ordered history entries")
    void getCountHistory_existingEntries_returnsOrderedHistory() throws Exception {
        CycleCountTask task = cycleCountTaskRepository.save(CycleCountTask.builder()
                .binLocation("BIN-H8")
                .itemSku("SKU-008")
                .itemDescription("Test Item 8")
                .expectedQuantity(BigDecimal.valueOf(40))
                .auditorId("auditor-8")
                .status(TaskStatus.COUNTED_PENDING_REVIEW)
                .countEntriesCount(0)
                .build());

        CountEntry entry1 = countEntryRepository.save(CountEntry.builder()
                .cycleCountTask(task)
                .auditorId("auditor-8")
                .actualQuantity(BigDecimal.valueOf(38))
                .measuredQuantity(BigDecimal.valueOf(38))
                .expectedQuantity(BigDecimal.valueOf(40))
                .variance(BigDecimal.valueOf(-2))
                .recountSequenceNumber(0)
                .countedAt(Instant.now(TEST_CLOCK))
                .build());

        CountEntry entry2 = countEntryRepository.save(CountEntry.builder()
                .cycleCountTask(task)
                .auditorId("auditor-8")
                .actualQuantity(BigDecimal.valueOf(39))
                .measuredQuantity(BigDecimal.valueOf(39))
                .expectedQuantity(BigDecimal.valueOf(40))
                .variance(BigDecimal.valueOf(-1))
                .recountSequenceNumber(1)
                .recountOfCountEntry(entry1)
                .countedAt(Instant.now(TEST_CLOCK))
                .build());

        task.setLatestCountEntryId(entry2.getCountEntryId());
        task.setCountEntriesCount(2);
        cycleCountTaskRepository.save(task);

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/cycleCount/task/{taskId}/history", task.getTaskId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].recountSequenceNumber").value(0))
                .andExpect(jsonPath("$[1].recountSequenceNumber").value(1));
    }

    // ─── Helper: getTasksByAuditor ────────────────────────────────────────────

    @Test
    @DisplayName("AC-27-11: getTasksByAuditor returns tasks assigned to the given auditor")
    void getTasksByAuditor_existingTasks_returnsMatchingTasks() throws Exception {
        cycleCountTaskRepository.save(CycleCountTask.builder()
                .binLocation("BIN-I9")
                .itemSku("SKU-009")
                .itemDescription("Test Item 9")
                .expectedQuantity(BigDecimal.valueOf(20))
                .auditorId("auditor-target")
                .status(TaskStatus.ASSIGNED)
                .countEntriesCount(0)
                .build());

        cycleCountTaskRepository.save(CycleCountTask.builder()
                .binLocation("BIN-J10")
                .itemSku("SKU-010")
                .itemDescription("Test Item 10")
                .expectedQuantity(BigDecimal.valueOf(15))
                .auditorId("auditor-other")
                .status(TaskStatus.ASSIGNED)
                .countEntriesCount(0)
                .build());

        mockMvc.perform(withGatewayAuth(get("/v1/inventory/cycleCount/auditor/{auditorId}/tasks", "auditor-target")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].auditorId").value("auditor-target"));
    }

    // ─── I2 (#1026): interfering movements listing ───────────────────────────

    @Test
    @DisplayName("I2: GET /cycleCount/task/{id}/interfering-movements returns 200 with an array for a clean window")
    void getInterferingMovements_cleanWindow_returns200WithEmptyArray() throws Exception {
        CycleCountTask task = cycleCountTaskRepository.save(CycleCountTask.builder()
                .binLocation("BIN-I2-CONTRACT")
                .itemSku("SKU-I2-CONTRACT")
                .itemDescription("I2 contract test item")
                .expectedQuantity(BigDecimal.valueOf(10))
                .auditorId("auditor-i2")
                .status(TaskStatus.ASSIGNED)
                .countEntriesCount(0)
                .build());

        mockMvc.perform(withGatewayAuth(
                        get("/v1/inventory/cycleCount/task/{taskId}/interfering-movements", task.getTaskId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("I2: GET /cycleCount/task/{id}/interfering-movements for unknown task returns 404")
    void getInterferingMovements_unknownTask_returns404() throws Exception {
        mockMvc.perform(withGatewayAuth(get(
                        "/v1/inventory/cycleCount/task/{taskId}/interfering-movements",
                        UUID.fromString("00000000-0000-0000-0000-00000000dead"))))
                .andExpect(status().isNotFound());
    }

    // ─── Private request body records (inline serialization) ────────────────

    private record SubmitCountBody(UUID taskId, String auditorId, Integer actualQuantity) {}

    private record SubmitRecountBody(UUID taskId, String auditorId, Integer actualQuantity, String permission) {}
}
