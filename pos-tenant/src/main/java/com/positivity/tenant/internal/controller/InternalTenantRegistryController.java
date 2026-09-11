package com.positivity.tenant.internal.controller;

import com.positivity.domainevents.tenant.TenantProjectionV1;
import com.positivity.tenant.internal.dto.TenantResponse;
import com.positivity.tenant.internal.enums.TenantStatus;
import com.positivity.tenant.internal.service.TenantService;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service view of the registry for {@code pos-tenancy-common}'s {@code
 * RemoteTenantRegistry} (plan WS4-2, decided 2026-09-10): the tenants in a status, as the public
 * {@link TenantProjectionV1} projection. Not reached through the gateway and absent from the
 * OpenAPI document; the caller proves itself with the shared secret {@code
 * TenantRegistrySecretFilter} checks, which is the authentication {@code isAuthenticated()} sees.
 *
 * <p>Every registry row belongs to the platform tenant, and this path is unenforced for {@code
 * TenantContextFilter} ({@code pos.tenancy.unenforced-paths}) because the caller carries no
 * tenant; {@code TenantRegistrySecretFilter} binds the platform tenant at the request edge once
 * the secret matches, and this controller only reads the binding.
 */
@Hidden
@RestController
@RequestMapping(InternalTenantRegistryController.PATH)
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class InternalTenantRegistryController {

    public static final String PATH = "/internal/v1/tenants";

    private final TenantService tenantService;

    /** Tenants in {@code status} ({@code ACTIVE} by default), oldest first. */
    @GetMapping
    public ResponseEntity<List<TenantProjectionV1>> list(
            @RequestParam(required = false, defaultValue = "ACTIVE") TenantStatus status) {
        List<TenantResponse> tenants = tenantService.list(status);
        return ResponseEntity.ok(tenants.stream()
                .map(tenant -> new TenantProjectionV1(
                        tenant.getId(),
                        tenant.getSlug(),
                        tenant.getDisplayName(),
                        tenant.getStatus().name()))
                .toList());
    }
}
