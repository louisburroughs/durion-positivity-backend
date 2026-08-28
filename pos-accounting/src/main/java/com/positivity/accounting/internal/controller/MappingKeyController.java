package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.MappingKeyCreateRequest;
import com.positivity.accounting.internal.dto.MappingKeyListResponse;
import com.positivity.accounting.internal.dto.MappingKeyResponse;
import com.positivity.accounting.internal.dto.MappingKeyUpdateRequest;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.MappingKeyService;
import com.positivity.events.EmitEvent;
import com.positivity.security.common.LogSanitizer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * REST Controller for Mapping Key management.
 */
@RestController
@RequestMapping("/v1/accounting")
@Tag(name = "Mapping Keys", description = "Manage mapping keys for GL mapping taxonomy")
@Validated
public class MappingKeyController {

    private static final Logger log = LoggerFactory.getLogger(MappingKeyController.class);

    // Allowed sort fields for listMappingKeysByCategory to prevent injection
    // attacks
    private static final Set<String> ALLOWED_SORT_FIELDS =
            Set.of("keyName", "keyCode", "description", "isActive", "createdAt", "updatedAt");

    private final MappingKeyService mappingKeyService;

    public MappingKeyController(MappingKeyService mappingKeyService) {
        this.mappingKeyService = mappingKeyService;
    }

