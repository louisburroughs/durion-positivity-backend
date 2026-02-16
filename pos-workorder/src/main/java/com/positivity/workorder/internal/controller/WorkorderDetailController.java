package com.positivity.workorder.internal.controller;

import com.positivity.workorder.internal.dto.WorkorderDetailResponse;
import com.positivity.workorder.service.WorkorderDetailService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Controller for workorder detail with role-based visibility.
 * 
 * <p>
 * Implements CAP-005 Story #155 - Role-Based Visibility
 */
@RestController
@RequestMapping("/v1/workorders")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Workorder Detail", description = "Workorder detail with role-based visibility")
public class WorkorderDetailController {

    private final WorkorderDetailService workorderDetailService;

    @GetMapping("/{workorderId}/detail")
    @Operation(summary = "Get workorder detail with role-based visibility", description = "Returns comprehensive workorder detail. Financial fields are conditionally included based on user authorities. "
            +
            "Capability flags indicate which actions the user can perform.", responses = {
                    @ApiResponse(responseCode = "200", description = "Workorder detail retrieved successfully", content = @Content(schema = @Schema(implementation = WorkorderDetailResponse.class))),
                    @ApiResponse(responseCode = "404", description = "Workorder not found")
            })
    public ResponseEntity<WorkorderDetailResponse> getWorkorderDetail(
            @Parameter(description = "Workorder ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable @NonNull UUID workorderId,
            @Parameter(description = "User authorities (comma-separated)", example = "workorder:workorder:view,workorder:financials:view") @RequestHeader(value = "X-Authorities", required = false) String authorities) {

        // Extract authorities from header
        Set<String> userAuthorities = extractAuthorities(authorities);

        log.debug("Getting workorder detail: workorderId={}, authorities={}", workorderId, userAuthorities);

        WorkorderDetailResponse response = workorderDetailService.getWorkorderDetail(workorderId, userAuthorities);

        return ResponseEntity.ok(response);
    }

    private Set<String> extractAuthorities(String authoritiesHeader) {
        if (authoritiesHeader == null || authoritiesHeader.isBlank()) {
            return Set.of();
        }
        return new HashSet<>(Arrays.asList(authoritiesHeader.split(",")));
    }
}
