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

    @Operation(summary = "List inquiries", description = "Inbound inquiries, newest first")
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

    @Operation(summary = "Get inquiry", description = "Retrieve a single inquiry")
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

    @Operation(summary = "Capture inquiry", description = "Record an inquiry taken by phone, walk-in, or referral")
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
    public ResponseEntity<InquiryResponse> capture(@Valid @RequestBody SubmitInquiryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inquiryService.capture(request));
    }

    @Operation(summary = "Assign inquiry", description = "Assign or unassign an inquiry")
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

    @Operation(
            summary = "Update inquiry status",
            description = "Move an inquiry to CONTACTED or CLOSED. CONVERTED is reached by converting it.")
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

    @Operation(
            summary = "Convert inquiry",
            description =
                    "Turn the inquiry into a PROSPECT party, or link it to an existing party when the enquirer is already known")
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
