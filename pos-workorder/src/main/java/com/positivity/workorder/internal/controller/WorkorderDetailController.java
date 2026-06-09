package com.positivity.workorder.internal.controller;

import com.positivity.workorder.internal.dto.WorkorderDetailResponse;
import com.positivity.workorder.service.WorkorderDetailService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

/**
 * Controller for workorder detail with role-based visibility.
 *
 * <p>
 * Implements CAP-005 Story #155 - Role-Based Visibility
 */
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/workorders")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Workorder Detail", description = "Workorder detail with role-based visibility")
public class WorkorderDetailController {

        private final WorkorderDetailService workorderDetailService;

        @GetMapping("/{workorderId}/detail")
        @PreAuthorize("hasAuthority('workorder:workorder:view')")
        @Operation(summary = "Get workorder detail with role-based visibility", description = "Returns comprehensive workorder detail. Financial fields are conditionally included based on user authorities. "
                        + "Capability flags indicate which actions the user can perform.", responses = {
                                        @ApiResponse(responseCode = "200", description = "Workorder detail retrieved successfully", content = @Content(schema = @Schema(implementation = WorkorderDetailResponse.class))),
                                        @ApiResponse(responseCode = "404", description = "Workorder not found")
                        })
        public ResponseEntity<WorkorderDetailResponse> getWorkorderDetail(
                        @Parameter(description = "Workorder ID", required = true, example = "550e8400-e29b-41d4-a716-446655440000") @PathVariable @NonNull UUID workorderId) {

                // Read authorities from Spring Security context (populated by
                // GatewayAuthoritiesFilter)
                Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                Set<String> userAuthorities = authentication != null
                                ? authentication.getAuthorities().stream()
                                                .map(GrantedAuthority::getAuthority)
                                                .collect(Collectors.toUnmodifiableSet())
                                : Collections.emptySet();

                log.debug(
                                "Getting workorder detail: workorderId(mask)={}, authorityCount={}",
                                maskForLog(workorderId),
                                userAuthorities.size());

                WorkorderDetailResponse response = workorderDetailService.getWorkorderDetail(workorderId,
                                userAuthorities);

                return ResponseEntity.ok(response);
        }

        private String maskForLog(Object value) {
                if (value == null) {
                        return "null";
                }
                String sanitized = value.toString().replace('\r', '_').replace('\n', '_').replace('\t', '_');
                int length = sanitized.length();
                if (length <= 4) {
                        return "****";
                }
                return sanitized.substring(0, 2) + "***" + sanitized.substring(length - 2);
        }
}
