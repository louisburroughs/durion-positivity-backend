package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.MappingKeyCreateRequest;
import com.positivity.accounting.internal.dto.MappingKeyListResponse;
import com.positivity.accounting.internal.dto.MappingKeyResponse;
import com.positivity.accounting.internal.dto.MappingKeyUpdateRequest;
import com.positivity.accounting.service.MappingKeyService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Set;
import java.util.UUID;

/**
 * REST Controller for Mapping Key management.
 */
@RestController
@RequestMapping("/v1/accounting")
@Tag(name = "Mapping Keys", description = "Manage mapping keys for GL mapping taxonomy")
public class MappingKeyController {

        private static final Logger log = LoggerFactory.getLogger(MappingKeyController.class);

        // Allowed sort fields for listMappingKeysByCategory to prevent injection
        // attacks
        private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
                        "keyName",
                        "keyCode",
                        "description",
                        "isActive",
                        "createdAt",
                        "updatedAt");

        private final MappingKeyService mappingKeyService;

        public MappingKeyController(MappingKeyService mappingKeyService) {
                this.mappingKeyService = mappingKeyService;
        }

        @PostMapping("/mapping-keys")
        @PreAuthorize("hasAuthority('accounting:mapping-key:create')")
        @Operation(summary = "Create mapping key", description = "Create a new mapping key for a posting category.")
        @ApiResponse(responseCode = "201", description = "Mapping key created")
        @ApiResponse(responseCode = "400", description = "Invalid request or duplicate name")
        @EmitEvent(id = "ACCOUNTING_MAPPING_KEY_CREATE", apiVersion = "1")
        public ResponseEntity<MappingKeyResponse> createMappingKey(
                        @Valid @RequestBody MappingKeyCreateRequest request) {
                log.info("Create mapping key request");
                MappingKeyResponse response = mappingKeyService.createMappingKey(request);
                return ResponseEntity.status(HttpStatus.CREATED).body(response);
        }

        @GetMapping("/mapping-keys/{mappingKeyId}")
        @PreAuthorize("hasAuthority('accounting:mapping-key:view')")
        @Operation(summary = "Get mapping key", description = "Retrieve a mapping key by identifier.")
        @ApiResponse(responseCode = "200", description = "Mapping key returned")
        @ApiResponse(responseCode = "404", description = "Mapping key not found")
        public ResponseEntity<MappingKeyResponse> getMappingKey(
                        @Parameter(description = "Mapping key identifier") @PathVariable @NonNull UUID mappingKeyId) {
                log.info("Get mapping key");
                MappingKeyResponse response = mappingKeyService.getMappingKey(mappingKeyId);
                return ResponseEntity.ok(response);
        }

        @PutMapping("/mapping-keys/{mappingKeyId}")
        @PreAuthorize("hasAuthority('accounting:mapping-key:edit')")
        @Operation(summary = "Update mapping key", description = "Update an existing mapping key.")
        @ApiResponse(responseCode = "200", description = "Mapping key updated")
        @ApiResponse(responseCode = "404", description = "Mapping key not found")
        @ApiResponse(responseCode = "400", description = "Invalid request or duplicate name")
        @EmitEvent(id = "ACCOUNTING_MAPPING_KEY_UPDATE", apiVersion = "1")
        public ResponseEntity<MappingKeyResponse> updateMappingKey(
                        @Parameter(description = "Mapping key identifier") @PathVariable @NonNull UUID mappingKeyId,
                        @Valid @RequestBody MappingKeyUpdateRequest request) {
                log.info("Update mapping key");
                MappingKeyResponse response = mappingKeyService.updateMappingKey(mappingKeyId, request);
                return ResponseEntity.ok(response);
        }

        @GetMapping("/posting-categories/{postingCategoryId}/mapping-keys")
        @PreAuthorize("hasAuthority('accounting:mapping-key:view')")
        @Operation(summary = "List mapping keys by category", description = "Retrieve paginated mapping keys for a posting category.")
        @ApiResponse(responseCode = "200", description = "Mapping keys listed")
        @ApiResponse(responseCode = "403", description = "Forbidden")
        @ApiResponse(responseCode = "404", description = "Posting category not found")
        @EmitEvent(id = "ACCOUNTING_MAPPING_KEY_LIST", apiVersion = "1")
        public ResponseEntity<MappingKeyListResponse> listMappingKeysByCategory(
                        @Parameter(description = "Posting category identifier") @PathVariable @NonNull UUID postingCategoryId,
                        @Parameter(description = "Page index (0-based)") @RequestParam(defaultValue = "0") int page,
                        @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size,
                        @Parameter(description = "Sort field") @RequestParam(defaultValue = "keyName") String sort,
                        @Parameter(description = "Filter by active status") @RequestParam(required = false) Boolean isActive) {
                log.info("List mapping keys for category");

                // Sanitize sort parameter - use default if not in allowed list
                String sanitizedSort = ALLOWED_SORT_FIELDS.contains(sort) ? sort : "keyName";
                if (!sort.equals(sanitizedSort)) {
                        log.warn("Invalid sort field '{}' requested, defaulting to '{}'", sort, sanitizedSort);
                }

                MappingKeyListResponse response = mappingKeyService.listMappingKeysByCategory(postingCategoryId, page,
                                size, sanitizedSort, isActive);
                return ResponseEntity.ok(response);
        }

        @PostMapping("/mapping-keys/{mappingKeyId}/deactivate")
        @PreAuthorize("hasAuthority('accounting:mapping-key:deactivate')")
        @Operation(summary = "Deactivate mapping key", description = "Deactivate a mapping key.")
        @ApiResponse(responseCode = "204", description = "Mapping key deactivated")
        @ApiResponse(responseCode = "404", description = "Mapping key not found")
        @ApiResponse(responseCode = "409", description = "Cannot deactivate - active mappings exist")
        @EmitEvent(id = "ACCOUNTING_MAPPING_KEY_DEACTIVATE", apiVersion = "1")
        public ResponseEntity<Void> deactivateMappingKey(
                        @Parameter(description = "Mapping key identifier") @PathVariable @NonNull UUID mappingKeyId) {
                log.info("Deactivate mapping key");
                mappingKeyService.deactivateMappingKey(mappingKeyId);
                return ResponseEntity.noContent().build();
        }
}
