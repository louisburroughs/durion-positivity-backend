package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.event.RoleAssignmentRevokedEvent;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.internal.security.service.JwtService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Ends a user's live tokens after a role-assignment revocation commits (ADR-0061 §4 amendment,
 * 2026-09-09, #1914 phase 3).
 *
 * <p><b>Why an event, not a direct call.</b> {@code UserRoleGrantServiceImpl} (used by {@code
 * RoleManagementServiceImpl} and {@code UserServiceImpl}) and {@code
 * RoleManagementServiceImpl#revokeRoleAssignment} both need to revoke the affected user's live
 * tokens as part of ending an assignment. {@code JwtServiceImpl} depends on {@code UserService},
 * and {@code UserServiceImpl} depends on {@code UserRoleGrantService} (which {@code
 * RoleManagementServiceImpl} also depends on) — either of those services calling {@link
 * JwtService} directly would close a Spring bean cycle back through {@code JwtServiceImpl}.
 * Publishing {@link RoleAssignmentRevokedEvent} and reacting to it here breaks the cycle: the
 * publishers depend only on {@code ApplicationEventPublisher}, a core Spring type with no such
 * dependency.
 *
 * <p><b>Why {@code AFTER_COMMIT}.</b> Token revocation is the security-critical side effect of a
 * role-assignment revocation, so it must not run — and must not appear to have run — for a
 * revocation whose transaction then rolls back. {@link JwtService#revokeAllTokensForUser} reaches
 * {@code TokenRevocationManager}, which is Redis-backed and not transactional; ordering the call
 * after the database commit (rather than inside the same transaction) is what guarantees the
 * role_assignments write is durable before the token layer acts on it, so a failed token-layer
 * write can never strand a revoked token as still-live.
 *
 * <p><b>{@code Propagation.REQUIRES_NEW}.</b> When {@code AFTER_COMMIT} fires, the just-committed
 * transaction's resources (its {@code EntityManagerHolder}) are still thread-bound — Spring only
 * unbinds them in the cleanup step that runs after every {@code afterCommit} synchronization has
 * been invoked. A plain {@code REQUIRED} call here would therefore "participate" in that already-
 * committed, about-to-be-discarded transaction instead of opening a fresh one: the delete would be
 * applied to the stale persistence context but never actually committed to the database, since
 * only the transaction that started it calls commit. {@code REQUIRES_NEW} suspends whatever is
 * still thread-bound and opens a genuinely new transaction on its own connection, so this method's
 * writes get their own real commit.
 *
 * <p><b>Reuses {@link JwtService#revokeAllTokensForUser}</b> — the same username-keyed "revoke
 * every stored token for a user" facility {@code AdminAccountStateServiceImpl} already uses for
 * account lockout/disable, over the existing {@code TokenRevocationManager} + {@code jwt_token}
 * path. This javadoc records role-assignment revocation as a second security-load-bearing trigger
 * of that facility, alongside account state changes: its Redis-unavailable behaviour (fail-open,
 * with the {@code jwt_token} row still deleted so the bearer path still refuses the token) is
 * unchanged and applies here too.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoleAssignmentTokenRevocationListener {

    private final UserRepository userRepository;
    private final JwtService jwtService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onRoleAssignmentRevoked(RoleAssignmentRevokedEvent event) {
        Optional<User> user = userRepository.findById(event.getUserId());
        if (user.isEmpty()) {
            log.warn("Role assignment revoked for unknown userId={}; no live tokens to revoke", event.getUserId());
            return;
        }
        jwtService.revokeAllTokensForUser(user.get().getUsername());
        log.info("Revoked live tokens after role-assignment revocation: userId={}", event.getUserId());
    }
}
