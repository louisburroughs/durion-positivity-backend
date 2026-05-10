package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@ExtendWith(MockitoExtension.class)
class McpRoleResolverImplTest {

    @Mock
    private Authentication authentication;

    private McpRoleResolverImpl resolver;

    @BeforeEach
    void setUp() {
        resolver = new McpRoleResolverImpl();
    }

    private void givenAuthorities(String... roles) {
        Collection<? extends GrantedAuthority> authorities =
                Stream.of(roles).map(SimpleGrantedAuthority::new).toList();
        when(authentication.getAuthorities()).thenAnswer(inv -> authorities);
    }

    @Test
    @DisplayName("resolvePrimaryRole returns ROLE_ADMIN when caller has admin authority")
    void resolvePrimaryRole_admin_returnsAdmin() {
        givenAuthorities("ROLE_ADMIN", "ROLE_SERVICE_ADVISOR");
        assertThat(resolver.resolvePrimaryRole(authentication)).isEqualTo("ROLE_ADMIN");
    }

    @Test
    @DisplayName("resolvePrimaryRole returns ROLE_TECHNICIAN when only technician role present")
    void resolvePrimaryRole_technicianOnly_returnsTechnician() {
        givenAuthorities("ROLE_TECHNICIAN");
        assertThat(resolver.resolvePrimaryRole(authentication)).isEqualTo("ROLE_TECHNICIAN");
    }

    @Test
    @DisplayName("resolvePrimaryRole falls back to ROLE_USER when no known role present")
    void resolvePrimaryRole_noKnownRole_returnsUser() {
        givenAuthorities("ROLE_UNKNOWN");
        assertThat(resolver.resolvePrimaryRole(authentication)).isEqualTo("ROLE_USER");
    }

    @Test
    @DisplayName("resolvePrimaryRole falls back to ROLE_USER when authority list is empty")
    void resolvePrimaryRole_emptyAuthorities_returnsUser() {
        when(authentication.getAuthorities()).thenAnswer(inv -> List.of());
        assertThat(resolver.resolvePrimaryRole(authentication)).isEqualTo("ROLE_USER");
    }

    @ParameterizedTest(name = "highest-priority role from {0} is {1}")
    @MethodSource("rolePriorityScenarios")
    @DisplayName("resolvePrimaryRole selects highest-priority role")
    void resolvePrimaryRole_selectsHighestPriority(List<String> roles, String expected) {
        givenAuthorities(roles.toArray(String[]::new));
        assertThat(resolver.resolvePrimaryRole(authentication)).isEqualTo(expected);
    }

    static Stream<Arguments> rolePriorityScenarios() {
        return Stream.of(
                Arguments.of(List.of("ROLE_SYSTEM_ADMINISTRATOR", "ROLE_ADMIN"), "ROLE_SYSTEM_ADMINISTRATOR"),
                Arguments.of(List.of("ROLE_ADMIN", "ROLE_LOCATION_MANAGER"), "ROLE_ADMIN"),
                Arguments.of(List.of("ROLE_LOCATION_MANAGER", "ROLE_ACCOUNT_MANAGER"), "ROLE_LOCATION_MANAGER"),
                Arguments.of(List.of("ROLE_ACCOUNT_MANAGER", "ROLE_SERVICE_ADVISOR"), "ROLE_ACCOUNT_MANAGER"),
                Arguments.of(List.of("ROLE_SERVICE_ADVISOR", "ROLE_DISPATCHER"), "ROLE_SERVICE_ADVISOR"),
                Arguments.of(List.of("ROLE_TECHNICIAN"), "ROLE_TECHNICIAN"));
    }

    @Test
    @DisplayName("resolvePrimaryRole ignores non-ROLE_ authorities")
    void resolvePrimaryRole_ignoresNonRoleAuthorities() {
        givenAuthorities("mcp:chat:execute", "ROLE_SERVICE_ADVISOR");
        assertThat(resolver.resolvePrimaryRole(authentication)).isEqualTo("ROLE_SERVICE_ADVISOR");
    }
}
