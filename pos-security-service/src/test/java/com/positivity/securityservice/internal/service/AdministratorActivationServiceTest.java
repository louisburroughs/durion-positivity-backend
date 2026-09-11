package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.positivity.securityservice.internal.security.service.JwtService;
import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * First-administrator activation (ADR-0062 §7, WS2b-3): mint under the platform binding only,
 * hash-only storage, one earlier open token closed per mint, 72-hour validity, exchange once.
 */
class AdministratorActivationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");
    private static final UUID TENANT = UUID.fromString("01990000-0000-7000-8000-000000000123");
    private static final UUID OTHER_TENANT = UUID.fromString("01990000-0000-7000-8000-000000000999");
    private static final UUID USER = UUID.fromString("01990000-0000-7000-8000-000000000501");

    private final UserRepository users = mock(UserRepository.class);
    private final UserActivationTokenRepository tokens = mock(UserActivationTokenRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final AuditEventService audit = mock(AuditEventService.class);
    private final JwtService jwtService = mock(JwtService.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<AuditEventService> auditProvider = mock(ObjectProvider.class);

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AdministratorActivationService.MintAuditWriter mintAuditWriter =
            new AdministratorActivationService.MintAuditWriter(auditProvider);
    private final AdministratorActivationService.BoundOperations bound =
            new AdministratorActivationService.BoundOperations(
                    users, tokens, encoder, mintAuditWriter, jwtService, clock);
    private final AdministratorActivationService service = new AdministratorActivationService(tokens, bound, clock);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static User user() {
        User user = new User();
        user.setId(USER);
        user.setUsername("owner@acme.example");
        user.setPassword("$2a$unmatchable");
        user.setCredentialsNonExpired(false);
        user.setCredentialsExpireAt(NOW.minus(Duration.ofDays(1)));
        user.setAwaitingActivation(true);
        return user;
    }

    private static UserActivationToken row(String tokenHash, Instant expiresAt, Instant usedAt) {
        return UserActivationToken.builder()
                .id(UUID.fromString("01990000-0000-7000-8000-000000000777"))
                .tenantId(TENANT)
                .userId(USER)
                .tokenHash(tokenHash)
                .expiresAt(expiresAt)
                .usedAt(usedAt)
                .createdBy("admin.platform")
                .createdAt(NOW.minus(Duration.ofHours(1)))
                .build();
    }

    @Test
    @DisplayName("the hash is the lower-case hex SHA-256 of the token, so lookups never need the token stored")
    void hashIsSha256Hex() {
        assertThat(AdministratorActivationService.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Nested
    @DisplayName("mint")
    class Mint {

        @Test
        @DisplayName("refuses a caller bound to a tenant other than the platform tenant, and an unbound one")
        void requiresThePlatformBinding() {
            TenantContext.bind(OTHER_TENANT);
            assertThatThrownBy(() -> service.mint(TENANT, USER))
                    .isInstanceOf(PlatformTenantRequiredException.class)
                    .hasMessageContaining(OTHER_TENANT.toString());

            TenantContext.clear();
            assertThatThrownBy(() -> service.mint(TENANT, USER))
                    .isInstanceOf(PlatformTenantRequiredException.class)
                    .hasMessageContaining("none is bound");
            verify(tokens, never()).save(any());
        }

        @Test
        @DisplayName(
                "stores only the hash under the target tenant's binding, closes earlier open tokens, 72 h validity")
        void mintsUnderTheTargetBinding() {
            // No transaction here, so the audit is emitted at once; the deferral is proven below.
            when(auditProvider.getIfAvailable()).thenReturn(audit);
            AtomicReference<UUID> boundDuringLookup = new AtomicReference<>();
            when(users.findByIdForUpdate(USER)).thenAnswer(inv -> {
                boundDuringLookup.set(TenantContext.require());
                return Optional.of(user());
            });
            UserActivationToken earlier = row("older-hash", NOW.plus(Duration.ofHours(10)), null);
            when(tokens.findByTenantIdAndUserIdAndUsedAtIsNull(TENANT, USER)).thenReturn(List.of(earlier));

            TenantContext.bind(PlatformTenant.ID);
            AdministratorActivationService.IssuedToken issued = service.mint(TENANT, USER);

            assertThat(TenantContext.current())
                    .as("the caller's binding is restored")
                    .contains(PlatformTenant.ID);
            assertThat(boundDuringLookup.get())
                    .as("the user is read as the target tenant")
                    .isEqualTo(TENANT);
            assertThat(issued.token()).hasSize(43).matches("[A-Za-z0-9_-]+");
            assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(72)));

            ArgumentCaptor<UserActivationToken> saved = ArgumentCaptor.forClass(UserActivationToken.class);
            verify(tokens).save(saved.capture());
            UserActivationToken row = saved.getValue();
            assertThat(row.getTenantId()).isEqualTo(TENANT);
            assertThat(row.getUserId()).isEqualTo(USER);
            assertThat(row.getTokenHash())
                    .isEqualTo(AdministratorActivationService.hash(issued.token()))
                    .doesNotContain(issued.token());
            assertThat(row.getExpiresAt()).isEqualTo(issued.expiresAt());
            assertThat(row.getUsedAt()).isNull();
            assertThat(row.getCreatedAt()).isEqualTo(NOW);
            assertThat(row.getCreatedBy()).isEqualTo("system");
            assertThat(earlier.getUsedAt())
                    .as("the earlier open token is closed")
                    .isEqualTo(NOW);
            verify(tokens).saveAll(List.of(earlier));

            ArgumentCaptor<AuditLogEventRequest> auditRequest = ArgumentCaptor.forClass(AuditLogEventRequest.class);
            verify(audit).createEvent(auditRequest.capture());
            assertThat(auditRequest.getValue().getEventType()).isEqualTo("AdministratorActivationTokenMinted");
            assertThat(auditRequest.getValue().getEntityId()).isEqualTo(USER.toString());
        }

        @Test
        @DisplayName("two mints never produce the same token")
        void tokensAreRandom() {
            when(users.findByIdForUpdate(USER)).thenReturn(Optional.of(user()));
            when(tokens.findByTenantIdAndUserIdAndUsedAtIsNull(TENANT, USER)).thenReturn(List.of());
            TenantContext.bind(PlatformTenant.ID);

            String first = service.mint(TENANT, USER).token();
            String second = service.mint(TENANT, USER).token();

            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("a user the target tenant does not hold is 404 USER_NOT_FOUND, and nothing is written")
        void unknownUserIs404() {
            when(users.findByIdForUpdate(USER)).thenReturn(Optional.empty());
            TenantContext.bind(PlatformTenant.ID);

            assertThatThrownBy(() -> service.mint(TENANT, USER)).isInstanceOf(UserNotFoundException.class);
            verify(tokens, never()).save(any());
            verify(tokens, never()).saveAll(any());
        }

        @Test
        @DisplayName("a user whose credentials are live is 409: a token must never overwrite a live password")
        void liveUserIs409() {
            User live = user();
            live.setCredentialsNonExpired(true);
            live.setCredentialsExpireAt(null);
            when(users.findByIdForUpdate(USER)).thenReturn(Optional.of(live));
            TenantContext.bind(PlatformTenant.ID);

            assertThatThrownBy(() -> service.mint(TENANT, USER))
                    .isInstanceOf(UserNotAwaitingActivationException.class)
                    .hasMessageContaining(USER.toString());
            verify(tokens, never()).save(any());
            verify(tokens, never()).saveAll(any());
        }

        @Test
        @DisplayName("an ordinary account whose credentials an administrator expired before its first login is 409:"
                + " only the explicit marker names the provisioning state")
        void adminExpiredNeverSignedInUserIs409() {
            User expiredByAdmin = user();
            expiredByAdmin.setAwaitingActivation(false);
            assertThat(expiredByAdmin.isCredentialsNonExpired()).isFalse();
            assertThat(expiredByAdmin.getLastSuccessfulLoginAt()).isNull();
            when(users.findByIdForUpdate(USER)).thenReturn(Optional.of(expiredByAdmin));
            TenantContext.bind(PlatformTenant.ID);

            assertThatThrownBy(() -> service.mint(TENANT, USER)).isInstanceOf(UserNotAwaitingActivationException.class);
            verify(tokens, never()).save(any());
        }

        @Test
        @DisplayName("the marker alone is not enough: credentials that are live again make it 409 (defensive AND)")
        void markerWithLiveCredentialsIs409() {
            User inconsistent = user();
            inconsistent.setCredentialsNonExpired(true);
            when(users.findByIdForUpdate(USER)).thenReturn(Optional.of(inconsistent));
            TenantContext.bind(PlatformTenant.ID);

            assertThatThrownBy(() -> service.mint(TENANT, USER)).isInstanceOf(UserNotAwaitingActivationException.class);
            verify(tokens, never()).save(any());
        }

        @Test
        @DisplayName("an account that has already signed in is 409 even with the marker set and credentials "
                + "expired: the never-signed-in invariant is not the marker alone")
        void alreadySignedInAccountIs409EvenWithTheMarkerAndExpiredCredentials() {
            User signedInBefore = user();
            signedInBefore.setLastSuccessfulLoginAt(NOW.minus(Duration.ofDays(1)));
            when(users.findByIdForUpdate(USER)).thenReturn(Optional.of(signedInBefore));
            TenantContext.bind(PlatformTenant.ID);

            assertThatThrownBy(() -> service.mint(TENANT, USER)).isInstanceOf(UserNotAwaitingActivationException.class);
            verify(tokens, never()).save(any());
        }

        @Test
        @DisplayName("inside a transaction the audit event waits for the commit and its failure is only a WARN")
        void auditIsEmittedAfterCommit() {
            when(auditProvider.getIfAvailable()).thenReturn(audit);
            when(audit.createEvent(any())).thenThrow(new IllegalStateException("audit down"));
            when(users.findByIdForUpdate(USER)).thenReturn(Optional.of(user()));
            when(tokens.findByTenantIdAndUserIdAndUsedAtIsNull(TENANT, USER)).thenReturn(List.of());
            TenantContext.bind(PlatformTenant.ID);

            TransactionSynchronizationManager.initSynchronization();
            try {
                assertThat(service.mint(TENANT, USER).token()).isNotBlank();
                verify(audit, never()).createEvent(any());
                List<TransactionSynchronization> registered = TransactionSynchronizationManager.getSynchronizations();
                assertThat(registered).hasSize(1);
                // A throwing audit after commit is swallowed, never surfaced to the caller.
                registered.forEach(TransactionSynchronization::afterCommit);
                verify(audit).createEvent(any());
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }

        @Test
        @DisplayName("an audit failure does not fail the mint")
        void auditFailureIsSwallowed() {
            when(auditProvider.getIfAvailable()).thenReturn(audit);
            when(audit.createEvent(any())).thenThrow(new IllegalStateException("audit down"));
            when(users.findByIdForUpdate(USER)).thenReturn(Optional.of(user()));
            when(tokens.findByTenantIdAndUserIdAndUsedAtIsNull(TENANT, USER)).thenReturn(List.of());
            TenantContext.bind(PlatformTenant.ID);

            assertThat(service.mint(TENANT, USER).token()).isNotBlank();
            verify(tokens).save(any(UserActivationToken.class));
        }
    }

    @Nested
    @DisplayName("activate")
    class Activate {

        private static final String TOKEN = "Qm9iIGlzIG5vdCBhIHJlYWwgdG9rZW4gYnV0IGxvb2tzIGxpa2Ugb25l";
        private static final String HASH = AdministratorActivationService.hash(TOKEN);

        @Test
        @DisplayName("sets the password, clears the credential expiry and consumes the token under the row's tenant")
        void exchangesOnce() {
            UserActivationToken row = row(HASH, NOW.plus(Duration.ofHours(1)), null);
            when(tokens.findByTokenHash(HASH)).thenReturn(Optional.of(row));
            when(tokens.consume(row.getId(), NOW)).thenReturn(1);
            User user = user();
            // Attempts made against the unmatchable provisioning password before activation (WS2b-3
            // review): the login pre-flight lockout check runs before AuthenticationManager rejects
            // the credentials, so this bookkeeping accrues even while the account is awaiting
            // activation.
            user.setFailedLoginAttempts(3);
            user.setAccountNonLocked(false);
            user.setLockedAt(NOW.minus(Duration.ofMinutes(5)));
            user.setLockedUntil(NOW.plus(Duration.ofMinutes(10)));
            AtomicReference<UUID> boundDuringUpdate = new AtomicReference<>();
            when(users.findByIdForUpdate(USER)).thenAnswer(inv -> {
                boundDuringUpdate.set(TenantContext.require());
                return Optional.of(user);
            });
            when(encoder.encode("Sup3rS3cret!")).thenReturn("$2a$hashed");

            service.activate(TOKEN, "Sup3rS3cret!");

            assertThat(boundDuringUpdate.get()).isEqualTo(TENANT);
            assertThat(TenantContext.isBound())
                    .as("activation started and ends unbound")
                    .isFalse();
            assertThat(user.getPassword()).isEqualTo("$2a$hashed");
            assertThat(user.isCredentialsNonExpired()).isTrue();
            assertThat(user.getCredentialsExpireAt()).isNull();
            assertThat(user.isAwaitingActivation()).as("the marker is cleared").isFalse();
            assertThat(user.isAccountNonLocked())
                    .as("pre-activation lockout must not survive activation")
                    .isTrue();
            assertThat(user.getFailedLoginAttempts()).isZero();
            assertThat(user.getLockedAt()).isNull();
            assertThat(user.getLockedUntil()).isNull();
            verify(users).save(user);
            verify(users, never()).findById(any());
            // Every token minted for the username before activation (JwtController#issueInternalToken
            // / #generateTokenPair, which check neither password nor awaiting-activation state) must
            // not keep authenticating once credentialsNonExpired flips true.
            verify(jwtService).revokeAllTokensForUser(user.getUsername());
            // The user lock is taken before the token is consumed, the same order issue() uses, so a
            // concurrent mint cannot slip a fresh token in between the consume and the password write.
            InOrder inOrder = inOrder(users, tokens);
            inOrder.verify(users).findByIdForUpdate(USER);
            inOrder.verify(tokens).consume(row.getId(), NOW);
            inOrder.verify(users).save(user);
        }

        @Test
        @DisplayName("an account that has already signed in is refused even with the marker set and credentials "
                + "expired: the never-signed-in invariant is not the marker alone")
        void alreadySignedInAccountIsInvalidEvenWithTheMarkerAndExpiredCredentials() {
            UserActivationToken row = row(HASH, NOW.plus(Duration.ofHours(1)), null);
            when(tokens.findByTokenHash(HASH)).thenReturn(Optional.of(row));
            User signedInBefore = user();
            signedInBefore.setLastSuccessfulLoginAt(NOW.minus(Duration.ofDays(1)));
            when(users.findByIdForUpdate(USER)).thenReturn(Optional.of(signedInBefore));

            assertThatThrownBy(() -> service.activate(TOKEN, "Sup3rS3cret!"))
                    .isInstanceOf(ActivationTokenInvalidException.class);
            verify(tokens, never()).consume(any(), any());
            verify(users, never()).save(any());
            verify(jwtService, never()).revokeAllTokensForUser(anyString());
        }

        @Test
        @DisplayName(
                "a user no longer awaiting activation is refused before the token is consumed, as an invalid token")
        void userNoLongerAwaitingIsInvalidAndTokenUntouched() {
            UserActivationToken row = row(HASH, NOW.plus(Duration.ofHours(1)), null);
            when(tokens.findByTokenHash(HASH)).thenReturn(Optional.of(row));
            User activated = user();
            activated.setAwaitingActivation(false);
            activated.setCredentialsNonExpired(true);
            when(users.findByIdForUpdate(USER)).thenReturn(Optional.of(activated));

            assertThatThrownBy(() -> service.activate(TOKEN, "Sup3rS3cret!"))
                    .isInstanceOf(ActivationTokenInvalidException.class);
            verify(tokens, never()).consume(any(), any());
            verify(users, never()).save(any());
            verify(encoder, never()).encode(anyString());
        }

        @Test
        @DisplayName("an unknown token is refused before any tenant is bound")
        void unknownTokenIsInvalid() {
            when(tokens.findByTokenHash(anyString())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.activate("nope", "Sup3rS3cret!"))
                    .isInstanceOf(ActivationTokenInvalidException.class);
            verify(users, never()).findByIdForUpdate(any());
            verify(encoder, never()).encode(anyString());
        }

        @Test
        @DisplayName("an expired token is the same refusal")
        void expiredTokenIsInvalid() {
            when(tokens.findByTokenHash(HASH)).thenReturn(Optional.of(row(HASH, NOW, null)));

            assertThatThrownBy(() -> service.activate(TOKEN, "Sup3rS3cret!"))
                    .isInstanceOf(ActivationTokenInvalidException.class);
            verify(tokens, never()).consume(any(), any());
        }

        @Test
        @DisplayName("a used token is the same refusal")
        void usedTokenIsInvalid() {
            when(tokens.findByTokenHash(HASH))
                    .thenReturn(Optional.of(row(HASH, NOW.plus(Duration.ofHours(1)), NOW.minusSeconds(5))));

            assertThatThrownBy(() -> service.activate(TOKEN, "Sup3rS3cret!"))
                    .isInstanceOf(ActivationTokenInvalidException.class);
            verify(tokens, never()).consume(any(), any());
        }

        @Test
        @DisplayName("a token consumed concurrently between lookup and update is refused and the password untouched")
        void raceOnConsumeIsInvalid() {
            UserActivationToken row = row(HASH, NOW.plus(Duration.ofHours(1)), null);
            when(tokens.findByTokenHash(HASH)).thenReturn(Optional.of(row));
            when(users.findByIdForUpdate(USER)).thenReturn(Optional.of(user()));
            when(tokens.consume(row.getId(), NOW)).thenReturn(0);

            assertThatThrownBy(() -> service.activate(TOKEN, "Sup3rS3cret!"))
                    .isInstanceOf(ActivationTokenInvalidException.class);
            verify(users, never()).save(any());
        }

        @Test
        @DisplayName("a user the row's tenant no longer holds is the same refusal, not a 404")
        void hiddenUserIsInvalid() {
            UserActivationToken row = row(HASH, NOW.plus(Duration.ofHours(1)), null);
            when(tokens.findByTokenHash(HASH)).thenReturn(Optional.of(row));
            when(users.findByIdForUpdate(USER)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.activate(TOKEN, "Sup3rS3cret!"))
                    .isInstanceOf(ActivationTokenInvalidException.class);
            verify(tokens, never()).consume(eq(row.getId()), any());
        }
    }
}
