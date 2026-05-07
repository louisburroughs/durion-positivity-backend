package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.positivity.mcp.service.CurrentUserContext;
import com.positivity.mcp.service.McpRoleResolver;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.security.common.MissingPersonIdException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class CurrentUserContextResolverTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-7000-8000-000000000111");

    @Mock
    private McpRoleResolver mcpRoleResolver;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("resolve returns authenticated username, UUID, roles, and authorities")
    void resolve_returnsAuthenticatedUserContext() {
        Authentication authentication = authentication(USER_ID);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(mcpRoleResolver.resolvePrimaryRole(authentication)).thenReturn("ROLE_ADMIN");

        CurrentUserContextResolver resolver = new CurrentUserContextResolver(mcpRoleResolver);
        CurrentUserContext context = resolver.resolve(authentication);

        assertThat(context.username()).isEqualTo("admin.alpha");
        assertThat(context.userId()).isEqualTo(USER_ID);
        assertThat(context.primaryRole()).isEqualTo("ROLE_ADMIN");
        assertThat(context.roles()).containsExactly("ROLE_ADMIN");
        assertThat(context.authorities()).contains("ROLE_ADMIN", "mcp:chat:execute");
    }

    @Test
    @DisplayName("resolve fails when authenticated UUID is missing")
    void resolve_withoutUserId_throwsMissingPersonIdException() {
        Authentication authentication = authenticationWithoutUserId();
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(mcpRoleResolver.resolvePrimaryRole(authentication)).thenReturn("ROLE_USER");

        CurrentUserContextResolver resolver = new CurrentUserContextResolver(mcpRoleResolver);

        assertThatThrownBy(() -> resolver.resolve(authentication)).isInstanceOf(MissingPersonIdException.class);
    }

    private static Authentication authentication(UUID userId) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                "admin.alpha",
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("mcp:chat:execute")));
        authentication.setDetails(Map.of(
                GatewaySecurityConstants.DETAIL_USERNAME,
                "admin.alpha",
                GatewaySecurityConstants.DETAIL_USER_ID,
                userId));
        return authentication;
    }

    private static Authentication authenticationWithoutUserId() {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                "operator.alpha", "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER")));
        authentication.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, "operator.alpha"));
        return authentication;
    }
}
