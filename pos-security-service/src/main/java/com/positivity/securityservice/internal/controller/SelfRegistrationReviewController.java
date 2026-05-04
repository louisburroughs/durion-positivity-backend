package com.positivity.securityservice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.securityservice.internal.dto.ResolveSelfRegistrationReviewCaseRequest;
import com.positivity.securityservice.internal.dto.SelfRegistrationReviewCaseResponse;
import com.positivity.securityservice.internal.enums.SelfRegistrationCaseStatus;
import com.positivity.securityservice.internal.enums.SelfRegistrationCaseType;
import com.positivity.securityservice.service.SelfRegistrationReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Self-Registration Review API", description = "Administrative queue for blocked self-registration recovery and identity review cases")
@RestController
@RequestMapping("/v1/self-registration/review-cases")
@RequiredArgsConstructor
public class SelfRegistrationReviewController {

        private final SelfRegistrationReviewService selfRegistrationReviewService;

        @GetMapping
        @Operation(summary = "List self-registration review cases", description = "Returns blocked self-registration cases for recovery or identity review.")
        @ApiResponse(responseCode = "200", description = "Review cases returned successfully")
        @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth", scopes = {
                        "security:user_account_state:view" })
        @PreAuthorize("hasAuthority('security:user_account_state:view')")
        public ResponseEntity<List<SelfRegistrationReviewCaseResponse>> listCases(
                        @RequestParam(required = false) SelfRegistrationCaseStatus status,
                        @RequestParam(required = false) SelfRegistrationCaseType caseType) {
                return ResponseEntity.ok(selfRegistrationReviewService.listCases(status, caseType));
        }

        @GetMapping("/{caseId}")
        @Operation(summary = "Get a self-registration review case", description = "Returns the details of a blocked self-registration case.")
        @ApiResponse(responseCode = "200", description = "Review case returned successfully")
        @ApiResponse(responseCode = "404", description = "Review case not found")
        @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth", scopes = {
                        "security:user_account_state:view" })
        @PreAuthorize("hasAuthority('security:user_account_state:view')")
        public ResponseEntity<SelfRegistrationReviewCaseResponse> getCase(@PathVariable UUID caseId) {
                return ResponseEntity.ok(selfRegistrationReviewService.getCase(caseId));
        }

        @PostMapping("/{caseId}/resolve")
        @EmitEvent(id = "SECURITY_SELF_REGISTRATION_REVIEW_RESOLVE", apiVersion = "1")
        @Operation(summary = "Resolve a self-registration review case", description = "Marks a blocked self-registration case as resolved after recovery or manual review.")
        @ApiResponse(responseCode = "200", description = "Review case resolved successfully")
        @ApiResponse(responseCode = "404", description = "Review case not found")
        @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth", scopes = {
                        "security:user_account_state:manage" })
        @PreAuthorize("hasAuthority('security:user_account_state:manage')")
        public ResponseEntity<SelfRegistrationReviewCaseResponse> resolveCase(
                        @PathVariable UUID caseId,
                        @Valid @RequestBody ResolveSelfRegistrationReviewCaseRequest request,
                        @NonNull Principal principal) {
                return ResponseEntity
                                .ok(selfRegistrationReviewService.resolveCase(caseId, request, principal.getName()));
        }
}
