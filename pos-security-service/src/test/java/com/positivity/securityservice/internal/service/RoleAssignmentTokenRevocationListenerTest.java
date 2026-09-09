package com.positivity.securityservice.internal.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.event.RoleAssignmentRevokedEvent;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.internal.security.service.JwtService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ADR-0061 §4 amendment (2026-09-09, #1914 phase 3): {@link RoleAssignmentTokenRevocationListener}
 * is the after-commit reaction to {@link RoleAssignmentRevokedEvent} — see that type and the
 * listener's own javadoc for why an event rather than a direct call. This unit test invokes the
 * listener method directly (the {@code @TransactionalEventListener(AFTER_COMMIT)} firing mechanics
 * are Spring's, not this module's, to test); the end-to-end after-commit behaviour is covered by
 * {@code RoleAssignmentRevocationIT}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RoleAssignmentTokenRevocationListener")
class RoleAssignmentTokenRevocationListenerTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtService jwtService;

    @InjectMocks
    private RoleAssignmentTokenRevocationListener sut;

    @Test
    @DisplayName("revokes every stored token for the event's user, by username")
    void onRoleAssignmentRevoked_knownUser_revokesAllTokensByUsername() {
        User user = new User();
        user.setId(USER_ID);
        user.setUsername("alice");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        sut.onRoleAssignmentRevoked(new RoleAssignmentRevokedEvent(this, USER_ID));

        verify(jwtService).revokeAllTokensForUser("alice");
    }

    @Test
    @DisplayName("an event for a user that no longer exists revokes nothing")
    void onRoleAssignmentRevoked_unknownUser_doesNothing() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        sut.onRoleAssignmentRevoked(new RoleAssignmentRevokedEvent(this, USER_ID));

        verify(jwtService, never()).revokeAllTokensForUser(any());
    }
}
