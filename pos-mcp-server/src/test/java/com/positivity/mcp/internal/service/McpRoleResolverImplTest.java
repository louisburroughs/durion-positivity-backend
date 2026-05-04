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
    Collection<? extends GrantedAuthority> authorities = Stream.of(roles).map(SimpleGrantedAuthority::new).toList();
    when(authentication.getAuthorities()).thenAnswer(inv -> authorities);
  }

  @Test
  @DisplayName("resolvePrimaryRole returns ROLE_ADMIN when caller has admin authority")
  void resolvePrimaryRole_admin_returnsAdmin() {
    givenAuthorities("ROLE_ADMIN", "ROLE_CASHIER");
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
        Arguments.of(List.of("ROLE_MANAGER", "ROLE_CASHIER"), "ROLE_MANAGER"),
        Arguments.of(List.of("ROLE_SERVICE_WRITER", "ROLE_TECHNICIAN"), "ROLE_SERVICE_WRITER"),
        Arguments.of(List.of("ROLE_CASHIER", "ROLE_SUPPLIER"), "ROLE_CASHIER"),
        Arguments.of(List.of("ROLE_SUPPLIER", "ROLE_TECHNICIAN"), "ROLE_SUPPLIER"),
        Arguments.of(List.of("ROLE_TECHNICIAN"), "ROLE_TECHNICIAN"));
  }

  @Test
  @DisplayName("resolvePrimaryRole ignores non-ROLE_ authorities")
  void resolvePrimaryRole_ignoresNonRoleAuthorities() {
    givenAuthorities("mcp:chat:execute", "ROLE_CASHIER");
    assertThat(resolver.resolvePrimaryRole(authentication)).isEqualTo("ROLE_CASHIER");
  }
}
