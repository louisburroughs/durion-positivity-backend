package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.config.AuditEventService;
import com.positivity.securityservice.internal.dto.AuditLogEventRequest;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.entity.UserActivationToken;
import com.positivity.securityservice.internal.exception.ActivationTokenInvalidException;
import com.positivity.securityservice.internal.exception.PlatformTenantRequiredException;
import com.positivity.securityservice.internal.exception.UserNotAwaitingActivationException;
import com.positivity.securityservice.internal.exception.UserNotFoundException;
import com.positivity.securityservice.internal.repository.UserActivationTokenRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * First-administrator activation (ADR-0062 §7, plan WS2b-3, decided 2026-09-10). Provisioning
 * leaves a tenant's first {@code ADMIN} credential-expired behind an unmatchable password; a
 * platform operator {@link #mint mints} a one-time token for that user and hands it over out of
 * band; the administrator {@link #activate exchanges} it, unauthenticated, for the first password.
 * No mail is involved, and the same token shape later drives e-mail reset.
 *
 * <p>Tokens are 32 random bytes, URL-safe base64; only the SHA-256 hex of a token is stored, so a
 * lost token is replaced by minting another (which closes the earlier one), never recovered. A
 * token is exchangeable once, for 72 hours.
 *
 * <p>Tenant bindings: minting runs under the caller's platform binding and rebinds the target
 * tenant for the user lookup and the token row; activation starts unbound (the request is on
 * {@code pos.tenancy.unenforced-paths}), finds the row by hash in the global table, and binds the
 * row's tenant for the user update. Both rebind <em>around</em> the transaction, in
 * {@link BoundOperations}, because the Hibernate session fixes its tenant at open time.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdministratorActivationService {

    /** How long a minted token stays exchangeable. */
    public static final Duration TOKEN_VALIDITY = Duration.ofHours(72);

    static final int TOKEN_BYTES = 32;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserActivationTokenRepository tokenRepository;
    private final BoundOperations boundOperations;
    private final Clock clock;

    /** A freshly minted token: the only time the token itself exists outside the operator's hands. */
    public record IssuedToken(
            @NonNull String token, @NonNull Instant expiresAt) {}

    /**
     * Mints an activation token for {@code userId} of tenant {@code tenantId}, closing any earlier
     * open token for the user.
     *
     * @throws PlatformTenantRequiredException when the caller is not bound to the platform tenant
     * @throws UserNotFoundException when the user does not exist in that tenant
     * @throws UserNotAwaitingActivationException when the user is not the credential-expired,
     *     never-signed-in account provisioning created (a live account keeps its password)
     */
    public @NonNull IssuedToken mint(@NonNull UUID tenantId, @NonNull UUID userId) {
        UUID bound = TenantContext.current().orElse(null);
        if (!PlatformTenant.isPlatform(bound)) {
            throw new PlatformTenantRequiredException(bound);
        }
        String actor = CurrentActor.resolve();
        String token = generateToken();
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(TOKEN_VALIDITY);
        TenantContext.runAs(
                tenantId, () -> boundOperations.issue(tenantId, userId, hash(token), now, expiresAt, actor));
        log.info(
                "Activation token minted for administrator {} of tenant {} by {}; expires {}",
                userId,
                tenantId,
                actor,
                expiresAt);
        return new IssuedToken(token, expiresAt);
    }

    /**
     * Exchanges {@code token} for {@code newPassword}: sets the password, clears the credential
     * expiry and marks the token used, in one transaction under the token's tenant.
     *
     * @throws ActivationTokenInvalidException when the token is unknown, expired or already used
     */
    public void activate(@NonNull String token, @NonNull String newPassword) {
        UserActivationToken row =
                tokenRepository.findByTokenHash(hash(token)).orElseThrow(ActivationTokenInvalidException::new);
        if (!row.isExchangeableAt(Instant.now(clock))) {
            throw new ActivationTokenInvalidException();
        }
        TenantContext.runAs(row.getTenantId(), () -> boundOperations.exchange(row, newPassword));
    }

    private static String generateToken() {
        byte[] entropy = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(entropy);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    }

    /** Lower-case hex SHA-256 of the token: what the table stores and what lookups are keyed by. */
    public static @NonNull String hash(@NonNull String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
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
        private final UserActivationTokenRepository tokenRepository;
        private final PasswordEncoder passwordEncoder;
        private final MintAuditWriter mintAuditWriter;
        private final Clock clock;

        /** Under the target tenant's binding: the user must be visible there. */
        @Transactional
        public void issue(
                @NonNull UUID tenantId,
                @NonNull UUID userId,
                @NonNull String tokenHash,
                @NonNull Instant now,
                @NonNull Instant expiresAt,
                @NonNull String actor) {
            // Locked for the rest of the transaction: two mints for the same administrator serialize
            // here, so the second sees the first's row and closes it instead of leaving two live tokens.
            User user = userRepository
                    .findByIdForUpdate(userId)
                    .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));
            if (!isAwaitingActivation(user)) {
                throw new UserNotAwaitingActivationException(userId);
            }
            List<UserActivationToken> open = tokenRepository.findByTenantIdAndUserIdAndUsedAtIsNull(tenantId, userId);
            open.forEach(earlier -> earlier.setUsedAt(now));
            tokenRepository.saveAll(open);
            tokenRepository.save(UserActivationToken.builder()
                    .tenantId(tenantId)
                    .userId(userId)
                    .tokenHash(tokenHash)
                    .expiresAt(expiresAt)
                    .createdBy(actor)
                    .createdAt(now)
                    .build());
            auditAfterCommit(new AuditLogEventRequest(
                    "AdministratorActivationTokenMinted",
                    actor,
                    userId.toString(),
                    "User",
                    open.isEmpty() ? "" : open.size() + " earlier open token(s) closed",
                    "expiresAt=" + expiresAt,
                    null));
            log.info(
                    "Administrator {} ({}) of tenant {}: activation token issued, {} earlier open token(s) closed",
                    user.getUsername(),
                    userId,
                    tenantId,
                    open.size());
        }

        /** Under the token's tenant binding. Consumes the token first, so a concurrent second use fails. */
        @Transactional
        public void exchange(@NonNull UserActivationToken row, @NonNull String newPassword) {
            // The user row is locked first, the same lock issue() takes: a mint running concurrently
            // waits here and then finds this token consumed and the user activated, so it refuses
            // (409) instead of committing a fresh token that could redeem against the live password.
            // A user hidden by row-level security (the row's tenant no longer holds it), or one no
            // longer awaiting activation, is the same refusal as an unknown token: nothing about the
            // account is revealed to the unauthenticated caller.
            User user =
                    userRepository.findByIdForUpdate(row.getUserId()).orElseThrow(ActivationTokenInvalidException::new);
            if (!isAwaitingActivation(user)) {
                throw new ActivationTokenInvalidException();
            }
            Instant now = Instant.now(clock);
            if (tokenRepository.consume(row.getId(), now) != 1) {
                throw new ActivationTokenInvalidException();
            }
            user.setPassword(passwordEncoder.encode(newPassword));
            user.setCredentialsNonExpired(true);
            user.setCredentialsExpireAt(null);
            user.setAwaitingActivation(false);
            userRepository.save(user);
            log.info(
                    "Administrator {} ({}) of tenant {} activated with token {}",
                    user.getUsername(),
                    user.getId(),
                    row.getTenantId(),
                    row.getId());
        }

        /**
         * The account provisioning creates and nobody has activated yet, read from the explicit
         * {@code users.awaiting_activation} marker that only {@code createUserAwaitingActivation}
         * sets and that activation and every ordinary password set clear. Credential state alone
         * cannot tell that account from a live one whose credentials an administrator expired
         * before its first login, so the marker decides; the expired-credentials invariant of the
         * provisioning state is kept as a defensive AND. A live account keeps its password — a
         * token must never overwrite it.
         */
        static boolean isAwaitingActivation(@NonNull User user) {
            return user.isAwaitingActivation() && !user.isCredentialsNonExpired();
        }

        /**
         * Emits the audit event once the surrounding transaction has committed, so a failing audit
         * write can neither block the mint nor mark the shared transaction rollback-only; outside a
         * transaction it is emitted at once. Either way a failure is a WARN, never a fault.
         */
        private void auditAfterCommit(AuditLogEventRequest request) {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        audit(request);
                    }
                });
            } else {
                audit(request);
            }
        }

        private void audit(AuditLogEventRequest request) {
            try {
                mintAuditWriter.write(request);
            } catch (RuntimeException e) {
                log.warn("Audit event emission failed: {}", e.getMessage());
            }
        }
    }

    /**
     * Commits the mint audit row in its own transaction, a dedicated bean so the {@code
     * @Transactional} proxy applies (the same reason {@link BoundOperations} is split out of the
     * outer service).
     *
     * <p>{@link AuditEventService#createEvent} is {@code @Transactional} with the default {@code
     * REQUIRED} propagation, and {@link BoundOperations#auditAfterCommit} runs it from an {@code
     * afterCommit} synchronization callback. At that point the just-committed transaction's
     * resources (its {@code EntityManagerHolder}) are still thread-bound — Spring only unbinds
     * them once every {@code afterCommit} synchronization has run — so a plain {@code REQUIRED}
     * call here would "join" that already-committed, about-to-be-discarded transaction instead of
     * opening a fresh one: the insert would land in the stale persistence context but never
     * actually be committed, since only the transaction that started it calls commit. {@code
     * REQUIRES_NEW} suspends whatever is still thread-bound and opens a genuinely new transaction
     * on its own connection, so this write gets its own real commit. See {@link
     * RoleAssignmentTokenRevocationListener}'s javadoc for the same mechanism applied to token
     * revocation, the pattern this follows.
     */
    @Component
    @RequiredArgsConstructor
    static class MintAuditWriter {

        private final ObjectProvider<AuditEventService> auditEventService;

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        void write(@NonNull AuditLogEventRequest request) {
            AuditEventService service = auditEventService.getIfAvailable();
            if (service != null) {
                service.createEvent(request);
            }
        }
    }
}
