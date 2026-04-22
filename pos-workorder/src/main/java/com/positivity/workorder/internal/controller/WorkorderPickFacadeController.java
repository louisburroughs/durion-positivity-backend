package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import com.positivity.workorder.internal.dto.pick.CompletePickTaskRequest;
import com.positivity.workorder.internal.dto.pick.ConfirmPickLineRequest;
import com.positivity.workorder.internal.dto.pick.ResolveScanRequest;
import com.positivity.workorder.internal.dto.pick.ResolveScanResponse;
import com.positivity.workorder.internal.dto.pick.WorkorderPickListResponse;
import com.positivity.workorder.internal.dto.pick.WorkorderPickTaskResponse;
import com.positivity.workorder.service.WorkorderPickFacadeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/workorders/{workorderId}")
@Tag(name = "Workorder Pick Facade", description = "Browser-facing pick list and pick task endpoints for workorder fulfillment")
public class WorkorderPickFacadeController {

        private final WorkorderPickFacadeService workorderPickFacadeService;

        public WorkorderPickFacadeController(WorkorderPickFacadeService workorderPickFacadeService) {
                this.workorderPickFacadeService = workorderPickFacadeService;
        }

        @GetMapping("/pick-list")
        @PreAuthorize("hasAuthority('inventory:pick_list:view')")
        @Operation(summary = "Get pick list for workorder")
        @ApiResponse(responseCode = "200", description = "Pick list retrieved successfully", content = @Content(schema = @Schema(implementation = WorkorderPickListResponse.class)))
        @ApiResponse(responseCode = "403", description = "Forbidden", content = @Content(schema = @Schema(implementation = ApiError.class)))
        @ApiResponse(responseCode = "404", description = "Workorder not found", content = @Content(schema = @Schema(implementation = ApiError.class)))
        public ResponseEntity<WorkorderPickListResponse> getPickList(
                        @Parameter(description = "Workorder ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable @NonNull UUID workorderId) {

                WorkorderPickListResponse response = workorderPickFacadeService.getPickListForWorkorder(workorderId);
                return ResponseEntity.ok(response);
        }

        @GetMapping("/pick-list/tasks")
        @PreAuthorize("hasAuthority('inventory:pick_list:view')")
        @Operation(summary = "Get pick tasks for workorder")
        @ApiResponse(responseCode = "200", description = "Pick tasks retrieved successfully", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = WorkorderPickTaskResponse.class))))
        @ApiResponse(responseCode = "403", description = "Forbidden", content = @Content(schema = @Schema(implementation = ApiError.class)))
        @ApiResponse(responseCode = "404", description = "Workorder not found", content = @Content(schema = @Schema(implementation = ApiError.class)))
        public ResponseEntity<List<WorkorderPickTaskResponse>> getPickTasks(
                        @Parameter(description = "Workorder ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable @NonNull UUID workorderId) {

                List<WorkorderPickTaskResponse> response = workorderPickFacadeService
                                .getPickTasksForWorkorder(workorderId);
                return ResponseEntity.ok(response);
        }

        @PostMapping("/pick-tasks/{pickTaskId}:resolve-scan")
        @PreAuthorize("hasAuthority('inventory:pick_list:execute')")
        @EmitEvent(id = "WORKORDER_PICK_FACADE_RESOLVE_SCAN", apiVersion = "1")
        @Operation(summary = "Resolve scan for pick task")
        @ApiResponse(responseCode = "200", description = "Scan resolved successfully", content = @Content(schema = @Schema(implementation = ResolveScanResponse.class)))
        @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ApiError.class)))
        @ApiResponse(responseCode = "403", description = "Forbidden", content = @Content(schema = @Schema(implementation = ApiError.class)))
        @ApiResponse(responseCode = "404", description = "Workorder or pick task not found", content = @Content(schema = @Schema(implementation = ApiError.class)))
        public ResponseEntity<ResolveScanResponse> resolveScan(
                        @Parameter(description = "Workorder ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable @NonNull UUID workorderId,
                        @Parameter(description = "Pick task ID", required = true, example = "550e8400-e29b-41d4-a716-446655440001") @PathVariable @NonNull UUID pickTaskId,
                        @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(schema = @Schema(implementation = ResolveScanRequest.class))) @RequestBody @Valid ResolveScanRequest request) {

                ResolveScanResponse response = workorderPickFacadeService.resolveScan(workorderId, pickTaskId, request);
                return ResponseEntity.ok(response);
        }

        @PostMapping("/pick-tasks/{pickTaskId}/lines/{pickLineId}:confirm")
        @PreAuthorize("hasAuthority('inventory:pick_list:execute')")
        @EmitEvent(id = "WORKORDER_PICK_FACADE_CONFIRM_LINE", apiVersion = "1")
        @Operation(summary = "Confirm pick line quantity")
        @ApiResponse(responseCode = "200", description = "Pick line confirmed successfully", content = @Content(schema = @Schema(implementation = WorkorderPickTaskResponse.class)))
        @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ApiError.class)))
        @ApiResponse(responseCode = "403", description = "Forbidden", content = @Content(schema = @Schema(implementation = ApiError.class)))
        @ApiResponse(responseCode = "404", description = "Workorder, pick task, or pick line not found", content = @Content(schema = @Schema(implementation = ApiError.class)))
        @ApiResponse(responseCode = "422", description = "Scan mismatch or domain validation error from pos-inventory", content = @Content(schema = @Schema(implementation = ApiError.class)))
        public ResponseEntity<WorkorderPickTaskResponse> confirmPickLine(
                        @Parameter(description = "Workorder ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable @NonNull UUID workorderId,
                        @Parameter(description = "Pick task ID", required = true, example = "550e8400-e29b-41d4-a716-446655440001") @PathVariable @NonNull UUID pickTaskId,
                        @Parameter(description = "Pick line ID", required = true, example = "550e8400-e29b-41d4-a716-446655440002") @PathVariable @NonNull UUID pickLineId,
                        @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(schema = @Schema(implementation = ConfirmPickLineRequest.class))) @RequestBody @Valid ConfirmPickLineRequest request) {

                WorkorderPickTaskResponse response = workorderPickFacadeService.confirmPickLine(workorderId, pickTaskId,
                                pickLineId, request);
                return ResponseEntity.ok(response);
        }

        @PostMapping("/pick-tasks/{pickTaskId}:complete")
        @PreAuthorize("hasAuthority('inventory:pick_list:execute')")
        @EmitEvent(id = "WORKORDER_PICK_FACADE_COMPLETE_TASK", apiVersion = "1")
        @Operation(summary = "Complete pick task")
        @ApiResponse(responseCode = "200", description = "Pick task completed successfully", content = @Content(schema = @Schema(implementation = WorkorderPickTaskResponse.class)))
        @ApiResponse(responseCode = "403", description = "Forbidden", content = @Content(schema = @Schema(implementation = ApiError.class)))
        @ApiResponse(responseCode = "404", description = "Workorder or pick task not found", content = @Content(schema = @Schema(implementation = ApiError.class)))
        public ResponseEntity<WorkorderPickTaskResponse> completePickTask(
                        @Parameter(description = "Workorder ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable @NonNull UUID workorderId,
                        @Parameter(description = "Pick task ID", required = true, example = "550e8400-e29b-41d4-a716-446655440001") @PathVariable @NonNull UUID pickTaskId,
                        @io.swagger.v3.oas.annotations.parameters.RequestBody(required = false, description = "Completion details; omit or send {} if no reason is provided", content = @Content(schema = @Schema(implementation = CompletePickTaskRequest.class))) @RequestBody(required = false) @Valid CompletePickTaskRequest request) {

                WorkorderPickTaskResponse response = workorderPickFacadeService.completePickTask(
                                workorderId, pickTaskId, request != null ? request : new CompletePickTaskRequest());
                return ResponseEntity.ok(response);
        }
}
