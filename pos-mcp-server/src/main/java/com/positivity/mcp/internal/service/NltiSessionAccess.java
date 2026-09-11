package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.entity.NltiSession;
import com.positivity.mcp.internal.exception.SessionNotFoundException;
import com.positivity.mcp.internal.exception.SessionOwnershipViolationException;
import com.positivity.mcp.internal.repository.NltiSessionRepository;
import com.positivity.tenancy.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The one place an NLTI session is looked up by id (ADR-0062 plan WS6, R-B6: a session never serves
 * another tenant).
 *
 * <p>Every lookup runs under the bound tenant: {@link TenantContext#require()} fails loudly on an
 * unbound path rather than letting the repository answer from nothing, Hibernate appends the tenant
 * to the query ({@code @TenantId}) and row-level security hides every other tenant's row. On top of
 * those two layers the row that comes back is checked against the binding, so a mismatch — which
 * neither layer should ever let through — is treated as "not found", never returned. A session id
 * of another tenant is therefore indistinguishable from an id that never existed: {@link
 * #requireOwned} answers {@link SessionNotFoundException} (404) for both, and reserves {@link
 * SessionOwnershipViolationException} (403) for a session of this tenant that belongs to another
 * subject.
 */
@Service
public class NltiSessionAccess {

    private static final Logger LOGGER = LoggerFactory.getLogger(NltiSessionAccess.class);

    private final NltiSessionRepository sessionRepository;

    public NltiSessionAccess(@NonNull NltiSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    /** The session with this id in the bound tenant, whoever owns it. */
    public @NonNull Optional<NltiSession> findInTenant(@NonNull UUID sessionId) {
        UUID tenantId = TenantContext.require();
        return sessionRepository.findById(sessionId).filter(session -> belongsTo(session, tenantId));
    }

    /** The session with this id in the bound tenant, if {@code subjectId} owns it. */
    public @NonNull Optional<NltiSession> findOwned(@NonNull UUID sessionId, @NonNull String subjectId) {
        UUID tenantId = TenantContext.require();
        return sessionRepository
                .findByIdAndSubjectId(sessionId, subjectId)
                .filter(session -> belongsTo(session, tenantId));
    }

    /**
     * The session with this id in the bound tenant, owned by {@code subjectId}.
     *
     * @throws SessionNotFoundException when the bound tenant has no such session (an id of another
     *     tenant lands here too)
     * @throws SessionOwnershipViolationException when the bound tenant's session belongs to another
     *     subject
     */
    public @NonNull NltiSession requireOwned(@NonNull UUID sessionId, @NonNull String subjectId) {
        NltiSession session = findInTenant(sessionId)
                .orElseThrow(() -> new SessionNotFoundException("No such session for this tenant: " + sessionId));
        if (!subjectId.equals(session.getSubjectId())) {
            throw new SessionOwnershipViolationException(
                    "Session is not owned by the authenticated subject: " + sessionId);
        }
        return session;
    }

    private static boolean belongsTo(@NonNull NltiSession session, @NonNull UUID tenantId) {
        UUID rowTenant = session.getTenantId();
        // Null only before the first flush, which a loaded row never is; treat it as the binding.
        if (rowTenant == null || rowTenant.equals(tenantId)) {
            return true;
        }
        LOGGER.warn(
                "NLTI session {} of tenant {} was loaded under tenant {}; treating it as not found",
                session.getId(),
                rowTenant,
                tenantId);
        return false;
    }
}
