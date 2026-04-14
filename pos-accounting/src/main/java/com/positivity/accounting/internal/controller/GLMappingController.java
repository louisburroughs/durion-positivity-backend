package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.GLMappingCreateRequest;
import com.positivity.accounting.internal.dto.GLMappingCreateResponse;
import com.positivity.accounting.internal.dto.GLMappingResolveRequest;
import com.positivity.accounting.internal.dto.GLMappingResolveResponse;
import com.positivity.accounting.service.GLMappingService;
import com.positivity.events.EmitEvent;
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
@SecurityRequirement(name = "bearerAuth")
@RequiredArgsConstructor
@Tag(name = "GL Mapping API", description = "Endpoints for creating and resolving GL mappings")
@Validated
public class GLMappingController {

    private final GLMappingService glMappingService;

    /**
     * Create a new GL mapping.
     *
     * @param request mapping creation request
     * @return created mapping wrapped in response
     */
    @PostMapping
    @PreAuthorize("hasAuthority('accounting:gl-mapping:create')")
    @EmitEvent(id = "ACCOUNTING_GL_MAPPING_CREATE", apiVersion = "1")
    @Operation(
            summary = "Create GL mapping",
            description = "Creates a new GL mapping for source-system external code resolution")
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
    @PreAuthorize("hasAuthority('accounting:gl-mapping:resolve')")
    @EmitEvent(id = "ACCOUNTING_GL_MAPPING_RESOLVE", apiVersion = "1")
    @Operation(
            summary = "Resolve GL mapping",
            description = "Resolves source-system external code to a GL account using effective-date rules")
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
}
