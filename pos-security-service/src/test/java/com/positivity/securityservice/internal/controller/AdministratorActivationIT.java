package com.positivity.securityservice.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasLength;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.securityservice.BaseContractIntegrationTest;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.repository.RoleAssignmentRepository;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.repository.UserActivationTokenRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.internal.service.AdministratorActivationService;
import com.positivity.securityservice.internal.service.UserService;
import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantHeaders;
import com.positivity.tenancy.web.TenantContextFilter;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;

/**
 * End-to-end proof of first-administrator activation (ADR-0062 §7, plan WS2b-3) on the H2 test
 * profile, through the real filter chain, security configuration, exception handlers and
 * transactions: a user created awaiting activation cannot sign in (the same 401 as a wrong
 * password), a platform-bound caller holding {@code platform:tenant:provision} mints a token over
 * HTTP, the unauthenticated activation exchanges it for a password, login then succeeds, and the
 * token is dead afterwards. A caller bound to the alpha tenant is refused with 403
 * {@code PLATFORM_TENANT_REQUIRED} even with the permission.
 *
 * <p>Runs under {@code verify} (failsafe). The tenant is the transitional default the H2 profile
 * binds on every request; the platform binding is asserted through {@code X-Tenant-Id}, which the
 * gateway sets from the token's {@code tid} claim in production.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("AdministratorActivationIT — ADR-0062 §7, WS2b-3")
class AdministratorActivationIT extends BaseContractIntegrationTest {

    private static final String USERNAME = "ws2b3.owner@acme.example";
    private static final String NEW_PASSWORD = "Sup3rS3cret!";
    private static final String PLATFORM_AUTHORITIES = "ROLE_PLATFORM_ADMIN,platform:tenant:provision";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private RoleAssignmentRepository roleAssignmentRepository;

    @Autowired
    private UserActivationTokenRepository tokenRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private AdministratorActivationService activationService;

    @Autowired
    private TenantContextFilter tenantContextFilter;

    @Value("${pos.tenancy.default-tenant-id}")
    private UUID defaultTenant;

    private UUID userId;

    /**
     * {@code webAppContextSetup} carries only the security chain, so the tenancy filter that turns
     * {@code X-Tenant-Id} into a binding (and clears it afterwards) is added here, ahead of Spring
     * Security as in the servlet container (order -110 before -100).
     */
    @BeforeEach
    void addTenantFilter() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .defaultRequest(withAuth(get("/")))
                .addFilters(tenantContextFilter)
                .apply(springSecurity())
                .build();
    }

    @BeforeEach
    void seedAdministratorAwaitingActivation() {
        if (!roleRepository.existsByName("ADMIN")) {
            Role role = new Role();
            role.setName("ADMIN");
            role.setDescription("WS2b-3 test role");
            role.setCreatedBy("ws2b-3-test");
            roleRepository.save(role);
        }
        deleteUserIfPresent();
        userId = userService
                .createUserAwaitingActivation(USERNAME, Set.of("ADMIN"))
                .getId();
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
        deleteUserIfPresent();
    }

    private void deleteUserIfPresent() {
        userRepository.findByUsername(USERNAME).ifPresent(user -> {
            tokenRepository.deleteAll(
                    tokenRepository.findByTenantIdAndUserIdAndUsedAtIsNull(defaultTenant, user.getId()));
            roleAssignmentRepository.deleteAll(roleAssignmentRepository.findByUser(user));
            userRepository.delete(user);
        });
    }

    private String mintPath() {
        return "/v1/platform/tenants/" + defaultTenant + "/administrators/" + userId + "/activation-token";
    }

    @Test
    @DisplayName("provisioned administrator: no login, mint from the platform tenant, activate, login, token dead")
    void activationRoundTrip() throws Exception {
        User before = userRepository.findById(userId).orElseThrow();
        assertThat(before.isCredentialsNonExpired()).isFalse();

        // 1. Nothing can sign in: the generated password was discarded, so this is a plain bad-password 401.
        login("anything-at-all")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        // 2. A platform operator mints the token over HTTP; the response is the only copy.
        String body = mockMvc.perform(withAuth(post(mintPath()), PLATFORM_AUTHORITIES)
                        .header(TenantHeaders.HTTP_TENANT_ID, PlatformTenant.ID.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token", hasLength(43)))
                .andExpect(jsonPath("$.expiresAt").isString())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String token = objectMapper.readTree(body).path("token").stringValue();
        assertThat(tokenRepository.findByTokenHash(AdministratorActivationService.hash(token)))
                .as("only the hash is stored")
                .isPresent();
        assertThat(tokenRepository.findAll()).noneMatch(row -> token.equals(row.getTokenHash()));

        // 3. Still no login: a token is not a password.
        login(token)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        // 4. Activation is unauthenticated and needs no tenant header.
        activate(token, NEW_PASSWORD).andExpect(status().isNoContent());
        User after = userRepository.findById(userId).orElseThrow();
        assertThat(after.isCredentialsNonExpired()).isTrue();
        assertThat(after.getCredentialsExpireAt()).isNull();

        // 5. The administrator signs in with the password they chose.
        login(NEW_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString());

        // 6. The token is dead: reuse is the same refusal as a bad token.
        activate(token, "An0therOne!")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACTIVATION_TOKEN_INVALID"));
        login("An0therOne!").andExpect(status().isUnauthorized());
        login(NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("minting again closes the earlier token; a tenant-bound caller is refused even with the permission")
    void secondMintClosesTheFirstAndTenantCallersAreRefused() throws Exception {
        String first = TenantContext.callAs(PlatformTenant.ID, () -> activationService.mint(defaultTenant, userId))
                .token();
        String second = TenantContext.callAs(PlatformTenant.ID, () -> activationService.mint(defaultTenant, userId))
                .token();

        activate(first, NEW_PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACTIVATION_TOKEN_INVALID"));
        activate(second, NEW_PASSWORD).andExpect(status().isNoContent());

        // The alpha tenant's own administrator, permission or not, cannot mint: platform tenant only.
        mockMvc.perform(withAuth(post(mintPath()), PLATFORM_AUTHORITIES)
                        .header(TenantHeaders.HTTP_TENANT_ID, defaultTenant.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLATFORM_TENANT_REQUIRED"));
        // And without the permission, the platform binding alone is not enough.
        mockMvc.perform(withAuth(post(mintPath()), "ROLE_ADMIN,security:user:edit")
                        .header(TenantHeaders.HTTP_TENANT_ID, PlatformTenant.ID.toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("unknown, malformed and blank tokens are one refusal; a blank password is a 400")
    void badInputs() throws Exception {
        activate("definitely-not-a-token", NEW_PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACTIVATION_TOKEN_INVALID"));
        activate("", NEW_PASSWORD).andExpect(status().isBadRequest());
        activate("abc", " ").andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.ResultActions login(String password) throws Exception {
        JsonNode body =
                objectMapper.createObjectNode().put("username", USERNAME).put("password", password);
        return mockMvc.perform(post("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private org.springframework.test.web.servlet.ResultActions activate(String token, String newPassword)
            throws Exception {
        JsonNode body = objectMapper.createObjectNode().put("token", token).put("newPassword", newPassword);
        return mockMvc.perform(post("/v1/auth/activate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}
