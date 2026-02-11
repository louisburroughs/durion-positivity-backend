package com.positivity.accounting.internal.controller;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.positivity.accounting.internal.dto.DefaultGLMappingListResponse;
import com.positivity.accounting.internal.dto.DefaultGLMappingRequest;
import com.positivity.accounting.internal.dto.DefaultGLMappingResponse;
import com.positivity.accounting.service.DefaultGLMappingService;
import com.positivity.events.EmitEvent;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * REST Controller for Default GL Mappings.
 * Manages fallback GL accounts for event types without explicit posting rules.
 * 
 * Resolution priority:
 * 1. PostingRuleVersion (explicit rules)
 * 2. DefaultGLMapping with matching organizationId
 * 3. DefaultGLMapping with NULL organizationId (global default)
 * 4. Fail with UNMAPPED_EVENT_TYPE
 */
@Slf4j
@RestController
@RequestMapping("/v1/accounting/default-mappings")
@Tag(name = "Default GL Mappings", description = "Manage default GL account mappings for event types without explicit posting rules")
@RequiredArgsConstructor
public class DefaultGLMappingController {

    private final DefaultGLMappingService defaultGLMappingService;

    @PostMapping
    @PreAuthorize("hasAuthority('accounting:default-mapping:create')")
    @Operation(summary = "Create default GL mapping", description = "Create a new default GL account mapping for an event type")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Default mapping created"),
            @ApiResponse(responseCode = "400", description = "Invalid request"),
            @ApiResponse(responseCode = "403", description = "Forbidden")
    })
    @EmitEvent(id = "ACCOUNTING_DEFAULT_MAPPING_CREATE", apiVersion = "1")
    public ResponseEntity<DefaultGLMappingResponse> createDefaultMapping(
            @Valid @RequestBody DefaultGLMappingRequest request) {
        log.info("Create default GL mapping: eventType={}, orgId={}",
                request.getEventType(), request.getOrganizationId());
        DefaultGLMappingResponse response = defaultGLMappingService.createDefaultMapping(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('accounting:default-mapping:edit')")
    @Operation(summary = "Update default GL mapping", description = "Update an existing default GL account mapping")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Default mapping updated"),
            @ApiResponse(responseCode = "404", description = "Default mapping not found"),
            @ApiResponse(responseCode = "403", description = "Forbidden")
    })
    @EmitEvent(id = "ACCOUNTING_DEFAULT_MAPPING_UPDATE", apiVersion = "1")
    public ResponseEntity<DefaultGLMappingResponse> updateDefaultMapping(
            @Parameter(description = "Default mapping identifier") @PathVariable UUID id,
            @Valid @RequestBody DefaultGLMappingRequest request) {
        log.info("Update default GL mapping: id={}", id);
        DefaultGLMappingResponse response = defaultGLMappingService.updateDefaultMapping(id, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('accounting:default-mapping:delete')")
    @Operation(summary = "Deactivate default GL mapping", description = "Soft delete (deactivate) a default GL mapping")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Default mapping deactivated"),
            @ApiResponse(responseCode = "404", description = "Default mapping not found"),
            @ApiResponse(responseCode = "403", description = "Forbidden")
    })
    @EmitEvent(id = "ACCOUNTING_DEFAULT_MAPPING_DELETE", apiVersion = "1")
    public ResponseEntity<Void> deactivateDefaultMapping(
            @Parameter(description = "Default mapping identifier") @PathVariable UUID id) {
        log.info("Deactivate default GL mapping: id={}", id);
        defaultGLMappingService.deactivateDefaultMapping(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('accounting:default-mapping:view')")
    @Operation(summary = "Get default GL mapping", description = "Retrieve a default GL mapping by identifier")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Default mapping returned"),
            @ApiResponse(responseCode = "404", description = "Default mapping not found"),
            @ApiResponse(responseCode = "403", description = "Forbidden")
    })
    public ResponseEntity<DefaultGLMappingResponse> getDefaultMapping(
            @Parameter(description = "Default mapping identifier") @PathVariable UUID id) {
        log.info("Get default GL mapping: id={}", id);
        DefaultGLMappingResponse response = defaultGLMappingService.getDefaultMapping(id);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('accounting:default-mapping:view')")
    @Operation(summary = "List default GL mappings", description = "Retrieve paginated list of default GL mappings")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Default mappings listed"),
            @ApiResponse(responseCode = "403", description = "Forbidden")
    })
    @EmitEvent(id = "ACCOUNTING_DEFAULT_MAPPING_LIST", apiVersion = "1")
    public ResponseEntity<DefaultGLMappingListResponse> listDefaultMappings(
            @Parameter(description = "Page index (0-based)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size) {
        log.info("List default GL mappings: page={}, size={}", page, size);
        DefaultGLMappingListResponse response = defaultGLMappingService.listDefaultMappings(page, size);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/search")
    @PreAuthorize("hasAuthority('accounting:default-mapping:view')")
    @Operation(summary = "Search default GL mappings", description = "Find default GL mappings by event type or organization")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Matching default mappings returned"),
            @ApiResponse(responseCode = "403", description = "Forbidden")
    })
    public ResponseEntity<List<DefaultGLMappingResponse>> searchDefaultMappings(
            @Parameter(description = "Filter by event type") @RequestParam(required = false) @Nullable String eventType,
            @Parameter(description = "Filter by organization ID") @RequestParam(required = false) @Nullable UUID organizationId) {
        log.info("Search default GL mappings: eventType={}, orgId={}", eventType, organizationId);

        List<DefaultGLMappingResponse> response;
        if (eventType != null) {
            response = defaultGLMappingService.findByEventType(eventType);
        } else if (organizationId != null) {
            response = defaultGLMappingService.findByOrganization(organizationId);
        } else {
            // No filters: return global defaults
            response = defaultGLMappingService.findAllGlobalDefaults();
        }

        return ResponseEntity.ok(response);
    }

    @GetMapping("/global")
    @PreAuthorize("hasAuthority('accounting:default-mapping:view')")
    @Operation(summary = "List global default mappings", description = "Retrieve all global default GL mappings (organizationId IS NULL)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Global default mappings returned"),
            @ApiResponse(responseCode = "403", description = "Forbidden")
    })
    public ResponseEntity<List<DefaultGLMappingResponse>> listGlobalDefaults() {
        log.info("List global default GL mappings");
        List<DefaultGLMappingResponse> response = defaultGLMappingService.findAllGlobalDefaults();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/resolve")
    @PreAuthorize("hasAuthority('accounting:default-mapping:view')")
    @Operation(summary = "Resolve default mapping for event", description = "Find the most specific active default mapping for an event type and organization")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Default mapping resolved"),
            @ApiResponse(responseCode = "404", description = "No default mapping found"),
            @ApiResponse(responseCode = "403", description = "Forbidden")
    })
    public ResponseEntity<DefaultGLMappingResponse> resolveDefaultMapping(
            @Parameter(description = "Event type") @RequestParam String eventType,
            @Parameter(description = "Organization ID (optional)") @RequestParam(required = false) @Nullable UUID organizationId) {
        log.info("Resolve default GL mapping: eventType={}, orgId={}", eventType, organizationId);

        DefaultGLMappingResponse response = defaultGLMappingService.findActiveDefaultForEvent(eventType,
                organizationId);
        if (response == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(response);
    }
}
