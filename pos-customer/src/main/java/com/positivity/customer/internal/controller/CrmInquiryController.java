package com.positivity.customer.internal.controller;

import com.positivity.customer.internal.dto.InquiryResponse;
import com.positivity.customer.internal.dto.PagedResponse;
import com.positivity.customer.internal.dto.SubmitInquiryRequest;
import com.positivity.customer.internal.enums.InquiryStatus;
import com.positivity.customer.internal.security.CrmPermissionRegistry;
import com.positivity.customer.service.InquiryService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Inbound inquiry triage and conversion, for authenticated staff (Story #1154). */
@Tag(name = "CRM Inquiries", description = "Inbound service and fleet-quote inquiries")
@RestController
@RequestMapping("/v1/crm/inquiries")
public class CrmInquiryController {

    private final InquiryService inquiryService;

    public CrmInquiryController(InquiryService inquiryService) {
        this.inquiryService = inquiryService;
    }

    @Operation(operationId = "listInquiries", summary = "List Inbound Inquiries", description = """
                    Returns inbound service and fleet-quote inquiries newest first, optionally filtered by \
                    lifecycle status.
                    Use this tool when triaging the inquiry queue; use getInquiry instead when the inquiry \
                    id is already known.
                    Preconditions: none; an empty page is returned when nothing matches.
                    Required inputs: none; status optionally filters on NEW, CONTACTED, CONVERTED, or \
                    CLOSED, page defaults to 0, and size defaults to 50 clamped between 1 and 200.
                    Emits a CRM_INQUIRY_LIST audit event; no state changes occur.
                    Returns 200 with an empty page rather than an error when no inquiry matches.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Inquiries returned",
                content = @Content(schema = @Schema(implementation = PagedResponse.class))),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @GetMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.INQUIRY_VIEW})
    @PreAuthorize("hasAuthority('crm:inquiry:view')")
    @EmitEvent(id = "CRM_INQUIRY_LIST", apiVersion = "1")
    public ResponseEntity<PagedResponse<InquiryResponse>> list(
            @RequestParam(name = "status", required = false) InquiryStatus status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        return ResponseEntity.ok(inquiryService.list(status, page, size));
    }

    @Operation(operationId = "getInquiry", summary = "Get Inquiry", description = """
                    Returns one inbound inquiry with its channel, audience type, contact details, interest \
                    fields, status, assignee, and any linked party.
                    Use this tool when the inquiry id is already known; use listInquiries instead to find \
                    inquiries by status.
                    Preconditions: the inquiry must exist.
                    Required inputs: inquiryId (UUID) as a path parameter; there is no request body.
                    Emits a CRM_INQUIRY_GET audit event; no state changes occur.
                    Returns 404 when no inquiry exists for the supplied inquiryId.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Inquiry returned",
                content = @Content(schema = @Schema(implementation = InquiryResponse.class))),
        @ApiResponse(responseCode = "404", description = "Inquiry not found", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @GetMapping("/{inquiryId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.INQUIRY_VIEW})
    @PreAuthorize("hasAuthority('crm:inquiry:view')")
    @EmitEvent(id = "CRM_INQUIRY_GET", apiVersion = "1")
    public ResponseEntity<InquiryResponse> get(@PathVariable UUID inquiryId) {
        return ResponseEntity.ok(inquiryService.get(inquiryId));
    }

    @Operation(operationId = "captureInquiry", summary = "Capture Staff-Taken Inquiry", description = """
                    Records an inbound inquiry taken by staff over phone, walk-in, email, or referral, \
                    creating it in NEW status and deliberately unassigned so it lands in the shared queue.
                    Use this tool for staff-entered inquiries on the authenticated API; do not use \
                    submitPublicInquiry, which is the anonymous rate-limited public web-form path.
                    Preconditions: none beyond authorization; no party needs to exist yet.
                    Required inputs: channel (WEB_FORM, PHONE, WALK_IN, EMAIL, REFERRAL, or CAMPAIGN), \
                    audienceType (COMMERCIAL or INDIVIDUAL), contactName, and at least one of email or \
                    phone; organizationName, vehicleOfInterest, serviceOfInterest, message, and \
                    campaignCode are optional.
                    Emits a CRM_INQUIRY_CAPTURE event and persists the inquiry.
                    Returns 400 when required fields are missing or when neither email nor phone is \
                    supplied, since an unreachable enquirer cannot be actioned.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Inquiry captured",
                content = @Content(schema = @Schema(implementation = InquiryResponse.class))),
        @ApiResponse(responseCode = "400", description = "Validation failed", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @PostMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.INQUIRY_MANAGE})
    @PreAuthorize("hasAuthority('crm:inquiry:manage')")
    @EmitEvent(id = "CRM_INQUIRY_CAPTURE", apiVersion = "1")
    public ResponseEntity<InquiryResponse> capture(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "The inquiry as taken by staff, including how it arrived and how to reach the enquirer.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Phone fleet inquiry", value = """
                                                                    {"channel":"PHONE",
                                                                     "audienceType":"COMMERCIAL",
                                                                     "contactName":"Dana Ortiz",
                                                                     "organizationName":"Ortiz Landscaping",
                                                                     "email":"dana@ortizlandscaping.example",
                                                                     "phone":"+1-512-555-0142",
                                                                     "serviceOfInterest":"Fleet maintenance quote for 6 trucks"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    SubmitInquiryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inquiryService.capture(request));
    }

    @Operation(operationId = "assignInquiry", summary = "Assign Inquiry", description = """
                    Assigns an inquiry to a staff member for follow-through, or returns it to the shared \
                    queue when assignedTo is omitted.
                    Use this tool when routing a triaged inquiry to an owner; do not use \
                    updateInquiryStatus, which moves the lifecycle state rather than ownership.
                    Preconditions: the inquiry must exist; assignment is allowed in any status.
                    Required inputs: inquiryId (UUID) as a path parameter; assignedTo is an optional query \
                    parameter whose omission unassigns the inquiry, and there is no request body.
                    Emits a CRM_INQUIRY_ASSIGN event; only the inquiry's assignee changes.
                    Returns 404 when no inquiry exists for the supplied inquiryId.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Inquiry assigned",
                content = @Content(schema = @Schema(implementation = InquiryResponse.class))),
        @ApiResponse(responseCode = "404", description = "Inquiry not found", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @PutMapping("/{inquiryId}/assignee")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.INQUIRY_MANAGE})
    @PreAuthorize("hasAuthority('crm:inquiry:manage')")
    @EmitEvent(id = "CRM_INQUIRY_ASSIGN", apiVersion = "1")
    public ResponseEntity<InquiryResponse> assign(
            @PathVariable UUID inquiryId, @RequestParam(name = "assignedTo", required = false) String assignedTo) {
        return ResponseEntity.ok(inquiryService.assign(inquiryId, assignedTo));
    }

    @Operation(operationId = "updateInquiryStatus", summary = "Update Inquiry Status", description = """
                    Moves an inquiry through its lifecycle to CONTACTED or CLOSED, optionally recording a \
                    resolution note.
                    Use this tool for ordinary status progression; do not use it to reach CONVERTED, which \
                    is only reachable through convertInquiry because conversion creates or links a party.
                    Preconditions: the inquiry must exist and the transition must be legal — NEW may move \
                    to CONTACTED or CLOSED, CONTACTED may move to CLOSED, and CONVERTED and CLOSED are \
                    terminal.
                    Required inputs: inquiryId (UUID) as a path parameter and status (CONTACTED or CLOSED) \
                    as a required query parameter; resolutionNote is optional and there is no request body.
                    Emits a CRM_INQUIRY_STATUS_UPDATE event; only the inquiry record changes.
                    Returns 404 when the inquiry does not exist, and 422 when CONVERTED is requested \
                    directly or the transition is illegal from the current status.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Status updated",
                content = @Content(schema = @Schema(implementation = InquiryResponse.class))),
        @ApiResponse(responseCode = "404", description = "Inquiry not found", content = @Content),
        @ApiResponse(
                responseCode = "422",
                description = "Illegal transition, or CONVERTED was requested directly",
                content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @PutMapping("/{inquiryId}/status")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.INQUIRY_MANAGE})
    @PreAuthorize("hasAuthority('crm:inquiry:manage')")
    @EmitEvent(id = "CRM_INQUIRY_STATUS_UPDATE", apiVersion = "1")
    public ResponseEntity<InquiryResponse> updateStatus(
            @PathVariable UUID inquiryId,
            @RequestParam(name = "status") InquiryStatus status,
            @RequestParam(name = "resolutionNote", required = false) String resolutionNote) {
        return ResponseEntity.ok(inquiryService.updateStatus(inquiryId, status, resolutionNote));
    }

    @Operation(operationId = "convertInquiry", summary = "Convert Inquiry To Party", description = """
                    Converts an inquiry into CRM state: a commercial inquiry with no existingPartyId creates \
                    a new commercial party in lifecycle stage PROSPECT, while supplying existingPartyId \
                    links the inquiry to that party instead; either way the inquiry becomes CONVERTED.
                    Use this tool when an enquirer becomes a prospect or is recognized as an existing \
                    customer; do not use updateInquiryStatus, which rejects CONVERTED as a direct status \
                    change.
                    Preconditions: the inquiry must exist in an open status (NEW or CONTACTED); an \
                    INDIVIDUAL-audience inquiry can only be converted by linking an existing party, because \
                    creating a person party requires a verified identity in pos-people-contact.
                    Required inputs: inquiryId (UUID) as a path parameter; existingPartyId (UUID) is an \
                    optional query parameter naming a known party to link, and there is no request body.
                    Emits a CRM_INQUIRY_CONVERT event; a new PROSPECT commercial party may be created and \
                    the inquiry is stamped with the resulting partyId.
                    Returns 404 when the inquiry or the supplied existingPartyId cannot be found, and 422 \
                    when the inquiry is already CONVERTED or CLOSED, or when an individual inquiry has no \
                    party to link.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Inquiry converted",
                content = @Content(schema = @Schema(implementation = InquiryResponse.class))),
        @ApiResponse(responseCode = "404", description = "Inquiry or party not found", content = @Content),
        @ApiResponse(
                responseCode = "422",
                description = "Illegal transition, or an individual inquiry with no party to link",
                content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @PostMapping("/{inquiryId}/convert")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.INQUIRY_MANAGE})
    @PreAuthorize("hasAuthority('crm:inquiry:manage')")
    @EmitEvent(id = "CRM_INQUIRY_CONVERT", apiVersion = "1")
    public ResponseEntity<InquiryResponse> convert(
            @PathVariable UUID inquiryId,
            @RequestParam(name = "existingPartyId", required = false) UUID existingPartyId) {
        return ResponseEntity.ok(inquiryService.convert(inquiryId, existingPartyId));
    }
}
