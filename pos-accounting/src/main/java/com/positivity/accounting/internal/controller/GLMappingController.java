package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.GLMappingCreateRequest;
import com.positivity.accounting.internal.dto.GLMappingCreateResponse;
import com.positivity.accounting.internal.dto.GLMappingResolveRequest;
import com.positivity.accounting.internal.dto.GLMappingResolveResponse;
import com.positivity.accounting.internal.dto.MappingResolutionTestRequest;
import com.positivity.accounting.internal.dto.MappingResolutionTestResponse;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.GLMappingService;
import com.positivity.accounting.internal.service.MappingResolutionTestService;
import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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
    @PreAuthorize("hasAuthority('" + AccountingPermissions.GL_MAPPING_CREATE + "')")
    @EmitEvent(id = "ACCOUNTING_GL_MAPPING_CREATE", apiVersion = "1")
    @Operation(
            operationId = "createGLMapping",
            summary = "Create GL Mapping",
            description = """
                    Creates a date-effective mapping from a source-system external code to a GL account, used \
                    when posting events that carry external codes.
                    Use this tool to register how an upstream system's code lands in the ledger; do not use \
                    createDefaultMapping, which sets the fallback debit and credit pair for an event type \
                    without explicit rules.
                    Preconditions: the target GL account must exist and be active, and the new effective date \
                    range must not overlap an existing mapping for the same sourceSystem and externalCode.
                    Required inputs: sourceSystem, externalCode, glAccountId (UUID) and effectiveStartDate; \
                    effectiveEndDate defaults to null (open-ended) and dimensions are optional.
                    Emits an ACCOUNTING_GL_MAPPING_CREATE event; a cash-receipt code mapped to an implausible \
                    account subtype logs a non-blocking warning.
                    Returns 400 when the GL account is missing or inactive, or when the effective dates \
                    overlap an existing mapping.
                    """,
            tags = {"GL Mapping API"})
    @ApiResponse(
            responseCode = "201",
            description = "GL mapping created",
            content = @Content(schema = @Schema(implementation = GLMappingCreateResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request payload")
    public ResponseEntity<GLMappingCreateResponse> createGLMapping(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Date-effective external-code-to-GL-account mapping to create.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = GLMappingCreateRequest.class),
                                            examples = @ExampleObject(name = "POS cash code mapping", value = """
                                                                    {"sourceSystem":"POS",
                                                                     "externalCode":"CASH_RECEIPT",
                                                                     "glAccountId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "effectiveStartDate":"2026-01-01T00:00:00"}
                                                                    """)))
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
    @PreAuthorize("hasAuthority('" + AccountingPermissions.GL_MAPPING_RESOLVE + "')")
    @EmitEvent(id = "ACCOUNTING_GL_MAPPING_RESOLVE", apiVersion = "1")
    @Operation(
            operationId = "resolveGLMapping",
            summary = "Resolve GL Mapping",
            description = """
                    Resolves a source-system external code to the GL account whose mapping is effective on \
                    the supplied transaction date.
                    Use this tool to look up where a coded transaction will post; do not use \
                    resolveTestMapping, which dry-runs full posting-rule evaluation for an event payload \
                    rather than a single code lookup.
                    Preconditions: a mapping must exist for the sourceSystem and externalCode whose effective \
                    date range covers the transaction date.
                    Required inputs: sourceSystem, externalCode and transactionDate (ISO date-time); there \
                    are no optional fields.
                    Emits an ACCOUNTING_GL_MAPPING_RESOLVE audit event; no mappings or entries are created.
                    Returns 400 when no mapping is effective for the code on that date.
                    """,
            tags = {"GL Mapping API"})
    @ApiResponse(
            responseCode = "200",
            description = "GL mapping resolved",
            content = @Content(schema = @Schema(implementation = GLMappingResolveResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request payload")
    public ResponseEntity<GLMappingResolveResponse> resolveGLMapping(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "External code and transaction date to resolve against effective mappings.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = GLMappingResolveRequest.class),
                                            examples = @ExampleObject(name = "Resolve POS cash code", value = """
                                                                    {"sourceSystem":"POS",
                                                                     "externalCode":"CASH_RECEIPT",
                                                                     "transactionDate":"2026-08-13T00:00:00"}
                                                                    """)))
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
    @PreAuthorize("hasAuthority('" + AccountingPermissions.POSTING_RULES_VIEW + "')")
    @EmitEvent(id = "ACCOUNTING_MAPPING_RESOLVE_TEST", apiVersion = "1")
    @Operation(
            operationId = "resolveTestMapping",
            summary = "Dry-Run Mapping Rule Resolution",
            description = """
                    Resolves a hypothetical accounting event against the published posting rules and GL \
                    mappings, returning the matched rule (id, name, version), the exact journal entry lines \
                    the evaluator would post including proportional split-line shares and residual \
                    distribution, and per-predicate evaluation outcomes.
                    Use this tool to inspect what a rule set would do before real events arrive; do not use \
                    resolveGLMapping, which only resolves a single external code to one account.
                    Preconditions: a PUBLISHED posting rule set should exist, though a no-match outcome is a \
                    normal 200 response with matched=false rather than an error.
                    Required inputs: eventType and transactionDate (ISO date); samplePayload is an optional \
                    JSON object evaluated by the rule predicates.
                    Emits an ACCOUNTING_MAPPING_RESOLVE_TEST audit event only; nothing is persisted, and no \
                    accounting event, journal entry or outbox record is created.
                    Returns 400 when the sample payload cannot be interpreted by the rules.
                    """,
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
                            description =
                                    "Hypothetical event type, sample payload and date to evaluate against published rules.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = MappingResolutionTestRequest.class),
                                            examples = @ExampleObject(name = "Invoice finalized dry run", value = """
                                                                    {"eventType":"INVOICE_FINALIZED",
                                                                     "samplePayload":{"totalAmount":150.00,"taxAmount":10.50},
                                                                     "transactionDate":"2026-08-13"}
                                                                    """)))
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
