package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.PostingCategoryCreateRequest;
import com.positivity.accounting.internal.dto.PostingCategoryListResponse;
import com.positivity.accounting.internal.dto.PostingCategoryResponse;
import com.positivity.accounting.internal.dto.PostingCategoryUpdateRequest;
import com.positivity.accounting.service.PostingCategoryService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST Controller for Posting Category management.
 */
@RestController
@RequestMapping("/v1/accounting/posting-categories")
@Tag(name = "Posting Categories", description = "Manage posting categories for GL mapping taxonomy")
public class PostingCategoryController {

        private static final Logger log = LoggerFactory.getLogger(PostingCategoryController.class);

        private final PostingCategoryService postingCategoryService;

        public PostingCategoryController(PostingCategoryService postingCategoryService) {
                this.postingCategoryService = postingCategoryService;
        }

        @PostMapping
        @PreAuthorize("hasAuthority('accounting:posting-category:create')")
        @Operation(summary = "Create posting category", description = "Create a new posting category.")
        @ApiResponse(responseCode = "201", description = "Posting category created")
        @ApiResponse(responseCode = "400", description = "Invalid request or duplicate name")
        @EmitEvent(id = "ACCOUNTING_POSTING_CATEGORY_CREATE", apiVersion = "1")
        public ResponseEntity<PostingCategoryResponse> createPostingCategory(
                        @Valid @RequestBody PostingCategoryCreateRequest request) {
                log.info("Create posting category request: {}", request.getCategoryName());
                PostingCategoryResponse response = postingCategoryService.createPostingCategory(request);
                return ResponseEntity.status(HttpStatus.CREATED).body(response);
        }

        @GetMapping("/{postingCategoryId}")
        @PreAuthorize("hasAuthority('accounting:posting-category:view')")
        @Operation(summary = "Get posting category", description = "Retrieve a posting category by identifier.")
        @ApiResponse(responseCode = "200", description = "Posting category returned")
        @ApiResponse(responseCode = "404", description = "Posting category not found")
        public ResponseEntity<PostingCategoryResponse> getPostingCategory(
                        @Parameter(description = "Posting category identifier") @PathVariable UUID postingCategoryId) {
                log.info("Get posting category: {}", postingCategoryId);
                PostingCategoryResponse response = postingCategoryService.getPostingCategory(postingCategoryId);
                return ResponseEntity.ok(response);
        }

        @PutMapping("/{postingCategoryId}")
        @PreAuthorize("hasAuthority('accounting:posting-category:edit')")
        @Operation(summary = "Update posting category", description = "Update an existing posting category.")
        @ApiResponse(responseCode = "200", description = "Posting category updated")
        @ApiResponse(responseCode = "404", description = "Posting category not found")
        @ApiResponse(responseCode = "400", description = "Invalid request or duplicate name")
        @EmitEvent(id = "ACCOUNTING_POSTING_CATEGORY_UPDATE", apiVersion = "1")
        public ResponseEntity<PostingCategoryResponse> updatePostingCategory(
                        @Parameter(description = "Posting category identifier") @PathVariable UUID postingCategoryId,
                        @Valid @RequestBody PostingCategoryUpdateRequest request) {
                log.info("Update posting category: {}", postingCategoryId);
                PostingCategoryResponse response = postingCategoryService.updatePostingCategory(postingCategoryId,
                                request);
                return ResponseEntity.ok(response);
        }

        @GetMapping
        @PreAuthorize("hasAuthority('accounting:posting-category:view')")
        @Operation(summary = "List posting categories", description = "Retrieve paginated posting categories.")
        @ApiResponse(responseCode = "200", description = "Posting categories listed")
        @ApiResponse(responseCode = "403", description = "Forbidden")
        @EmitEvent(id = "ACCOUNTING_POSTING_CATEGORY_LIST", apiVersion = "1")
        public ResponseEntity<PostingCategoryListResponse> listPostingCategories(
                        @Parameter(description = "Page index (0-based)") @RequestParam(defaultValue = "0") int page,
                        @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size,
                        @Parameter(description = "Sort field") @RequestParam(defaultValue = "categoryName") String sort,
                        @Parameter(description = "Filter by active status") @RequestParam(required = false) Boolean isActive) {
                log.info("List posting categories: page={}, size={}, sort={}, isActive={}", page, size, sort, isActive);
                PostingCategoryListResponse response = postingCategoryService.listPostingCategories(page, size, sort,
                                isActive);
                return ResponseEntity.ok(response);
        }

        @PostMapping("/{postingCategoryId}/deactivate")
        @PreAuthorize("hasAuthority('accounting:posting-category:deactivate')")
        @Operation(summary = "Deactivate posting category", description = "Deactivate a posting category.")
        @ApiResponse(responseCode = "204", description = "Posting category deactivated")
        @ApiResponse(responseCode = "404", description = "Posting category not found")
        @ApiResponse(responseCode = "409", description = "Cannot deactivate - active mappings exist")
        @EmitEvent(id = "ACCOUNTING_POSTING_CATEGORY_DEACTIVATE", apiVersion = "1")
        public ResponseEntity<Void> deactivatePostingCategory(
                        @Parameter(description = "Posting category identifier") @PathVariable UUID postingCategoryId) {
                log.info("Deactivate posting category: {}", postingCategoryId);
                postingCategoryService.deactivatePostingCategory(postingCategoryId);
                return ResponseEntity.noContent().build();
        }
}
