package com.positivity.securityservice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.securityservice.internal.dto.TenantSearchResponse;
import com.positivity.securityservice.internal.service.TenantSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The organization directory the login form searches (ADR-0062 §3).
 *
 * <p>Anonymous by necessity: a user picks their organization before anything can bind a tenant, and
 * a slug is an identifier they do not know. It sits under {@code /v1/auth/**} so the gateway's
 * public-auth prefix already bypasses JWT validation for it.
 */
@Tag(name = "Auth API", description = "User-facing authentication endpoints")
@RestController
@RequestMapping("/v1/auth")
@RequiredArgsConstructor
public class TenantSearchController {

    private final TenantSearchService tenantSearchService;

    @Operation(operationId = "searchTenants", summary = "Search Organizations for the Login Form", description = """
            Returns the organizations whose name starts with the query, or one of whose words does, so a user can \
            pick theirs at sign-in instead of typing a tenant slug.
            Use this tool to populate the login form's organization field; do not use it to enumerate tenants, \
            which the result cap, the minimum query length and the prefix-only matching all exist to limit, and do \
            not use it to check whether an organization exists before logging in — loginUser answers the same 401 \
            either way.
            Preconditions: none; the endpoint is anonymous.
            Required inputs: q, the text the user has typed so far. A q shorter than the configured minimum \
            (3 characters by default) is not searched and answers an empty list, which is the normal state while \
            someone is still typing.
            Emits a SECURITY_TENANT_SEARCH event.
            Returns 200 with at most 10 ACTIVE organizations, each carrying only its display name and the slug to \
            submit, and 404 when the directory is switched off, in which case the form asks for the slug instead.
            """)
    @ApiResponse(responseCode = "200", description = "Matching organizations, possibly none")
    @ApiResponse(responseCode = "404", description = "Organization search is disabled in this deployment")
    @EmitEvent(id = "SECURITY_TENANT_SEARCH", apiVersion = "1")
    @PreAuthorize("permitAll()")
    @GetMapping("/tenants")
    public ResponseEntity<List<TenantSearchResponse>> search(
            @Parameter(description = "Text the user has typed so far") @RequestParam(name = "q", required = false)
                    String q) {
        if (!tenantSearchService.isEnabled()) {
            // 404, not 403: a disabled directory does not exist as far as a caller is concerned,
            // and the login form falls back to asking for the slug.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return ResponseEntity.ok(tenantSearchService.search(q));
    }
}
