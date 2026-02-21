package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.positivity.inventory.internal.dto.cyclecount.CountResponse;
import com.positivity.inventory.internal.dto.cyclecount.CountEntryResponse;
import com.positivity.inventory.internal.dto.cyclecount.CycleCountTaskResponse;
import com.positivity.inventory.internal.dto.cyclecount.SubmitCountRequest;
import com.positivity.inventory.internal.dto.cyclecount.SubmitRecountRequest;
import com.positivity.inventory.service.CycleCountService;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for cycle count operations.
 * 
 * <p>
 * Implements API endpoints for issue #27:
 * <ul>
 * <li>Submit counts and recounts</li>
 * <li>View count history</li>
 * <li>List assigned tasks</li>
 * </ul>
 */
@RequiredArgsConstructor
@Slf4j
@RestController
@RequestMapping("/api/inventory/cycleCount")
@Tag(name = "Cycle Count API", description = "API for cycle count operations and variance tracking")
public class CycleCountController {
        private final CycleCountService cycleCountService;

        /**
         * Submit a count for a cycle count task.
         */
        @PostMapping("/submit")
        @EmitEvent(id = "INVENTORY_CYCLE_COUNT_SUBMIT", apiVersion = "1")
        @Tag(name = "Cycle Count Operations")
        @Operation(summary = "Submit a count for a cycle count task", description = "Records the actual quantity counted by an auditor. Calculates variance and updates task status.")
        @ApiResponses(value = {
                        @ApiResponse(responseCode = "200", description = "Count submitted successfully", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CountResponse.class))),
                        @ApiResponse(responseCode = "400", description = "Invalid request or quantity"),
                        @ApiResponse(responseCode = "404", description = "Task not found")
        })
        public ResponseEntity<CountResponse> submitCount(
                        @Valid @RequestBody SubmitCountRequest request) {
                log.info("POST /api/inventory/cycle-count/submit - taskId: {}", request.getTaskId());

                CountResponse response = cycleCountService.submitCount(request);
                return ResponseEntity.ok(response);
        }

        /**
         * Submit a recount for a cycle count task.
         */
        @PostMapping("/recount")
        @EmitEvent(id = "INVENTORY_CYCLE_COUNT_RECOUNT", apiVersion = "1")
        @Tag(name = "Cycle Count Operations")
        @Operation(summary = "Submit a recount for a cycle count task", description = "Records a recount with permission validation and limit enforcement. "
                        +
                        "Maximum 2 recounts allowed (3 total counts).")
        @ApiResponses(value = {
                        @ApiResponse(responseCode = "200", description = "Recount submitted successfully", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CountResponse.class))),
                        @ApiResponse(responseCode = "400", description = "Invalid request or recount limit exceeded"),
                        @ApiResponse(responseCode = "403", description = "Insufficient permission"),
                        @ApiResponse(responseCode = "404", description = "Task not found")
        })
        public ResponseEntity<CountResponse> submitRecount(
                        @Valid @RequestBody SubmitRecountRequest request) {
                log.info("POST /api/inventory/cycle-count/recount - taskId: {}, permission: {}",
                                request.getTaskId(), request.getPermission());

                CountResponse response = cycleCountService.submitRecount(request);
                return ResponseEntity.ok(response);
        }

        /**
         * Get a cycle count task by ID.
         */
        @GetMapping("/task/{taskId}")
        @Tag(name = "Cycle Count Query")
        @Operation(summary = "Get cycle count task details", description = "Retrieves details of a specific cycle count task.")
        @ApiResponses(value = {
                        @ApiResponse(responseCode = "200", description = "Task retrieved successfully", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CycleCountTaskResponse.class))),
                        @ApiResponse(responseCode = "404", description = "Task not found")
        })
        public ResponseEntity<CycleCountTaskResponse> getTask(
                        @Parameter(description = "Task ID") @PathVariable UUID taskId) {
                log.info("GET /api/inventory/cycle-count/task/{}", taskId);

                CycleCountTaskResponse task = cycleCountService.getTask(taskId);
                return ResponseEntity.ok(task);
        }

        /**
         * Get count history for a task.
         */
        @GetMapping("/task/{taskId}/history")
        @Tag(name = "Cycle Count Query")
        @Operation(summary = "Get count history for a task", description = "Retrieves all count entries (original + recounts) for a task, ordered by sequence.")
        @ApiResponses(value = {
                        @ApiResponse(responseCode = "200", description = "History retrieved successfully", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CountEntryResponse.class)))
        })
        public ResponseEntity<List<CountEntryResponse>> getCountHistory(
                        @Parameter(description = "Task ID") @PathVariable UUID taskId) {
                log.info("GET /api/inventory/cycle-count/task/{}/history", taskId);

                List<CountEntryResponse> history = cycleCountService.getCountHistory(taskId);
                return ResponseEntity.ok(history);
        }

        /**
         * Get tasks assigned to an auditor.
         */
        @GetMapping("/auditor/{auditorId}/tasks")
        @Tag(name = "Cycle Count Query")
        @Operation(summary = "Get tasks assigned to an auditor", description = "Retrieves all cycle count tasks assigned to a specific auditor.")
        @ApiResponses(value = {
                        @ApiResponse(responseCode = "200", description = "Tasks retrieved successfully", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CycleCountTaskResponse.class)))
        })
        public ResponseEntity<List<CycleCountTaskResponse>> getAuditorTasks(
                        @Parameter(description = "Auditor ID") @PathVariable String auditorId) {
                log.info("GET /api/inventory/cycle-count/auditor/{}/tasks", auditorId);

                List<CycleCountTaskResponse> tasks = cycleCountService.getTasksByAuditor(auditorId);
                return ResponseEntity.ok(tasks);
        }

}
