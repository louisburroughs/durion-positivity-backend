package com.positivity.inventory.controller;

import com.positivity.inventory.dto.cyclecount.CountResponse;
import com.positivity.inventory.dto.cyclecount.SubmitCountRequest;
import com.positivity.inventory.dto.cyclecount.SubmitRecountRequest;
import com.positivity.inventory.entity.CountEntry;
import com.positivity.inventory.entity.CycleCountTask;
import com.positivity.inventory.exception.InsufficientPermissionException;
import com.positivity.inventory.exception.InvalidCountQuantityException;
import com.positivity.inventory.exception.RecountLimitExceededException;
import com.positivity.inventory.exception.TaskNotFoundException;
import com.positivity.inventory.service.CycleCountService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for cycle count operations.
 * 
 * <p>Implements API endpoints for issue #27:
 * <ul>
 *   <li>Submit counts and recounts</li>
 *   <li>View count history</li>
 *   <li>List assigned tasks</li>
 * </ul>
 */
@RequiredArgsConstructor
@Slf4j
@RestController
@RequestMapping("/api/inventory/cycle-count")
@Tag(name = "Cycle Count API", description = "API for cycle count operations and variance tracking")
public class CycleCountController {
    
    private final CycleCountService cycleCountService;
    
    /**
     * Submit a count for a cycle count task.
     */
    @PostMapping("/submit")
    @Operation(
        summary = "Submit a count for a cycle count task",
        description = "Records the actual quantity counted by an auditor. Calculates variance and updates task status."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Count submitted successfully",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CountResponse.class))),
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
    @Operation(
        summary = "Submit a recount for a cycle count task",
        description = "Records a recount with permission validation and limit enforcement. " +
                     "Maximum 2 recounts allowed (3 total counts)."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Recount submitted successfully",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CountResponse.class))),
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
    @Operation(
        summary = "Get cycle count task details",
        description = "Retrieves details of a specific cycle count task."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Task retrieved successfully",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CycleCountTask.class))),
        @ApiResponse(responseCode = "404", description = "Task not found")
    })
    public ResponseEntity<CycleCountTask> getTask(
            @Parameter(description = "Task ID") @PathVariable UUID taskId) {
        log.info("GET /api/inventory/cycle-count/task/{}", taskId);
        
        CycleCountTask task = cycleCountService.getTask(taskId);
        return ResponseEntity.ok(task);
    }
    
    /**
     * Get count history for a task.
     */
    @GetMapping("/task/{taskId}/history")
    @Operation(
        summary = "Get count history for a task",
        description = "Retrieves all count entries (original + recounts) for a task, ordered by sequence."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "History retrieved successfully",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CountEntry.class)))
    })
    public ResponseEntity<List<CountEntry>> getCountHistory(
            @Parameter(description = "Task ID") @PathVariable UUID taskId) {
        log.info("GET /api/inventory/cycle-count/task/{}/history", taskId);
        
        List<CountEntry> history = cycleCountService.getCountHistory(taskId);
        return ResponseEntity.ok(history);
    }
    
    /**
     * Get tasks assigned to an auditor.
     */
    @GetMapping("/auditor/{auditorId}/tasks")
    @Operation(
        summary = "Get tasks assigned to an auditor",
        description = "Retrieves all cycle count tasks assigned to a specific auditor."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Tasks retrieved successfully",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CycleCountTask.class)))
    })
    public ResponseEntity<List<CycleCountTask>> getAuditorTasks(
            @Parameter(description = "Auditor ID") @PathVariable String auditorId) {
        log.info("GET /api/inventory/cycle-count/auditor/{}/tasks", auditorId);
        
        List<CycleCountTask> tasks = cycleCountService.getTasksByAuditor(auditorId);
        return ResponseEntity.ok(tasks);
    }
    
    // Exception Handlers
    
    @ExceptionHandler(TaskNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleTaskNotFound(TaskNotFoundException ex) {
        log.error("Task not found: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", ex.getMessage()));
    }
    
    @ExceptionHandler(InvalidCountQuantityException.class)
    public ResponseEntity<Map<String, String>> handleInvalidQuantity(InvalidCountQuantityException ex) {
        log.error("Invalid quantity: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage()));
    }
    
    @ExceptionHandler(RecountLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleRecountLimitExceeded(RecountLimitExceededException ex) {
        log.error("Recount limit exceeded: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                    "error", ex.getMessage(),
                    "taskId", ex.getTaskId().toString(),
                    "currentCount", ex.getCurrentCount(),
                    "maxAllowed", ex.getMaxAllowed()
                ));
    }
    
    @ExceptionHandler(InsufficientPermissionException.class)
    public ResponseEntity<Map<String, String>> handleInsufficientPermission(InsufficientPermissionException ex) {
        log.error("Insufficient permission: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", ex.getMessage()));
    }
    
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException ex) {
        log.error("Illegal state: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage()));
    }
}
