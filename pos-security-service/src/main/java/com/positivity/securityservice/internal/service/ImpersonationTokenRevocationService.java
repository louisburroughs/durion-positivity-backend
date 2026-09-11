package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.entity.ExtTenant;
import com.positivity.securityservice.internal.repository.ExtTenantRepository;
import com.positivity.securityservice.internal.security.service.JwtService;
import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantContext;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

/**
 * Ends a platform operator's live impersonation tokens wherever they are (ADR-0062 §7, WS2b-4).
 *
 * <p><b>Why this exists.</b> {@link JwtService#revokeAllTokensForUser} is keyed on the token's
 * {@code subject} and runs under the binding in force — which is how every ordinary token is
 * reached, because a login token is stored in the user's own tenant under the user's own username.
 * An impersonation token is neither: {@code PlatformImpersonationService} stores it in the
 * <em>target</em> tenant under the synthetic subject {@code support:<operator>@<slug>}. Disabling
 * the operator, expiring their account or credentials, or revoking their platform role therefore
 * left every token they had minted usable until its own 15-minute expiry. This service closes that
 * window: it sweeps every tenant the {@code ext_tenant} replica knows and revokes the rows whose
 * {@code impersonated_by_user_id} is the operator, the persisted link back to the human.
 *
 * <p><b>Why a sweep rather than a lookup.</b> Nothing records which tenants an operator has
 * visited, and a per-operator index of that would be a second thing to keep true. The tenant count
 * is small and the per-tenant query is indexed on {@code impersonated_by_user_id} (partial: the
 * column is {@code NULL} on every ordinary token), so a sweep is cheap and cannot miss a tenant.
 * The sweep is unconditional — an account-state or role change reaches this service whatever the
 * binding in force, and a user who has never minted a token simply deletes nothing.
 *
 * <p><b>Tenant rebinding.</b> The database session fixes its tenant when it opens (open-in-view is
 * off in this module), so the per-tenant work has to be a fresh transaction inside the rebind:
 * {@link JwtService#revokeImpersonationTokensMintedBy} is {@code REQUIRES_NEW} for exactly that,
 * and reaching it through the {@code JwtService} bean (not a self-call) is what makes the
 * propagation take effect. A failure for one tenant is logged and the sweep continues, so one
 * unreachable tenant cannot leave an operator's tokens live everywhere else.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImpersonationTokenRevocationService {

    private final ExtTenantRepository extTenantRepository;
    private final JwtService jwtService;

    /**
     * Revokes every impersonation token {@code operatorUserId} has minted, in every tenant.
     *
     * @param operatorUserId the platform operator's user id — the {@code uid} their tokens carry
     * @return how many token rows were revoked across all tenants
     */
    public int revokeForOperator(@NonNull UUID operatorUserId) {
        // The platform tenant is included even though it is never an impersonation target today
        // (the mint refuses it with TENANT_NOT_IMPERSONABLE): a sweep that depends on that refusal
        // staying true is a sweep that can silently start missing rows.
        Set<UUID> tenants = new LinkedHashSet<>();
        tenants.add(PlatformTenant.ID);
        extTenantRepository.findAll().stream().map(ExtTenant::getTenantId).forEach(tenants::add);

        int revoked = 0;
        for (UUID tenantId : tenants) {
            try {
                revoked += TenantContext.callAs(
                        tenantId, () -> jwtService.revokeImpersonationTokensMintedBy(operatorUserId));
            } catch (RuntimeException e) {
                log.error(
                        "Impersonation-token revocation failed for operator {} in tenant {}; continuing with the "
                                + "remaining tenants",
                        operatorUserId,
                        tenantId,
                        e);
            }
        }
        if (revoked > 0) {
            log.info(
                    "Revoked {} impersonation token(s) across {} tenant(s) for operator {}",
                    revoked,
                    tenants.size(),
                    operatorUserId);
        }
        return revoked;
    }
}
