package com.positivity.securityservice.internal.service;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.tenant.TenantProvisionedV1;
import com.positivity.securityservice.internal.config.OutboxEventWriter;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.repository.PermissionRepository;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.tenancy.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provisions a new tenant on {@code tenant.created} (ADR-0062 §7, plan WS2b part 2): applies the
 * platform role template, creates the initial administrator named in the create request, writes
 * the first {@code role_assignments} row, and answers {@code tenant.provisioned}, which moves the
 * tenant to {@code ACTIVE} in pos-tenant.
 *
 * <p>Runs under the new tenant's binding, which the caller establishes
 * ({@code TenantContext.runAs(tenantId, ...)}); every row written here is that tenant's under
 * row-level security. Idempotent on tenant: a role or user that already exists is left alone, so a
 * redelivered event or a retry after a partial failure converges. The answer is emitted on every
 * successful run, and pos-tenant's handler is idempotent on tenant id. Bringing an already
 * provisioned tenant up to a template that has since grown is {@link
 * RoleTemplateReconciliationService} (plan WS8), which shares {@link RoleTemplateApplier}.
 *
 * <p>The administrator is created awaiting activation (plan WS2b-3, decided 2026-09-10): the
 * password is generated and discarded (never returned, logged or persisted in plaintext) and the
 * credentials are marked expired, so the account cannot sign in until a platform operator mints an
 * activation token ({@code POST /v1/platform/tenants/{tenantId}/administrators/{userId}/activation-token})
 * and the administrator exchanges it at {@code POST /v1/auth/activate}. No credential ever rides
 * on this event.
 */
@Slf4j
@Service
public class TenantProvisioningService {

    /** Actor recorded on the rows provisioning writes. */
    static final String ACTOR = "tenant-provisioning";

    /** The template role the initial administrator holds. */
    static final String INITIAL_ADMIN_ROLE = "ADMIN";

    private static final String SOURCE = "pos-security-service";

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final UserRepository userRepository;
    private final UserService userService;
    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;
    private final Clock clock;
    private final String tenantEventsTopic;

    public TenantProvisioningService(
            RoleRepository roleRepository,
            PermissionRepository permissionRepository,
            UserRepository userRepository,
            UserService userService,
            ObjectProvider<OutboxEventWriter> outboxEventWriter,
            Clock clock,
            @Value("${pos.security-service.kafka.tenant-events-topic:tenant.events.v1}") String tenantEventsTopic) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.userRepository = userRepository;
        this.userService = userService;
        this.outboxEventWriter = outboxEventWriter;
        this.clock = clock;
        this.tenantEventsTopic = tenantEventsTopic;
    }

    /** What one run did; a redelivery reports zeros. */
    public record Outcome(int rolesCreated, boolean administratorCreated) {}

    /**
     * @param tenantId the tenant being provisioned; must be the bound tenant
     * @param initialAdminEmail username of the first administrator (the create request's email)
     * @param template the platform role template, read under the platform binding
     * @throws IllegalStateException when the binding is not {@code tenantId}, or the template
     *     carries no {@value #INITIAL_ADMIN_ROLE} role to give the administrator
     */
    @Transactional
    public @NonNull Outcome provision(
            @NonNull UUID tenantId, @NonNull String initialAdminEmail, @NonNull List<RoleTemplateEntry> template) {
        if (!tenantId.equals(TenantContext.require())) {
            throw new IllegalStateException("Provisioning of tenant " + tenantId + " must run under its own binding");
        }
        // Case-insensitively, the same convention the convergence loop below and
        // RoleTemplateReconciliationService both use: a template entry is the same role by name
        // regardless of case (ADR-0062 section 6, plan WS8), and this guard must recognise exactly
        // the entry the loop below would. An exact comparison here rejected a template whose ADMIN
        // entry was provisioned under a different case (`roles.csv` resolves names case-insensitively
        // too) before the convergence logic that does tolerate it ever ran.
        if (template.stream().noneMatch(entry -> INITIAL_ADMIN_ROLE.equalsIgnoreCase(entry.name()))) {
            throw new IllegalStateException("The platform role template has no " + INITIAL_ADMIN_ROLE
                    + " role; nothing to give the administrator");
        }

        int created = 0;
        for (RoleTemplateEntry entry : template) {
            // Case-insensitively, the same uniqueness createRole enforces and the same lookup
            // RoleTemplateReconciliationService uses (ADR-0062 section 6, plan WS8): a tenant that
            // already carries `admin` must not be given a second ADMIN, which the (tenant_id,
            // lower(name)) index in the baseline would refuse anyway -- failing provisioning
            // outright rather than converging on a redelivered tenant.created.
            if (roleRepository.existsByNameIgnoreCase(entry.name())) {
                continue;
            }
            roleRepository.save(
                    RoleTemplateApplier.fromTemplate(entry, permissionRepository, Instant.now(clock), ACTOR));
            created++;
        }

        boolean administratorCreated = false;
        if (!userRepository.existsByUsername(initialAdminEmail)) {
            // The guard above is case-insensitive, so a tenant that already carried `admin` keeps
            // that row and no ADMIN was created; the administrator must be given the name actually
            // stored, because role resolution on the user path matches exactly.
            String adminRole = roleRepository
                    .findByNameIgnoreCase(INITIAL_ADMIN_ROLE)
                    .map(Role::getName)
                    .orElse(INITIAL_ADMIN_ROLE);
            userService.createUserAwaitingActivation(initialAdminEmail, Set.of(adminRole));
            administratorCreated = true;
        }

        emitProvisioned(tenantId);
        log.info(
                "Provisioned tenant {}: {} template role(s) created, administrator {} {}",
                tenantId,
                created,
                initialAdminEmail,
                administratorCreated ? "created" : "already present");
        return new Outcome(created, administratorCreated);
    }

    private void emitProvisioned(UUID tenantId) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            log.warn("Kafka is disabled; tenant.provisioned for {} is not queued", tenantId);
            return;
        }
        DomainEventEnvelope<TenantProvisionedV1> envelope = DomainEventEnvelope.of(
                TenantProvisionedV1.EVENT_TYPE,
                TenantProvisionedV1.SCHEMA_VERSION,
                tenantId,
                0L,
                SOURCE,
                null,
                ACTOR,
                new TenantProvisionedV1(tenantId),
                clock);
        writer.publish(tenantEventsTopic, envelope);
    }
}
