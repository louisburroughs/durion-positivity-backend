package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.PostingCategoryCreateRequest;
import com.positivity.accounting.internal.dto.PostingCategoryListResponse;
import com.positivity.accounting.internal.dto.PostingCategoryResponse;
import com.positivity.accounting.internal.dto.PostingCategoryUpdateRequest;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.PostingCategoryService;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * REST Controller for Posting Category management.
 */
@RestController
@RequestMapping("/v1/accounting/posting-categories")
@Tag(name = "Posting Categories", description = "Manage posting categories for GL mapping taxonomy")
@Validated
public class PostingCategoryController {

    private static final Logger log = LoggerFactory.getLogger(PostingCategoryController.class);

    // Allowed sort fields for listPostingCategories to prevent injection attacks
    private static final Set<String> ALLOWED_SORT_FIELDS =
            Set.of("categoryName", "categoryCode", "description", "isActive", "createdAt", "updatedAt");

    private final PostingCategoryService postingCategoryService;

    public PostingCategoryController(PostingCategoryService postingCategoryService) {
        this.postingCategoryService = postingCategoryService;
    }

    @PostMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:posting-category:create"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.POSTING_CATEGORY_CREATE + "')")
    @Operation(
            operationId = "createPostingCategory",
            summary = "Create Posting Category",
            description = """
                    Creates a posting category, the top level of the GL mapping taxonomy under which mapping \
                    keys are grouped.
                    Use this tool when adding a new grouping to the taxonomy; do not use createMappingKey, \
                    which adds a key inside an existing category.
                    Preconditions: no category with the same trimmed name may already exist.
                    Required inputs: categoryName (max 100 chars, trimmed before the uniqueness check) and \
                    createdBy (max 50 chars); description is optional.
                    Emits an ACCOUNTING_POSTING_CATEGORY_CREATE event.
                    Returns 400 when a category with the same name already exists.
                    """,
            tags = {"Posting Categories"})
    @ApiResponse(responseCode = "201", description = "Posting category created")
    @ApiResponse(responseCode = "400", description = "Invalid request or duplicate name")
    @EmitEvent(id = "ACCOUNTING_POSTING_CATEGORY_CREATE", apiVersion = "1")
    public ResponseEntity<PostingCategoryResponse> createPostingCategory(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Posting category to add to the GL mapping taxonomy.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Tender category", value = """
                                                                    {"categoryName":"TENDER_TYPES",
                                                                     "description":"Payment tender classifications",
                                                                     "createdBy":"jdoe"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    PostingCategoryCreateRequest request) {
        log.info("Create posting category request");
        PostingCategoryResponse response = postingCategoryService.createPostingCategory(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{postingCategoryId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:posting-category:view"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.POSTING_CATEGORY_VIEW + "')")
    @Operation(
            operationId = "getPostingCategory",
            summary = "Get Posting Category",
            description = """
                    Returns one posting category with its name, description and active flag.
                    Use this tool when the category id is already known; use listPostingCategories instead \
                    when browsing or filtering.
                    Preconditions: the posting category must exist.
                    Required inputs: postingCategoryId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no posting category exists for the supplied id.
                    """,
            tags = {"Posting Categories"})
    @ApiResponse(responseCode = "200", description = "Posting category returned")
    @ApiResponse(responseCode = "404", description = "Posting category not found")
    public ResponseEntity<PostingCategoryResponse> getPostingCategory(
            @Parameter(description = "Posting category identifier") @PathVariable UUID postingCategoryId) {
        log.info("Get posting category");
        PostingCategoryResponse response = postingCategoryService.getPostingCategory(postingCategoryId);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{postingCategoryId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:posting-category:edit"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.POSTING_CATEGORY_EDIT + "')")
    @Operation(
            operationId = "updatePostingCategory",
            summary = "Update Posting Category",
            description = """
                    Renames a posting category or changes its description; mapping keys under it are \
                    unaffected.
                    Use this tool to correct a category's name or description; do not use \
                    deactivatePostingCategory, which retires the category from future mapping use.
                    Preconditions: the posting category must exist, and a changed name must not collide with \
                    another category.
                    Required inputs: postingCategoryId (UUID) as a path parameter, categoryName (max 100 \
                    chars) and modifiedBy (max 50 chars); description is optional.
                    Emits an ACCOUNTING_POSTING_CATEGORY_UPDATE event.
                    Returns 404 when the category does not exist, and 400 when the new name already exists.
                    """,
            tags = {"Posting Categories"})
    @ApiResponse(responseCode = "200", description = "Posting category updated")
    @ApiResponse(responseCode = "404", description = "Posting category not found")
    @ApiResponse(responseCode = "400", description = "Invalid request or duplicate name")
    @EmitEvent(id = "ACCOUNTING_POSTING_CATEGORY_UPDATE", apiVersion = "1")
    public ResponseEntity<PostingCategoryResponse> updatePostingCategory(
            @Parameter(description = "Posting category identifier") @PathVariable UUID postingCategoryId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Replacement name and description for the posting category.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Rename category", value = """
                                                                    {"categoryName":"PAYMENT_TENDERS",
                                                                     "description":"Payment tender classifications",
                                                                     "modifiedBy":"jdoe"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    PostingCategoryUpdateRequest request) {
        log.info("Update posting category");
        PostingCategoryResponse response = postingCategoryService.updatePostingCategory(postingCategoryId, request);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:posting-category:view"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.POSTING_CATEGORY_VIEW + "')")
    @Operation(
            operationId = "listPostingCategories",
            summary = "List Posting Categories",
            description = """
                    Lists posting categories as a paginated projection, optionally filtered by active flag.
                    Use this tool when browsing the mapping taxonomy; do not use getPostingCategory, which \
                    fetches a single category by id.
                    Preconditions: none beyond the caller holding accounting:posting-category:view.
                    Required inputs: none; page defaults to 0, size to 20, sort to categoryName (an \
                    unsupported sort field silently falls back to categoryName), and isActive is an optional \
                    filter.
                    Emits an ACCOUNTING_POSTING_CATEGORY_LIST audit event; no state changes.
                    Returns 200 with an empty page when no categories exist.
                    """,
            tags = {"Posting Categories"})
    @ApiResponse(responseCode = "200", description = "Posting categories listed")
    @ApiResponse(responseCode = "403", description = "Forbidden")
    @EmitEvent(id = "ACCOUNTING_POSTING_CATEGORY_LIST", apiVersion = "1")
    public ResponseEntity<PostingCategoryListResponse> listPostingCategories(
            @Parameter(description = "Page index (0-based)") @PositiveOrZero @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @Positive @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "Sort field") @NotBlank @RequestParam(defaultValue = "categoryName") String sort,
            @Parameter(description = "Filter by active status") @RequestParam(required = false) Boolean isActive) {
        log.info("List posting categories");

        // Sanitize sort parameter - use default if not in allowed list
        String sanitizedSort = ALLOWED_SORT_FIELDS.contains(sort) ? sort : "categoryName";
        if (!sort.equals(sanitizedSort)) {
            log.warn("Invalid sort field '{}' requested, defaulting to '{}'", LogSanitizer.forLog(sort), sanitizedSort);
        }

        PostingCategoryListResponse response =
                postingCategoryService.listPostingCategories(page, size, sanitizedSort, isActive);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{postingCategoryId}/deactivate")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:posting-category:deactivate"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.POSTING_CATEGORY_DEACTIVATE + "')")
    @Operation(
            operationId = "deactivatePostingCategory",
            summary = "Deactivate Posting Category",
            description = """
                    Deactivates a posting category so it can no longer be used for new GL mappings; the \
                    record and its keys are retained.
                    Use this tool to retire an unused category; do not use deactivateMappingKey, which \
                    retires a single key inside a category.
                    Preconditions: the posting category must exist and have no active GL mappings attached.
                    Required inputs: postingCategoryId (UUID) as a path parameter; there is no request body.
                    Emits an ACCOUNTING_POSTING_CATEGORY_DEACTIVATE event.
                    Returns 404 when the category does not exist, 409 when active GL mappings still \
                    reference it, and 204 with no body on success.
                    """,
            tags = {"Posting Categories"})
    @ApiResponse(responseCode = "204", description = "Posting category deactivated")
    @ApiResponse(responseCode = "404", description = "Posting category not found")
    @ApiResponse(responseCode = "409", description = "Cannot deactivate - active mappings exist")
    @EmitEvent(id = "ACCOUNTING_POSTING_CATEGORY_DEACTIVATE", apiVersion = "1")
    public ResponseEntity<Void> deactivatePostingCategory(
            @Parameter(description = "Posting category identifier") @PathVariable UUID postingCategoryId) {
        log.info("Deactivate posting category");
        postingCategoryService.deactivatePostingCategory(postingCategoryId);
        return ResponseEntity.noContent().build();
    }
}
