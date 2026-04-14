package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.entity.PrincipalRole;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.repository.PrincipalRoleRepository;
import com.positivity.securityservice.service.AuthorizationService.Decision;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
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
    private PrincipalRoleRepository principalRoleRepository;

    @InjectMocks
    private AuthorizationServiceImpl sut;

    @Nested
    @DisplayName("authorize()")
    class Authorize {
        @Test
        @DisplayName("returns ALLOW when principal has matching permission")
        void authorize_matchingPermission_returnsAllow() {
            Permission permission = new Permission();
            permission.setName("pricing:price_book:edit");
            Role role = new Role();
            role.setPermissions(Set.of(permission));
            PrincipalRole principalRole = new PrincipalRole();
            principalRole.setRole(role);
            when(principalRoleRepository.findByPrincipalId("principal-1")).thenReturn(List.of(principalRole));

            Decision result = sut.authorize("principal-1", "pricing:price_book:edit");

            assertThat(result).isEqualTo(Decision.ALLOW);
        }

        @Test
        @DisplayName("returns DENY when principal lacks matching permission")
        void authorize_missingPermission_returnsDeny() {
            when(principalRoleRepository.findByPrincipalId("principal-2")).thenReturn(List.of());

            Decision result = sut.authorize("principal-2", "pricing:price_book:edit");

            assertThat(result).isEqualTo(Decision.DENY);
        }

        @Test
        @DisplayName("returns DENY when principal has different permission")
        void authorize_differentPermission_returnsDeny() {
            Permission permission = new Permission();
            permission.setName("pricing:price_book:view");
            Role role = new Role();
            role.setPermissions(Set.of(permission));
            PrincipalRole principalRole = new PrincipalRole();
            principalRole.setRole(role);
            when(principalRoleRepository.findByPrincipalId("principal-3")).thenReturn(List.of(principalRole));

            Decision result = sut.authorize("principal-3", "pricing:price_book:edit");

            assertThat(result).isEqualTo(Decision.DENY);
        }
    }
}
