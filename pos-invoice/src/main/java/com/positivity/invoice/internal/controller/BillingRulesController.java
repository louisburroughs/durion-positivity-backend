package com.positivity.invoice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.invoice.internal.dto.BillingRulesDTO;
import com.positivity.invoice.service.BillingRulesService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.regex.Pattern;

/**
 * REST controller for billing rules management.
 * CAP:092 - Preferences & Billing Rules
 */
@RestController
@RequestMapping("/v1/billing/rules")
@Tag(name = "Billing Rules", description = "Manage billing rules for commercial accounts")
public class BillingRulesController {

    private static final Logger log = LoggerFactory.getLogger(BillingRulesController.class);

    // Pattern for valid UUID format to prevent injection attacks
    private static final Pattern VALID_UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final BillingRulesService billingRulesService;

    public BillingRulesController(@NonNull BillingRulesService billingRulesService) {
        this.billingRulesService = billingRulesService;
    }

    @GetMapping("/{partyId}")
    @EmitEvent(id = "BILLING_RULES_GET", apiVersion = "1")
    @Operation(summary = "Get billing rules for a party/customer", description = "Retrieve the current billing rules configuration for a commercial account")
    @ApiResponse(responseCode = "200", description = "Billing rules found")
    @ApiResponse(responseCode = "404", description = "No billing rules configured for this party")
    public ResponseEntity<BillingRulesDTO> getBillingRules(@PathVariable @NonNull String partyId) {
        // Validate partyId format
        if (!VALID_UUID_PATTERN.matcher(partyId).matches()) {
            log.warn("Invalid partyId format in getBillingRules");
            return ResponseEntity.badRequest().build();
        }

        log.debug("GET /v1/billing/rules/{}", partyId);

        return billingRulesService.getBillingRules(partyId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{partyId}")
    @EmitEvent(id = "BILLING_RULES_UPSERT", apiVersion = "1")
    @Operation(summary = "Create or update billing rules", description = "Idempotent upsert of billing rules for a commercial account")
    @ApiResponse(responseCode = "200", description = "Billing rules updated")
    @ApiResponse(responseCode = "201", description = "Billing rules created")
    @ApiResponse(responseCode = "400", description = "Invalid billing rules data")
    public ResponseEntity<BillingRulesDTO> upsertBillingRules(
            @PathVariable @NonNull String partyId,
            @Valid @RequestBody @NonNull BillingRulesDTO billingRulesDTO) {

        // Validate partyId format
        if (!VALID_UUID_PATTERN.matcher(partyId).matches()) {
            log.warn("Invalid partyId format in upsertBillingRules");
            return ResponseEntity.badRequest().build();
        }

        // Get userId from SecurityContext via service
        String userId = billingRulesService.getCurrentUserId();
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
