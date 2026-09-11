package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.config.AuditEventService;
import com.positivity.securityservice.internal.domain.ReservedRoles;
import com.positivity.securityservice.internal.dto.AuditLogEventRequest;
import com.positivity.securityservice.internal.entity.ExtTenant;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.exception.PlatformTenantRequiredException;
import com.positivity.securityservice.internal.exception.TenantNotFoundException;
import com.positivity.securityservice.internal.exception.TenantNotImpersonableException;
import com.positivity.securityservice.internal.exception.UserNotFoundException;
import com.positivity.securityservice.internal.repository.ExtTenantRepository;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.internal.security.service.JwtService;
import com.positivity.securityservice.internal.security.service.JwtService.IssuedImpersonationToken;
import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.replica.TenantProjectionEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Platform support access (ADR-0062 §7, plan WS2b-4, decided 2026-09-10): an impersonation token,
 * never a cross-tenant role. A platform operator holding {@code platform:tenant:impersonate},
 * bound to the platform tenant, {@link #issue issues} themselves a 15-minute, no-refresh access
 * token with {@code tid} = the target tenant, {@code act} = the operator and the target tenant's
 * fixed {@code SUPPORT} role as its authorities. The gateway and every module treat it as any
 * other token: {@code tid} becomes {@code X-Tenant-Id}, {@code perm_bits} becomes the caller's
 * authorities, so the operator reads what the tenant's own {@code SUPPORT} role may read — capped
 * by the read-only ceiling ({@code SupportReadOnlyCeiling}) at mint time, because a template
 * role's grants stay editable and a widened {@code SUPPORT} must never yield a write-capable
 * token; grants dropped by the ceiling are logged and audited ({@code droppedGrants}).
 *
 * <p>Refusals: 403 {@code PLATFORM_TENANT_REQUIRED} under any binding but the platform tenant's;
 * 404 {@code TENANT_NOT_FOUND} when the target is not in the {@code ext_tenant} replica; 409
 * {@code TENANT_NOT_IMPERSONABLE} when it is not {@code ACTIVE} or holds no {@code SUPPORT} role
 * yet, or is the platform tenant itself (the operator is already there).
 *
 * <p>Tenant bindings: the guard and the operator lookup run under the caller's platform binding;
 * the role check and the mint run under the target tenant's binding
 * ({@link TenantContext#callAs}), so the token row and the resolved grants are that tenant's.
 * Both rebind <em>around</em> a transaction ({@link BoundOperations}) because the Hibernate session
 * fixes its tenant at open time (open-in-view is off in this module).
 *
 * <p>Audit: one {@code PlatformImpersonationTokenIssued} event in the target tenant (so the
 * tenant's own audit log shows who read its data and until when) and one in the platform tenant
 * (the operator-side ledger), each written after the mint committed and in its own transaction,
 * plus an INFO log line. The token itself is never logged or stored
 * beyond the ordinary {@code jwt_token} row.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformImpersonationService {

    /**
     * The template role an impersonation token carries; seeded by
     * {@code R__seed_reference_security.sql} and never assignable to a user ({@link ReservedRoles}).
     */
    public static final String SUPPORT_ROLE = ReservedRoles.SUPPORT;

    /** Audit event type written on every issuance. */
    public static final String AUDIT_EVENT_TYPE = "PlatformImpersonationTokenIssued";

    /** Length of {@code jwt_token.subject} ({@code V1__baseline_security_service.sql}). */
    static final int MAX_SUBJECT_LENGTH = 255;

    private static final String SUBJECT_PREFIX = "support:";
    private static final String SUBJECT_SEPARATOR = "@";

    private final ExtTenantRepository extTenantRepository;
    private final BoundOperations boundOperations;

    /** What the operator receives, exactly once. */
    public record IssuedToken(
            @NonNull String token,
            @NonNull Instant expiresAt,
            @NonNull UUID tenantId,
            @NonNull String tenantSlug) {}

    /**
     * Mints an impersonation token for {@code tenantId} on behalf of the authenticated operator.
     *
     * @param tenantId      the target tenant
     * @param correlationId the request's correlation id, recorded on the audit events; may be null
     * @throws PlatformTenantRequiredException when the caller is not bound to the platform tenant
     * @throws TenantNotFoundException when the tenant is not in the replica
     * @throws TenantNotImpersonableException when the tenant is not ACTIVE or has no SUPPORT role
     * @throws UserNotFoundException when the operator has no user row in the platform tenant
     */
    public @NonNull IssuedToken issue(@NonNull UUID tenantId, @Nullable String correlationId) {
        UUID bound = TenantContext.current().orElse(null);
        if (!PlatformTenant.isPlatform(bound)) {
            throw new PlatformTenantRequiredException(bound);
        }
        if (PlatformTenant.isPlatform(tenantId)) {
            throw new TenantNotImpersonableException(
                    tenantId, "it is the platform tenant; the caller is already bound to it");
        }
        ExtTenant target =
                extTenantRepository.findById(tenantId).orElseThrow(() -> new TenantNotFoundException(tenantId));
        if (!TenantProjectionEvent.STATUS_ACTIVE.equals(target.getStatus())) {
            throw new TenantNotImpersonableException(tenantId, "its status is " + target.getStatus());
        }

        String operatorUsername = CurrentActor.resolve();
        User operator = boundOperations.operator(operatorUsername);
        String subject = syntheticSubject(operatorUsername, target.getSlug());

        IssuedImpersonationToken issued = TenantContext.callAs(
                tenantId, () -> boundOperations.mint(tenantId, subject, operator.getId(), operatorUsername));

        if (!issued.droppedAuthorities().isEmpty()) {
            // The tenant widened its SUPPORT role past the read-only ceiling; the token stayed
            // read-only (SupportReadOnlyCeiling), and the widening is worth a look.
            log.warn(
                    "Impersonation token for tenant {} ({}) minted without {} grant(s) its SUPPORT role carries "
                            + "beyond the read-only ceiling: {}",
                    tenantId,
                    target.getSlug(),
                    issued.droppedAuthorities().size(),
                    issued.droppedAuthorities());
        }

        // Audited after the mint committed, each event in its own transaction, so a failing audit
        // store can neither withhold the token nor poison the mint's transaction: the tenant-side
        // event under the target binding, the operator-side event under the platform binding.
        AuditLogEventRequest auditRequest = auditRequest(tenantId, operatorUsername, subject, issued, correlationId);
        TenantContext.runAs(tenantId, () -> boundOperations.audit(auditRequest));
        boundOperations.audit(auditRequest);

        log.info(
                "Impersonation token issued: operator={} ({}) tenant={} ({}) subject={} jti={} expiresAt={} correlationId={}",
                operatorUsername,
                operator.getId(),
                tenantId,
                target.getSlug(),
                subject,
                issued.jti(),
                issued.expiresAt(),
                correlationId);
        return new IssuedToken(issued.token(), issued.expiresAt(), tenantId, target.getSlug());
    }

    /**
     * {@code support:<operator>@<slug>}, bounded to {@link #MAX_SUBJECT_LENGTH} — {@code
     * jwt_token.subject} is {@code varchar(255)} and a username may already be 255 characters. When
     * the full form does not fit, the operator part is cut and a stable eight-hex-digit SHA-256
     * fingerprint of the whole username is appended, so the subject stays unique per operator,
     * deterministic across mints, and still ends in {@code @<slug>}.
     */
    static @NonNull String syntheticSubject(@NonNull String operatorUsername, @NonNull String tenantSlug) {
        String full = SUBJECT_PREFIX + operatorUsername + SUBJECT_SEPARATOR + tenantSlug;
        if (full.length() <= MAX_SUBJECT_LENGTH) {
            return full;
        }
        String fingerprint = "~" + sha256Hex(operatorUsername).substring(0, 8);
        int room = MAX_SUBJECT_LENGTH
                - SUBJECT_PREFIX.length()
                - fingerprint.length()
                - SUBJECT_SEPARATOR.length()
                - tenantSlug.length();
        return SUBJECT_PREFIX + operatorUsername.substring(0, room) + fingerprint + SUBJECT_SEPARATOR + tenantSlug;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static AuditLogEventRequest auditRequest(
            UUID tenantId,
            String operatorUsername,
            String subject,
            IssuedImpersonationToken issued,
            @Nullable String correlationId) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("operator", operatorUsername);
        context.put("subject", subject);
        context.put("jti", issued.jti());
        context.put("expiresAt", issued.expiresAt().toString());
        context.put("role", SUPPORT_ROLE);
        if (!issued.droppedAuthorities().isEmpty()) {
            context.put("droppedGrants", List.copyOf(issued.droppedAuthorities()));
        }
        if (correlationId != null && !correlationId.isBlank()) {
            context.put("correlationId", correlationId);
        }
        // The audit service requires both values; there is no "before" state for an issuance.
        return new AuditLogEventRequest(
                AUDIT_EVENT_TYPE,
                operatorUsername,
                tenantId.toString(),
                "Tenant",
                "",
                "expiresAt=" + issued.expiresAt(),
                context);
    }

    /**
     * The transactional halves, a separate bean so the {@code @Transactional} proxy is honoured
     * when the outer service calls them from inside a tenant rebind.
     */
    @Slf4j
    @Component
    @RequiredArgsConstructor
    public static class BoundOperations {

        private final UserRepository userRepository;
        private final RoleRepository roleRepository;
        private final JwtService jwtService;
        private final ObjectProvider<AuditEventService> auditEventService;

        /** Under the platform binding: the operator is a platform-tenant user. */
        @Transactional(readOnly = true)
        public @NonNull User operator(@NonNull String username) {
            return userRepository
                    .findByUsername(username)
                    .orElseThrow(
                            () -> new UserNotFoundException("Operator not found in the platform tenant: " + username));
        }

        /** Under the target tenant's binding: checks the role, then mints and stores the token. */
        @Transactional
        public @NonNull IssuedImpersonationToken mint(
                @NonNull UUID tenantId,
                @NonNull String subject,
                @NonNull UUID operatorUserId,
                @NonNull String operatorUsername) {
            if (!roleRepository.existsByName(SUPPORT_ROLE)) {
                throw new TenantNotImpersonableException(
                        tenantId, "it has no " + SUPPORT_ROLE + " role yet (template reconcile pending)");
            }
            return jwtService.generateImpersonationToken(
                    subject, operatorUserId, operatorUsername, Set.of(SUPPORT_ROLE));
        }

        /**
         * Writes one audit event under the binding in force. Deliberately not transactional: the
         * audit service opens its own transaction, so a refusal there rolls back nothing else.
         */
        public void audit(@NonNull AuditLogEventRequest request) {
            AuditEventService service = auditEventService.getIfAvailable();
            if (service == null) {
                return;
            }
            try {
                service.createEvent(request);
            } catch (RuntimeException e) {
                log.warn("Audit event emission failed: {}", e.getMessage());
            }
        }
    }
}
