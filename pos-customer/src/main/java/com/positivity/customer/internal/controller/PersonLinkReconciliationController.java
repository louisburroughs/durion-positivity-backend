package com.positivity.customer.internal.controller;

import com.positivity.customer.internal.security.CrmPermissionRegistry;
import com.positivity.customer.internal.service.PersonLinkReconciliationService;
import com.positivity.customer.internal.service.PersonLinkReconciliationService.PersonLinkReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only person-link reconciliation (ADR-0015 I1). Reports any
 * {@code person_party.person_id} that does not resolve to a pos-people person,
 * the integrity precondition for demoting person_party to a thin link (Phase 3).
 */
@Tag(name = "CRM Person-Link Reconciliation", description = "Verify person_party links resolve in pos-people")
@RestController
@RequestMapping("/v1/crm/admin/person-links")
public class PersonLinkReconciliationController {

    private final PersonLinkReconciliationService reconciliationService;

    public PersonLinkReconciliationController(PersonLinkReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @Operation(operationId = "reconcilePersonLinks", summary = "Reconcile Person Links", description = """
                    Produces an integrity report verifying that every person_party.person_id in this module \
                    resolves to a canonical person in pos-people, the precondition for keeping person_party \
                    as a thin link under ADR-0015.
                    Use this tool for admin integrity checks after imports or migrations; do not use \
                    searchPersons, which looks up individual customers rather than auditing link health.
                    Preconditions: pos-people must be reachable to resolve the links being verified.
                    Required inputs: none; there are no parameters and no request body.
                    No events are emitted and no state changes; the report is computed on demand and \
                    nothing is repaired automatically.
                    Returns 200 with the reconciliation report, including any person ids that failed to \
                    resolve.
                    """)
    @ApiResponse(responseCode = "200", description = "Reconciliation report returned")
    @GetMapping("/reconcile")
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_VIEW + "')")
    public ResponseEntity<PersonLinkReport> reconcile() {
        return ResponseEntity.ok(reconciliationService.reconcile());
    }
}
