package com.positivity.invoice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.invoice.internal.dto.BillingRulesDTO;
import com.positivity.invoice.internal.security.InvoicePermissions;
import com.positivity.invoice.internal.service.BillingRulesService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for billing rules management.
 * CAP:092 - Preferences & Billing Rules
 */
@RestController
@SecurityRequirement(
        name = "bearerAuth",
        scopes = {"invoice:billing-rules"})
@RequestMapping("/v1/billing/rules")
@Tag(name = "Billing Rules", description = "Manage billing rules for commercial accounts")
@PreAuthorize("hasAuthority('" + InvoicePermissions.BILLING_RULES + "')")
public class BillingRulesController {

    private static final Logger log = LoggerFactory.getLogger(BillingRulesController.class);

    // Pattern for valid UUID format to prevent injection attacks
    private static final Pattern VALID_UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final BillingRulesService billingRulesService;

    public BillingRulesController(@NonNull BillingRulesService billingRulesService) {
        this.billingRulesService = billingRulesService;
    }

    @GetMapping("/{partyId}")
    @EmitEvent(id = "BILLING_RULES_GET", apiVersion = "1")
    @Operation(operationId = "getBillingRules", summary = "Get Billing Rules for a Party", description = """
                    Returns the billing rules configured for a commercial account party: purchase-order requirement, \
                    payment terms code, invoice delivery method, and invoice grouping strategy.
                    Use this tool when the billing configuration of a known party is needed before invoicing; do not \
                    use upsertBillingRules, which creates or replaces the configuration.
                    Preconditions: a billing rules record must already exist for the party, created explicitly or \
                    defaulted when the commercial account was provisioned.
                    Required inputs: partyId (UUID) as a path parameter; there is no request body.
                    Emits a BILLING_RULES_GET audit event; no state changes — this is a read-only projection.
                    Returns 404 when no billing rules are configured for the party, and 400 with an empty body when \
                    partyId is not a well-formed UUID.
                    """)
    @ApiResponse(responseCode = "200", description = "Billing rules found")
    @ApiResponse(responseCode = "404", description = "No billing rules configured for this party")
    public ResponseEntity<BillingRulesDTO> getBillingRules(@PathVariable @NonNull String partyId) {
        // Validate partyId format
        if (!VALID_UUID_PATTERN.matcher(partyId).matches()) {
            log.warn("Invalid partyId format in getBillingRules");
            return ResponseEntity.badRequest().build();
        }

        log.debug("GET /v1/billing/rules/{}", partyId);

        return billingRulesService
                .getBillingRules(partyId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{partyId}")
    @EmitEvent(id = "BILLING_RULES_UPSERT", apiVersion = "1")
    @Operation(operationId = "upsertBillingRules", summary = "Create or Update Billing Rules", description = """
                    Creates or replaces the billing rules for a commercial account party in one idempotent upsert \
                    keyed on the path partyId, which overrides any partyId carried in the body.
                    Use this tool when configuring how a party is invoiced; use getBillingRules instead to read the \
                    current configuration without changing it.
                    Preconditions: paymentTermsCode must belong to the validated vocabulary (DUE_ON_RECEIPT, NET_10, \
                    NET_15, NET_30, NET_45, NET_60); a missing record is created and an existing one is updated in \
                    place.
                    Required inputs: partyId (UUID path), purchaseOrderRequired (boolean), paymentTermsCode, \
                    invoiceDeliveryMethod (EMAIL, PORTAL, MAIL) and invoiceGroupingStrategy (PER_WORKORDER, \
                    PER_VEHICLE, SINGLE_INVOICE); updatedBy is taken from the security context, never from the body.
                    Emits a BILLING_RULES_UPSERT event and publishes a billing-rules-updated notification; due dates \
                    of already-finalized invoices are never recomputed from a terms change.
                    Returns 201 when the record is created, 200 when an existing record is updated, and 400 with an \
                    empty body when partyId is not a well-formed UUID.
                    """)
    @ApiResponse(responseCode = "200", description = "Billing rules updated")
    @ApiResponse(responseCode = "201", description = "Billing rules created")
    @ApiResponse(responseCode = "400", description = "Invalid billing rules data")
    public ResponseEntity<BillingRulesDTO> upsertBillingRules(
            @PathVariable @NonNull String partyId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Billing rules configuration to store for the party.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Net-30 emailed per-workorder invoicing",
                                                            value = """
                                                                    {"partyId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "purchaseOrderRequired":true,
                                                                     "paymentTermsCode":"NET_30",
                                                                     "invoiceDeliveryMethod":"EMAIL",
                                                                     "invoiceGroupingStrategy":"PER_WORKORDER"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    BillingRulesDTO billingRulesDTO) {

        // Validate partyId format
        if (!VALID_UUID_PATTERN.matcher(partyId).matches()) {
            log.warn("Invalid partyId format in upsertBillingRules");
            return ResponseEntity.badRequest().build();
        }

        // Get userId from SecurityContext via service
        String userId = billingRulesService.getCurrentUsername();
        // Sanitize userId for logging (prevent log injection)
        String sanitizedUserId = sanitizeForLogging(userId);
        log.debug("PUT /v1/billing/rules/{} by user={}", partyId, sanitizedUserId);

        // Override partyId from path to ensure consistency
        billingRulesDTO.setPartyId(partyId);

        boolean isNew = billingRulesService.getBillingRules(partyId).isEmpty();
        BillingRulesDTO saved = billingRulesService.saveBillingRules(billingRulesDTO, userId);

        HttpStatus status = isNew ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(saved);
    }

    /**
     *
     * to prevent log injection attacks.
     */
    private String sanitizeForLogging(String input) {
        if (input == null) {
            return "null";
        }
        // Replace newlines, carriage returns, and other control characters
        return input.replaceAll("[\\r\\n\\t]", "_").replaceAll("[\\p{Cntrl}]", "");
    }
}