    @PostMapping("/mapping-keys")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:mapping-key:create"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.MAPPING_KEY_CREATE + "')")
    @Operation(
            operationId = "createMappingKey",
            summary = "Create Mapping Key",
            description = """
                    Creates a mapping key inside a posting category, extending the taxonomy that GL mappings \
                    attach to.
                    Use this tool when adding a new key under an existing category; do not use \
                    createPostingCategory, which creates the parent category itself.
                    Preconditions: the posting category must exist, and no key with the same trimmed name may \
                    already exist inside it.
                    Required inputs: postingCategoryId (UUID), keyName (max 100 chars, trimmed before \
                    uniqueness check) and createdBy (max 50 chars); description is optional.
                    Emits an ACCOUNTING_MAPPING_KEY_CREATE event.
                    Returns 404 when the posting category does not exist, and 400 when the key name already \
                    exists in that category.
                    """,
            tags = {"Mapping Keys"})
    @ApiResponse(responseCode = "201", description = "Mapping key created")
    @ApiResponse(responseCode = "400", description = "Invalid request or duplicate name")
    @EmitEvent(id = "ACCOUNTING_MAPPING_KEY_CREATE", apiVersion = "1")
    public ResponseEntity<MappingKeyResponse> createMappingKey(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Mapping key to add under an existing posting category.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Tender type key", value = """
                                                                    {"postingCategoryId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "keyName":"TENDER_CASH",
                                                                     "description":"Cash tender postings",
                                                                     "createdBy":"jdoe"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    MappingKeyCreateRequest request) {
        log.info("Create mapping key request");
        MappingKeyResponse response = mappingKeyService.createMappingKey(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/mapping-keys/{mappingKeyId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:mapping-key:view"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.MAPPING_KEY_VIEW + "')")
    @Operation(
            operationId = "getMappingKey",
            summary = "Get Mapping Key",
            description = """
                    Returns one mapping key with its posting category, name, description and active flag.
                    Use this tool when the mapping key id is already known; use listMappingKeysByCategory \
                    instead when browsing the keys of a category.
                    Preconditions: the mapping key and its parent posting category must exist.
                    Required inputs: mappingKeyId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no mapping key exists for the supplied id.
                    """,
            tags = {"Mapping Keys"})
    @ApiResponse(responseCode = "200", description = "Mapping key returned")
    @ApiResponse(responseCode = "404", description = "Mapping key not found")
    public ResponseEntity<MappingKeyResponse> getMappingKey(
            @Parameter(description = "Mapping key identifier") @PathVariable @NonNull UUID mappingKeyId) {
        log.info("Get mapping key");
        MappingKeyResponse response = mappingKeyService.getMappingKey(mappingKeyId);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/mapping-keys/{mappingKeyId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:mapping-key:edit"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.MAPPING_KEY_EDIT + "')")
    @Operation(
            operationId = "updateMappingKey",
            summary = "Update Mapping Key",
            description = """
                    Renames a mapping key or changes its description; the key stays in its posting category.
                    Use this tool to correct a key's name or description; do not use deactivateMappingKey, \
                    which retires the key from future mapping use.
                    Preconditions: the mapping key must exist, and a changed name must not collide with \
                    another key in the same category.
                    Required inputs: mappingKeyId (UUID) as a path parameter, keyName (max 100 chars) and \
                    modifiedBy (max 50 chars); description is optional.
                    Emits an ACCOUNTING_MAPPING_KEY_UPDATE event.
                    Returns 404 when the mapping key does not exist, and 400 when the new name already exists \
                    in the category.
                    """,
            tags = {"Mapping Keys"})
    @ApiResponse(responseCode = "200", description = "Mapping key updated")
    @ApiResponse(responseCode = "404", description = "Mapping key not found")
    @ApiResponse(responseCode = "400", description = "Invalid request or duplicate name")
    @EmitEvent(id = "ACCOUNTING_MAPPING_KEY_UPDATE", apiVersion = "1")
    public ResponseEntity<MappingKeyResponse> updateMappingKey(
            @Parameter(description = "Mapping key identifier") @PathVariable @NonNull UUID mappingKeyId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Replacement name and description for the mapping key.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Rename key", value = """
                                                                    {"keyName":"TENDER_CASH_DRAWER",
                                                                     "description":"Cash drawer tender postings",
                                                                     "modifiedBy":"jdoe"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    MappingKeyUpdateRequest request) {
        log.info("Update mapping key");
        MappingKeyResponse response = mappingKeyService.updateMappingKey(mappingKeyId, request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/posting-categories/{postingCategoryId}/mapping-keys")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:mapping-key:view"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.MAPPING_KEY_VIEW + "')")
    @Operation(
            operationId = "listMappingKeysByCategory",
            summary = "List Mapping Keys By Category",
            description = """
                    Lists the mapping keys of one posting category as a paginated projection, optionally \
                    filtered by active flag.
                    Use this tool when browsing a category's keys; do not use getMappingKey, which fetches a \
                    single key by id.
                    Preconditions: the posting category must exist.
                    Required inputs: postingCategoryId (UUID) as a path parameter; page defaults to 0, size \
                    to 20, sort to keyName (an unsupported sort field silently falls back to keyName), and \
                    isActive is an optional filter.
                    Emits an ACCOUNTING_MAPPING_KEY_LIST audit event; no state changes.
                    Returns 404 when the posting category does not exist.
                    """,
            tags = {"Mapping Keys"})
    @ApiResponse(responseCode = "200", description = "Mapping keys listed")
    @ApiResponse(responseCode = "403", description = "Forbidden")
    @ApiResponse(responseCode = "404", description = "Posting category not found")
    @EmitEvent(id = "ACCOUNTING_MAPPING_KEY_LIST", apiVersion = "1")
    public ResponseEntity<MappingKeyListResponse> listMappingKeysByCategory(
            @Parameter(description = "Posting category identifier") @PathVariable @NonNull UUID postingCategoryId,
            @Parameter(description = "Page index (0-based)") @PositiveOrZero @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @Positive @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "Sort field") @NotBlank @RequestParam(defaultValue = "keyName") String sort,
            @Parameter(description = "Filter by active status") @RequestParam(required = false) Boolean isActive) {
        log.info("List mapping keys for category");

        // Sanitize sort parameter - use default if not in allowed list
        String sanitizedSort = ALLOWED_SORT_FIELDS.contains(sort) ? sort : "keyName";
        if (!sort.equals(sanitizedSort)) {
            log.warn("Invalid sort field '{}' requested, defaulting to '{}'", LogSanitizer.forLog(sort), sanitizedSort);
        }

        MappingKeyListResponse response =
                mappingKeyService.listMappingKeysByCategory(postingCategoryId, page, size, sanitizedSort, isActive);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/mapping-keys/{mappingKeyId}/deactivate")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:mapping-key:deactivate"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.MAPPING_KEY_DEACTIVATE + "')")
    @Operation(
            operationId = "deactivateMappingKey",
            summary = "Deactivate Mapping Key",
            description = """
                    Deactivates a mapping key so it can no longer be attached to new GL mappings; the record \
                    is retained.
                    Use this tool to retire an unused key; do not use deactivatePostingCategory, which \
                    retires the whole parent category.
                    Preconditions: the mapping key must exist and have no active GL mappings attached.
                    Required inputs: mappingKeyId (UUID) as a path parameter; there is no request body.
                    Emits an ACCOUNTING_MAPPING_KEY_DEACTIVATE event.
                    Returns 404 when the mapping key does not exist, 409 when active GL mappings still \
                    reference it, and 204 with no body on success.
                    """,
            tags = {"Mapping Keys"})
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
