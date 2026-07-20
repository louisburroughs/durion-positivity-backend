package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.GLMappingCreateRequest;
import com.positivity.accounting.internal.dto.GLMappingCreateResponse;
import com.positivity.accounting.internal.dto.GLMappingResolveRequest;
import com.positivity.accounting.internal.dto.GLMappingResolveResponse;
import com.positivity.accounting.internal.dto.MappingResolutionTestRequest;
import com.positivity.accounting.internal.dto.MappingResolutionTestResponse;
import com.positivity.accounting.service.GLMappingService;
import com.positivity.accounting.service.MappingResolutionTestService;
import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for GL mapping operations.
 * Provides endpoints for creating and resolving GL mappings.
 */
@Slf4j
@RestController
@RequestMapping("/v1/accounting/mappings")
@RequiredArgsConstructor
@Tag(name = "GL Mapping API", description = "Endpoints for creating and resolving GL mappings")
@Validated
public class GLMappingController {

    private final GLMappingService glMappingService;
    private final MappingResolutionTestService mappingResolutionTestService;

    /**
     * Create a new GL mapping.
     *
     * @param request mapping creation request
     * @return created mapping wrapped in response
     */
    @PostMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:gl-mapping:create"})
    @PreAuthorize("hasAuthority('accounting:gl-mapping:create')")
    @EmitEvent(id = "ACCOUNTING_GL_MAPPING_CREATE", apiVersion = "1")
    @Operation(
            summary = "Create GL mapping",
            description = "Creates a new GL mapping for source-system external code resolution",
            tags = {"GL Mapping API"})
    @ApiResponse(
            responseCode = "201",
            description = "GL mapping created",
            content = @Content(schema = @Schema(implementation = GLMappingCreateResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request payload")
    public ResponseEntity<GLMappingCreateResponse> createGLMapping(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "GL mapping creation request",
                            required = true,
                            content = @Content(schema = @Schema(implementation = GLMappingCreateRequest.class)))
                    @Valid
                    @RequestBody
                    GLMappingCreateRequest request) {
        log.info(
                "Create GL mapping request: sourceSystem={}, externalCode={}",
                request.getSourceSystem(),
                request.getExternalCode());

        GLMappingCreateResponse response = glMappingService.createMapping(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Resolve an external code to a GL account.
     *
     * @param request resolve request with source, code, and transaction date
     * @return GL account ID
     */
    @PostMapping("/resolve")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:gl-mapping:resolve"})
    @PreAuthorize("hasAuthority('accounting:gl-mapping:resolve')")
    @EmitEvent(id = "ACCOUNTING_GL_MAPPING_RESOLVE", apiVersion = "1")
    @Operation(
            summary = "Resolve GL mapping",
            description = "Resolves source-system external code to a GL account using effective-date rules",
            tags = {"GL Mapping API"})
    @ApiResponse(
            responseCode = "200",
            description = "GL mapping resolved",
            content = @Content(schema = @Schema(implementation = GLMappingResolveResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request payload")
    public ResponseEntity<GLMappingResolveResponse> resolveGLMapping(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "GL mapping resolve request",
                            required = true,
                            content = @Content(schema = @Schema(implementation = GLMappingResolveRequest.class)))
                    @Valid
                    @RequestBody
                    GLMappingResolveRequest request) {
        log.info(
                "Resolve GL mapping request: sourceSystem={}, externalCode={}, transactionDate={}",
                request.getSourceSystem(),
                request.getExternalCode(),
                request.getTransactionDate());

        GLMappingResolveResponse response = glMappingService.resolveMapping(request);

        return ResponseEntity.ok(response);
    }

    /**
     * Dry-run posting rule / mapping resolution (story E3, issue #957).
     *
     * @param request dry-run request with event type, optional sample payload, and
     *                transaction date
     * @return dry-run resolution result; matched=false when no rule matched
     */
    @PostMapping("/resolve-test")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:posting_rules:view"})
    @PreAuthorize("hasAuthority('accounting:posting_rules:view')")
    @EmitEvent(id = "ACCOUNTING_MAPPING_RESOLVE_TEST", apiVersion = "1")
    @Operation(
            summary = "Dry-run mapping/rule resolution",
            description = "Resolves a hypothetical accounting event against the published posting rules and GL "
                    + "mappings, returning the matched rule (id/name/version), mapping details, the exact journal "
                    + "entry lines the evaluator would post (including E1 proportional split-line shares and "
                    + "residual distribution), and per-predicate evaluation outcomes (E2 condition grammar). "
                    + "This is a read-only inspection tool: NOTHING is persisted — no accounting event, journal "
                    + "entry, or outbox record is created. A no-match outcome is a normal 200 response with "
                    + "matched=false (not a 404).",
            tags = {"GL Mapping API"})
    @ApiResponse(
            responseCode = "200",
            description = "Dry-run resolution completed (matched=false when no published rule matched)",
            content = @Content(schema = @Schema(implementation = MappingResolutionTestResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request payload, or the sample payload could not be interpreted by the rules",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Missing or invalid authentication",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks the accounting:posting_rules:view permission",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<MappingResolutionTestResponse> resolveTestMapping(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Dry-run mapping/rule resolution request",
                            required = true,
                            content = @Content(schema = @Schema(implementation = MappingResolutionTestRequest.class)))
                    @Valid
                    @RequestBody
                    MappingResolutionTestRequest request) {
        log.info(
                "Dry-run mapping resolution request: eventType={}, transactionDate={}",
                request.getEventType(),
                request.getTransactionDate());

        MappingResolutionTestResponse response = mappingResolutionTestService.resolveTest(request);

        return ResponseEntity.ok(response);
    }
}
