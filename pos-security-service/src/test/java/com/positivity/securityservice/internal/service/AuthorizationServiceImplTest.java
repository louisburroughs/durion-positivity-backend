package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.internal.service.AuthorizationService.Decision;
import com.positivity.securityservice.internal.service.EffectiveGrantResolver.EffectiveGrants;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthorizationServiceImpl")
class AuthorizationServiceImplTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Spy
    Clock clock = TEST_CLOCK;

    @Mock
    private UserRepository userRepository;

    @Mock
    private EffectiveGrantResolver effectiveGrantResolver;

    @InjectMocks
    private AuthorizationServiceImpl sut;

    @Nested
    @DisplayName("authorizePerson()")
    class AuthorizePerson {
        @Test
        @DisplayName("returns ALLOW when the resolver's effective grants include the permission")
        void authorizePerson_matchingPermission_returnsAllow() {
            UUID personId = UUID.randomUUID();
            User user = new User();
            when(userRepository.findByPersonId(personId)).thenReturn(Optional.of(user));
            when(effectiveGrantResolver.resolve(user))
                    .thenReturn(new EffectiveGrants(Set.of(), Set.of(), Set.of("invoice:finalize:override")));

            Decision result = sut.authorizePerson(personId, "invoice:finalize:override");

            assertThat(result).isEqualTo(Decision.ALLOW);
        }

        @Test
        @DisplayName("returns DENY when the resolver's effective grants lack the permission")
        void authorizePerson_missingPermission_returnsDeny() {
            UUID personId = UUID.randomUUID();
            User user = new User();
            when(userRepository.findByPersonId(personId)).thenReturn(Optional.of(user));
            when(effectiveGrantResolver.resolve(user))
                    .thenReturn(new EffectiveGrants(Set.of(), Set.of(), Set.of("invoice:finalize:view")));

            Decision result = sut.authorizePerson(personId, "invoice:finalize:override");

            assertThat(result).isEqualTo(Decision.DENY);
        }

        @Test
        @DisplayName("returns DENY when no user is linked to the person")
        void authorizePerson_noLinkedUser_returnsDeny() {
            UUID personId = UUID.randomUUID();
            when(userRepository.findByPersonId(personId)).thenReturn(Optional.empty());

            Decision result = sut.authorizePerson(personId, "invoice:finalize:override");

            assertThat(result).isEqualTo(Decision.DENY);
        }
    }
}
