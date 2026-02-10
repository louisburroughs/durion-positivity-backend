package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.GLMappingCreateRequest;
import com.positivity.accounting.internal.dto.GLMappingCreateResponse;
import com.positivity.accounting.internal.dto.GLMappingResolveRequest;
import com.positivity.accounting.internal.dto.GLMappingResolveResponse;
import com.positivity.accounting.service.GLMappingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for GL mapping operations.
 * Provides endpoints for creating and resolving GL mappings.
 */
@Slf4j
@RestController
@RequestMapping("/v1/accounting/mappings")
@RequiredArgsConstructor
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
    public ResponseEntity<GLMappingCreateResponse> createGLMapping(
            @Valid @RequestBody GLMappingCreateRequest request) {
        log.info("Create GL mapping request: sourceSystem={}, externalCode={}",
                request.getSourceSystem(), request.getExternalCode());

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
    public ResponseEntity<GLMappingResolveResponse> resolveGLMapping(
            @Valid @RequestBody GLMappingResolveRequest request) {
        log.info("Resolve GL mapping request: sourceSystem={}, externalCode={}, transactionDate={}",
                request.getSourceSystem(), request.getExternalCode(), request.getTransactionDate());

        GLMappingResolveResponse response = glMappingService.resolveMapping(request);

        return ResponseEntity.ok(response);
    }
}
