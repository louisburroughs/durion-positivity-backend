package com.positivity.securityservice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.securityservice.internal.dto.ResolveSelfRegistrationReviewCaseRequest;
import com.positivity.securityservice.internal.dto.SelfRegistrationReviewCaseResponse;
import com.positivity.securityservice.internal.enums.SelfRegistrationCaseStatus;
import com.positivity.securityservice.internal.enums.SelfRegistrationCaseType;
import com.positivity.securityservice.internal.security.SecurityPermissions;
import com.positivity.securityservice.service.SelfRegistrationReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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

@Tag(
        name = "Self-Registration Review API",
        description = "Administrative queue for blocked self-registration recovery and identity review cases")
@RestController
@RequestMapping("/v1/self-registration/review-cases")
@RequiredArgsConstructor
public class SelfRegistrationReviewController {

    private final SelfRegistrationReviewService selfRegistrationReviewService;

    @GetMapping
    @Operation(
            operationId = "listSelfRegistrationReviewCases",
            summary = "List Self-Registration Review Cases",
            description = """
                    Returns blocked self-registration review cases, newest first, optionally filtered by status and \
                    case type.
                    Use this tool to work the recovery and identity-review queue; use \
                    getSelfRegistrationReviewCase instead for one known case.
                    Preconditions: the caller must hold security:user_account_state:view.
                    Required inputs: none are mandatory; status filters on OPEN or RESOLVED and caseType on \
                    ACCOUNT_RECOVERY or IDENTITY_REVIEW.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with an empty list when nothing matches, and 400 when a filter value is not a valid \
                    enum constant.
                    """)
    @ApiResponse(responseCode = "200", description = "Review cases returned successfully")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:user_account_state:view"})
    @PreAuthorize("hasAuthority('" + SecurityPermissions.USER_ACCOUNT_STATE_VIEW + "')")
    public ResponseEntity<List<SelfRegistrationReviewCaseResponse>> listCases(
            @RequestParam(required = false) SelfRegistrationCaseStatus status,
            @RequestParam(required = false) SelfRegistrationCaseType caseType) {
        return ResponseEntity.ok(selfRegistrationReviewService.listCases(status, caseType));
    }

    @GetMapping("/{caseId}")
    @Operation(
            operationId = "getSelfRegistrationReviewCase",
            summary = "Get One Self-Registration Review Case",
            description = """
                    Returns the full detail of one blocked self-registration case, including reason codes, CRM \
                    match summary, and any linked person or user ids.
                    Use this tool when the case id is known, typically from the referenceId of a 409 \
                    selfRegisterUser conflict; use listSelfRegistrationReviewCases instead to browse the queue.
                    Preconditions: the caller must hold security:user_account_state:view and the case must exist.
                    Required inputs: caseId (UUID) as a path parameter.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no review case exists for the supplied id.
                    """)
    @ApiResponse(responseCode = "200", description = "Review case returned successfully")
    @ApiResponse(responseCode = "404", description = "Review case not found")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:user_account_state:view"})
    @PreAuthorize("hasAuthority('" + SecurityPermissions.USER_ACCOUNT_STATE_VIEW + "')")
    public ResponseEntity<SelfRegistrationReviewCaseResponse> getCase(@PathVariable UUID caseId) {
        return ResponseEntity.ok(selfRegistrationReviewService.getCase(caseId));
    }

    @PostMapping("/{caseId}/resolve")
    @EmitEvent(id = "SECURITY_SELF_REGISTRATION_REVIEW_RESOLVE", apiVersion = "1")
    @Operation(
            operationId = "resolveSelfRegistrationReviewCase",
            summary = "Resolve a Self-Registration Review Case",
            description = """
                    Marks a blocked self-registration case RESOLVED, recording the resolving operator, timestamp, \
                    and resolution notes.
                    Use this tool after the underlying recovery or identity review is finished; do not use it to \
                    recover the account itself, which is done first with account-state endpoints such as \
                    enableUserAccount.
                    Preconditions: the caller must hold security:user_account_state:manage and be authenticated \
                    (the resolver is taken from the request principal); the case must exist, and re-resolving an \
                    already RESOLVED case overwrites the resolution fields.
                    Required inputs: caseId (UUID) as a path parameter and resolutionNotes, non-blank, in the body.
                    Emits a SECURITY_SELF_REGISTRATION_REVIEW_RESOLVE event.
                    Returns 404 when no review case exists for the supplied id.
                    """)
    @ApiResponse(responseCode = "200", description = "Review case resolved successfully")
    @ApiResponse(responseCode = "404", description = "Review case not found")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:user_account_state:manage"})
    @PreAuthorize("hasAuthority('" + SecurityPermissions.USER_ACCOUNT_STATE_MANAGE + "')")
    public ResponseEntity<SelfRegistrationReviewCaseResponse> resolveCase(
            @PathVariable UUID caseId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The resolution note recorded against the case.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Case resolution", value = """
                                                                    {"resolutionNotes":"Recovered existing disabled account and asked user to sign in."}
                                                                    """)))
                    @Valid
                    @RequestBody
                    ResolveSelfRegistrationReviewCaseRequest request,
            @NonNull Principal principal) {
        return ResponseEntity.ok(selfRegistrationReviewService.resolveCase(caseId, request, principal.getName()));
    }
}
