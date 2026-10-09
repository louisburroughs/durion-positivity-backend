package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.InformationReturnFormsResponse;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.InformationReturnFormsService;
import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The information-return forms front door (CAP:550 #2615; ADR-0071, AW59): pos-tax's configured forms, boxes and
 * payee-id schemes for the tenant's tax country, so the vendor AP settings screen can offer them. People never call
 * pos-tax directly. No location is taken or reached (ADR-0061 does not apply).
 */
@RestController
@RequestMapping("/v1/accounting/information-return-forms")
@RequiredArgsConstructor
@Tag(
        name = "Vendor Directory API",
        description = "Vendors from accounting's copy of the pos-supplier vendor master, and their AP settings")
public class InformationReturnFormsController {

    private final InformationReturnFormsService informationReturnForms;

    @GetMapping
    @EmitEvent(id = "ACCOUNTING_INFORMATION_RETURN_FORMS_VIEW", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:ap:view"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.AP_VIEW + "')")
    @Operation(
            operationId = "listInformationReturnForms",
            summary = "List Information-Return Forms",
            description = """
                    Returns the information-return forms configured for the tenant's tax country \
                    (accounting.tax.country), each with its boxes and the payee-id schemes a payee may be reported \
                    under, relayed from pos-tax's configuration.
                    Use this tool to fill the form, box and scheme pickers of a vendor's information-return flag; \
                    do not use it to make a vendor reportable, use setVendorApSettings instead.
                    Preconditions: the caller holds accounting:ap:view; the values are placeholders held for expert \
                    advice, so source is STUB.
                    Required inputs: none; the country is the deployment's tax country, never a parameter.
                    Emits an ACCOUNTING_INFORMATION_RETURN_FORMS_VIEW audit event; no state changes.
                    Returns 200 with an empty forms list when the country configures none, and 503 \
                    SERVICE_UNAVAILABLE with Retry-After when pos-tax cannot answer.
                    """,
            tags = {"Vendor Directory API"})
    @ApiResponse(
            responseCode = "200",
            description = "The configured forms (empty when the tax country configures none)",
            content = @Content(schema = @Schema(implementation = InformationReturnFormsResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "FORBIDDEN without accounting:ap:view",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "503",
            description = "SERVICE_UNAVAILABLE: pos-tax is unreachable, failing or refusing the read (any 4xx or 5xx;"
                    + " the country is the server's own setting, so nothing is relayed); retry after the"
                    + " Retry-After interval",
            headers =
                    @Header(
                            name = "Retry-After",
                            description = "Seconds to wait before retrying",
                            schema = @Schema(type = "integer")),
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<InformationReturnFormsResponse> listInformationReturnForms() {
        return ResponseEntity.ok(informationReturnForms.forms());
    }
}
