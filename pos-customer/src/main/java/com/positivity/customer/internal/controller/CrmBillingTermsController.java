package com.positivity.customer.internal.controller;

import com.positivity.customer.internal.dto.BillingTermsRef;
import com.positivity.customer.internal.security.CrmPermissionRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the reference list of available billing term options.
 *
 * <p>
 * This is a static reference endpoint intended to populate billing-term
 * dropdowns when creating or editing a commercial account. The list is stable
 * and does not vary per party or caller.
 */
@Tag(name = "CRM Accounts", description = "Account tier management, party creation, search, merge, contacts, preferences, and vehicle operations")
@RestController
@RequestMapping("/v1/crm")
public class CrmBillingTermsController {

  private static final List<BillingTermsRef> BILLING_TERMS = List.of(
      BillingTermsRef.builder().code("NET_30").label("Net 30").netDays(30).build(),
      BillingTermsRef.builder().code("NET_60").label("Net 60").netDays(60).build(),
      BillingTermsRef.builder().code("NET_90").label("Net 90").netDays(90).build(),
      BillingTermsRef.builder().code("COD").label("Cash on Delivery").netDays(0).build(),
      BillingTermsRef.builder().code("PREPAID").label("Prepaid").netDays(-1).build());

  @Operation(summary = "List billing terms", description = "Returns the reference list of all available billing terms. "
      + "This is a static reference endpoint; it does not vary per party or account.")
  @ApiResponse(responseCode = "200", description = "Billing terms retrieved successfully")
  @GetMapping("/billing-terms")
  @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_VIEW + "')")
  public ResponseEntity<List<BillingTermsRef>> listBillingTerms() {
    return ResponseEntity.ok(BILLING_TERMS);
  }
}
