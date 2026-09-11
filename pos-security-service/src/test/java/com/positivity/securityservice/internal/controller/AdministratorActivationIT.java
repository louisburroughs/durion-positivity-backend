package com.positivity.securityservice.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasLength;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.securityservice.BaseContractIntegrationTest;
import com.positivity.securityservice.internal.dto.UserUpdateRequest;
import com.positivity.securityservice.internal.entity.ExtTenant;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.entity.UserActivationToken;
import com.positivity.securityservice.internal.exception.ActivationTokenInvalidException;
import com.positivity.securityservice.internal.repository.AuditLogEventRepository;
import com.positivity.securityservice.internal.repository.ExtTenantRepository;
import com.positivity.securityservice.internal.repository.RoleAssignmentRepository;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.repository.UserActivationTokenRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.internal.security.service.JwtService;
import com.positivity.securityservice.internal.service.AdministratorActivationService;
import com.positivity.securityservice.internal.service.UserService;
import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantHeaders;
import com.positivity.tenancy.web.TenantContextFilter;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
    private AuditLogEventRepository auditLogEventRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private AdministratorActivationService activationService;

    @Autowired
    private AdministratorActivationService.BoundOperations boundOperations;

    @Autowired
    private ExtTenantRepository extTenantRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private PlatformTransactionManager transactionManager;

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
            tokenRepository.deleteAll(tokenRepository.findAll().stream()
                    .filter(row -> user.getId().equals(row.getUserId()))
                    .toList());
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
        assertThat(before.isAwaitingActivation()).as("provisioning marker set").isTrue();

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
        // The mint audit row is written from an afterCommit callback in a dedicated REQUIRES_NEW
        // transaction (AdministratorActivationService.MintAuditWriter); querying it back here,
        // after the HTTP call that minted the token has already returned, is the proof that the
        // insert actually committed rather than silently joining the already-completed mint
        // transaction and being discarded.
        assertThat(auditLogEventRepository.findByEventTypeOrderByTimestampDesc("AdministratorActivationTokenMinted"))
                .as("the mint audit row is durable")
                .anyMatch(event -> userId.toString().equals(event.getEntityId()));

        // 3. Still no login: a token is not a password.
        login(token)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        // 4. Activation is unauthenticated and needs no tenant header.
        activate(token, NEW_PASSWORD).andExpect(status().isNoContent());
        User after = userRepository.findById(userId).orElseThrow();
        assertThat(after.isCredentialsNonExpired()).isTrue();
        assertThat(after.getCredentialsExpireAt()).isNull();
        assertThat(after.isAwaitingActivation())
                .as("provisioning marker cleared")
                .isFalse();

        // 5. The administrator signs in with the password they chose.
        login(NEW_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString());

        // 6. A live account cannot be re-issued a token: the operator's mint is 409 and the password stays.
        mockMvc.perform(withAuth(post(mintPath()), PLATFORM_AUTHORITIES)
                        .header(TenantHeaders.HTTP_TENANT_ID, PlatformTenant.ID.toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_NOT_AWAITING_ACTIVATION"));

        // 7. The token is dead: reuse is the same refusal as a bad token.
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

    @Test
    @DisplayName(
            "the consume update itself refuses an expired token, so a token expiring between check and update is not consumed")
    void consumeRefusesAnExpiredToken() {
        String token = "expired-in-the-race";
        UserActivationToken expired = tokenRepository.save(UserActivationToken.builder()
                .tenantId(defaultTenant)
                .userId(userId)
                .tokenHash(AdministratorActivationService.hash(token))
                .expiresAt(Instant.now().minusSeconds(1))
                .createdBy("ws2b-3-test")
                .createdAt(Instant.now().minusSeconds(3600))
                .build());

        // Straight at the transactional half, past the service's own expiry check.
        assertThatThrownBy(
                        () -> TenantContext.runAs(defaultTenant, () -> boundOperations.exchange(expired, NEW_PASSWORD)))
                .isInstanceOf(ActivationTokenInvalidException.class);

        assertThat(tokenRepository.findById(expired.getId()).orElseThrow().getUsedAt())
                .as("an expired token is never marked used")
                .isNull();
        assertThat(userRepository.findById(userId).orElseThrow().isCredentialsNonExpired())
                .as("the account is still awaiting activation")
                .isFalse();
        tokenRepository.delete(expired);
    }

    @Test
    @DisplayName("a token minted for the account before activation is revoked by activation, not usable afterwards")
    void preActivationTokenIsRevokedByActivation() throws Exception {
        // A trusted internal caller can mint an access token for the still-awaiting-activation
        // username with no password or awaiting-activation check at all
        // (JwtController#issueInternalToken / #generateTokenPair mint this way).
        String preActivationToken = jwtService.generateToken(USERNAME, userId, Set.of("ADMIN"));

        // Before activation: JwtAuthenticationFilter's account-status check rejects it — the same
        // 401 as a bad credential — because credentialsNonExpired is still false.
        mockMvc.perform(get("/v1/auth/subject").header("Authorization", "Bearer " + preActivationToken))
                .andExpect(status().isUnauthorized());

        String token = mintToken();
        activate(token, NEW_PASSWORD).andExpect(status().isNoContent());

        // After activation: without revocation this same token would now authenticate, since
        // credentialsNonExpired flipped true — bypassing the operator-delivered token the
        // administrator was required to exchange. It must still be refused.
        mockMvc.perform(get("/v1/auth/subject").header("Authorization", "Bearer " + preActivationToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName(
            "PUT /v1/users/{id} takes the same pessimistic lock mint/activate take, so it cannot race the exchange")
    void updateUserSerializesAgainstTheActivationLock() throws Exception {
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicLong releasedAtNanos = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong updateDoneAtNanos = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicReference<Throwable> updateFailure =
                new java.util.concurrent.atomic.AtomicReference<>();

        // Holds the same PESSIMISTIC_WRITE lock issue()/exchange() take, in its own open
        // transaction, until told to let go.
        Thread holder = new Thread(
                () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    userRepository.findByIdForUpdate(userId);
                    lockHeld.countDown();
                    try {
                        releaseLock.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }),
                "ws2b3-lock-holder");
        holder.start();
        assertThat(lockHeld.await(5, TimeUnit.SECONDS))
                .as("the holder thread took the pessimistic lock")
                .isTrue();

        UserUpdateRequest request = new UserUpdateRequest();
        request.setPassword("Ra3cedButSerialized!");
        Thread updater = new Thread(
                () -> {
                    try {
                        userService.updateUser(userId, request);
                    } catch (Throwable t) {
                        updateFailure.set(t);
                    } finally {
                        updateDoneAtNanos.set(System.nanoTime());
                    }
                },
                "ws2b3-concurrent-update-user");
        updater.start();

        // Give the updater a real chance to reach and block on the same row lock before releasing
        // it; the actual proof below is the happens-before ordering, not this sleep.
        Thread.sleep(500);
        releasedAtNanos.set(System.nanoTime());
        releaseLock.countDown();

        holder.join(10_000);
        updater.join(10_000);

        assertThat(updateFailure.get())
                .as("the update must succeed once it can finally take the lock")
                .isNull();
        assertThat(updateDoneAtNanos.get())
                .as("the password update cannot have completed before the activation-shaped lock was released — "
                        + "without findByIdForUpdate it would have read the row and raced past instead")
                .isGreaterThanOrEqualTo(releasedAtNanos.get());
    }

    @Test
    @DisplayName("activation with no X-Tenant-Id rebinds to the token's own tenant, not the request's ambient default")
    void activationRebindsToTheTokenTenantAcrossTenants() throws Exception {
        UUID otherTenant = UUID.fromString("01990000-0000-7000-8000-0000000000aa");
        assertThat(otherTenant)
                .as("distinct from the ambient default the H2 request filter binds on every unbound request")
                .isNotEqualTo(defaultTenant);
        String otherUsername = "ws2b3.other-tenant.owner@acme.example";
        extTenantRepository.save(ExtTenant.builder()
                .tenantId(otherTenant)
                .slug("ws2b3-other-tenant")
                .displayName("WS2b-3 other tenant")
                .status("ACTIVE")
                .aggregateVersion(1L)
                .updatedAt(Instant.now())
                .build());

        // A distinct role name, not "ADMIN": the H2 test schema maps Role#name with a plain unique
        // column (create-drop from the entity), while the real Postgres baseline scopes it
        // (tenant_id, name) (V1__baseline_security_service.sql); reusing "ADMIN" here would collide
        // with the row the outer @BeforeEach already created for defaultTenant.
        String otherTenantRoleName = "WS2B3_OTHER_TENANT_ADMIN";
        UUID otherUserId = TenantContext.callAs(otherTenant, () -> {
            if (!roleRepository.existsByName(otherTenantRoleName)) {
                Role role = new Role();
                role.setName(otherTenantRoleName);
                role.setDescription("WS2b-3 cross-tenant test role");
                role.setCreatedBy("ws2b-3-test");
                roleRepository.save(role);
            }
            return userService
                    .createUserAwaitingActivation(otherUsername, Set.of(otherTenantRoleName))
                    .getId();
        });

        try {
            String otherToken = TenantContext.callAs(
                            PlatformTenant.ID, () -> activationService.mint(otherTenant, otherUserId))
                    .token();

            // No X-Tenant-Id header at all: the H2 profile's request filter binds only the ambient
            // default (defaultTenant) here, a different tenant than the token's own. If activation
            // used that ambient binding instead of rebinding to row.getTenantId(), the pessimistic
            // lookup's Hibernate @TenantId filter would not find otherUserId's row, and this would
            // answer 401 ACTIVATION_TOKEN_INVALID instead.
            activate(otherToken, NEW_PASSWORD).andExpect(status().isNoContent());

            User activated = TenantContext.callAs(
                    otherTenant, () -> userRepository.findById(otherUserId).orElseThrow());
            assertThat(activated.isAwaitingActivation()).isFalse();
            assertThat(activated.isCredentialsNonExpired()).isTrue();

            // And the administrator can now sign in bound to that tenant via its slug, not the
            // ambient default.
            JsonNode loginBody = objectMapper
                    .createObjectNode()
                    .put("username", otherUsername)
                    .put("password", NEW_PASSWORD)
                    .put("tenantSlug", "ws2b3-other-tenant");
            mockMvc.perform(post("/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginBody)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").isString());
        } finally {
            TenantContext.runAs(otherTenant, () -> {
                tokenRepository.deleteAll(tokenRepository.findAll().stream()
                        .filter(row -> otherUserId.equals(row.getUserId()))
                        .toList());
                userRepository.findById(otherUserId).ifPresent(user -> {
                    roleAssignmentRepository.deleteAll(roleAssignmentRepository.findByUser(user));
                    userRepository.delete(user);
                });
            });
            extTenantRepository.deleteById(otherTenant);
        }
    }

    private String mintToken() throws Exception {
        String body = mockMvc.perform(withAuth(post(mintPath()), PLATFORM_AUTHORITIES)
                        .header(TenantHeaders.HTTP_TENANT_ID, PlatformTenant.ID.toString()))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).path("token").stringValue();
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
