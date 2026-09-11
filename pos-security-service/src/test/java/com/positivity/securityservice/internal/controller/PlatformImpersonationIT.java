package com.positivity.securityservice.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.securityservice.BaseContractIntegrationTest;
import com.positivity.securityservice.internal.domain.PermissionBitsetCodec;
import com.positivity.securityservice.internal.entity.AuditLogEvent;
import com.positivity.securityservice.internal.entity.ExtTenant;
import com.positivity.securityservice.internal.entity.JwtToken;
import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.enums.PermissionCode;
import com.positivity.securityservice.internal.repository.AuditLogEventRepository;
import com.positivity.securityservice.internal.repository.ExtTenantRepository;
import com.positivity.securityservice.internal.repository.JwtTokenRepository;
import com.positivity.securityservice.internal.repository.PermissionRepository;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.internal.security.service.JwtService;
import com.positivity.securityservice.internal.service.PlatformImpersonationService;
import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantHeaders;
import com.positivity.tenancy.web.TenantContextFilter;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;

/**
 * End-to-end proof of platform support access (ADR-0062 §7, plan WS2b-4) on the H2 test profile,
 * through the real filter chain, security configuration, exception handlers and transactions: a
 * platform-bound caller holding {@code platform:tenant:impersonate} mints a token over HTTP; the
 * token carries {@code tid} = the target tenant and the tenant's own {@code SUPPORT} grants; a
 * tenant-scoped read made with the headers the gateway derives from it answers inside that tenant;
 * the module's own validation accepts it; the refresh exchange refuses it; and the issuance is
 * audited in both tenants. A tenant-bound caller is refused with 403 {@code PLATFORM_TENANT_REQUIRED}
 * even with the permission; an unknown tenant is 404 and a suspended one 409.
 *
 * <p>Runs under {@code verify} (failsafe). The target is the transitional default tenant the H2
 * profile binds on every request; the platform binding is asserted through {@code X-Tenant-Id},
 * which the gateway sets from the token's {@code tid} claim in production.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("PlatformImpersonationIT — ADR-0062 §7, WS2b-4")
class PlatformImpersonationIT extends BaseContractIntegrationTest {

    private static final String OPERATOR = "ws2b4.operator";
    private static final String PLATFORM_AUTHORITIES = "ROLE_PLATFORM_ADMIN,platform:tenant:impersonate";
    private static final UUID SUSPENDED_TENANT = UUID.fromString("01990000-0000-7000-8000-00000000dead");
    private static final UUID UNKNOWN_TENANT = UUID.fromString("01990000-0000-7000-8000-00000000beef");
    private static final List<String> SUPPORT_GRANTS = List.of("crm:party:view", "order:order:view");

    /** A write a tenant administrator added to SUPPORT: never on the token (SupportReadOnlyCeiling). */
    private static final String WIDENED_GRANT = "security:user:delete";

    @Autowired
    private ExtTenantRepository extTenantRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenRepository jwtTokenRepository;

    @Autowired
    private AuditLogEventRepository auditLogEventRepository;

    @Autowired
    private TenantContextFilter tenantContextFilter;

    @Value("${pos.tenancy.default-tenant-id}")
    private UUID targetTenant;

    @Value("${security.jwt.secret}")
    private String jwtSecret;

    private UUID operatorId;

    /**
     * {@code webAppContextSetup} carries only the security chain, so the tenancy filter that turns
     * {@code X-Tenant-Id} into a binding (and clears it afterwards) is added here, ahead of Spring
     * Security as in the servlet container. No default authentication headers: every request below
     * says exactly which gateway headers it carries.
     */
    @BeforeEach
    void addTenantFilter() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(tenantContextFilter)
                .apply(springSecurity())
                .build();
    }

    @BeforeEach
    void seed() {
        extTenantRepository.save(ExtTenant.builder()
                .tenantId(targetTenant)
                .slug("alpha")
                .displayName("Alpha")
                .status("ACTIVE")
                .aggregateVersion(1)
                .updatedAt(Instant.parse("2026-09-10T00:00:00Z"))
                .build());
        extTenantRepository.save(ExtTenant.builder()
                .tenantId(SUSPENDED_TENANT)
                .slug("dormant")
                .displayName("Dormant")
                .status("SUSPENDED")
                .aggregateVersion(2)
                .updatedAt(Instant.parse("2026-09-10T00:00:00Z"))
                .build());
        TenantContext.runAs(targetTenant, () -> {
            if (!roleRepository.existsByName(PlatformImpersonationService.SUPPORT_ROLE)) {
                Role support = new Role();
                support.setName(PlatformImpersonationService.SUPPORT_ROLE);
                support.setTemplateKey(PlatformImpersonationService.SUPPORT_ROLE);
                support.setDescription("WS2b-4 test role");
                support.setCreatedBy("ws2b-4-test");
                support.setMcpPersonaEligible(false);
                for (String name : SUPPORT_GRANTS) {
                    support.getPermissions().add(permission(name));
                }
                support.getPermissions().add(permission(WIDENED_GRANT));
                roleRepository.save(support);
            }
        });
        TenantContext.runAs(PlatformTenant.ID, () -> {
            User operator = userRepository.findByUsername(OPERATOR).orElseGet(() -> {
                User user = new User();
                user.setUsername(OPERATOR);
                user.setPassword("{noop}not-a-real-password");
                return userRepository.save(user);
            });
            operatorId = operator.getId();
        });
        TenantContext.clear();
    }

    private Permission permission(String name) {
        return permissionRepository.findByName(name).orElseGet(() -> {
            PermissionCode code = PermissionCode.fromCode(name).orElseThrow();
            String[] parts = name.split(":");
            Permission permission = new Permission();
            permission.setName(name);
            permission.setDescription("WS2b-4 test permission");
            permission.setDomain(parts[0]);
            permission.setResource(parts[1]);
            permission.setAction(parts[2]);
            permission.setRegisteredAt(Instant.parse("2026-09-10T00:00:00Z"));
            permission.setRegisteredByService("ws2b-4-test");
            permission.setBitIndex(code.bitIndex());
            return permissionRepository.save(permission);
        });
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
        TenantContext.runAs(
                targetTenant,
                () -> jwtTokenRepository.deleteAll(
                        jwtTokenRepository.findAllBySubject("support:" + OPERATOR + "@alpha")));
        TenantContext.clear();
    }

    private static String mintPath(UUID tenantId) {
        return "/v1/platform/tenants/" + tenantId + "/impersonation-token";
    }

    private static MockHttpServletRequestBuilder asGatewayUser(
            MockHttpServletRequestBuilder builder, String user, String authorities, UUID boundTenant) {
        return builder.header("X-User", user)
                .header("X-Authorities", authorities)
                .header(TenantHeaders.HTTP_TENANT_ID, boundTenant.toString());
    }

    private ResultActions mintAs(String user, String authorities, UUID boundTenant, UUID target) throws Exception {
        return mockMvc.perform(asGatewayUser(post(mintPath(target)), user, authorities, boundTenant)
                .header("X-Correlation-Id", "corr-ws2b-4"));
    }

    private Claims claims(String token) {
        return Jwts.parser()
                .verifyWith(new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    @Test
    @DisplayName("mint from the platform tenant → read inside the target tenant with the token → refresh refused")
    void impersonationRoundTrip() throws Exception {
        // 1. A platform operator mints the token over HTTP; the response is the only copy.
        String body = mintAs(OPERATOR, PLATFORM_AUTHORITIES, PlatformTenant.ID, targetTenant)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.expiresAt").isString())
                .andExpect(jsonPath("$.tenantId").value(targetTenant.toString()))
                .andExpect(jsonPath("$.tenantSlug").value("alpha"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode response = objectMapper.readTree(body);
        String token = response.path("token").stringValue();
        Instant expiresAt = Instant.parse(response.path("expiresAt").stringValue());

        // 2. The claims the gateway will read: tid = the target, SUPPORT grants, the operator as act.
        Claims claims = claims(token);
        assertThat(claims.getSubject()).isEqualTo("support:" + OPERATOR + "@alpha");
        assertThat(claims.get(JwtService.TID, String.class)).isEqualTo(targetTenant.toString());
        assertThat(claims.get(JwtService.UID, String.class)).isEqualTo(operatorId.toString());
        assertThat(claims.get(JwtService.TOKEN_USE, String.class)).isEqualTo(JwtService.TOKEN_USE_IMPERSONATION);
        assertThat(claims.get(JwtService.ACT))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("sub", operatorId.toString())
                .containsEntry("username", OPERATOR);
        assertThat(claims.getExpiration().toInstant()).isEqualTo(expiresAt);
        assertThat(expiresAt.minusSeconds(900)).isEqualTo(claims.getIssuedAt().toInstant());
        String permBits = claims.get(JwtService.PERM_BITS, String.class);
        assertThat(PermissionBitsetCodec.decodeToPermissions(permBits, PermissionCode.CATALOG_VERSION))
                .extracting(PermissionCode::code)
                .as("the SUPPORT role's reads, and not the write the tenant added to it")
                .containsExactlyInAnyOrderElementsOf(SUPPORT_GRANTS)
                .doesNotContain(WIDENED_GRANT);

        // 3. Stored under the target tenant, with no refresh half.
        JwtToken stored = TenantContext.callAs(
                targetTenant, () -> jwtTokenRepository.findByToken(token).orElseThrow());
        assertThat(stored.getRefreshToken()).isNull();
        assertThat(stored.getRefreshExpiresAt()).isNull();
        assertThat(stored.getSubject()).isEqualTo("support:" + OPERATOR + "@alpha");
        assertThat(TenantContext.callAs(PlatformTenant.ID, () -> jwtTokenRepository.findByToken(token)))
                .as("not visible from the platform tenant")
                .isEmpty();

        // 4. The module's own validation accepts it with no tenant bound: tid drives the lookup.
        mockMvc.perform(get("/v1/auth/validate").param("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        // 5. A tenant-scoped read with exactly the headers the gateway derives from the token
        //    answers inside the target tenant, with the SUPPORT grants as the caller's authorities.
        mockMvc.perform(get("/v1/tenants/me")
                        .header("X-User", claims.getSubject())
                        .header("X-Perm-Bits", permBits)
                        .header("X-Perm-Ver", String.valueOf(PermissionCode.CATALOG_VERSION))
                        .header(TenantHeaders.HTTP_TENANT_ID, claims.get(JwtService.TID, String.class)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(targetTenant.toString()))
                .andExpect(jsonPath("$.slug").value("alpha"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 6. There is no refresh: the token presented as one is refused outright.
        JsonNode refresh = objectMapper.createObjectNode().put("refreshToken", token);
        mockMvc.perform(post("/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refresh)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
        assertThat(TenantContext.callAs(targetTenant, () -> jwtTokenRepository.findByToken(token)))
                .as("the refused refresh revoked nothing")
                .isPresent();

        // 7. Audited on both sides, naming the operator, the tenant, the expiry and the correlation id.
        for (UUID tenant : List.of(targetTenant, PlatformTenant.ID)) {
            List<AuditLogEvent> events = TenantContext.callAs(
                    tenant,
                    () -> auditLogEventRepository.findByEventTypeOrderByTimestampDesc(
                            PlatformImpersonationService.AUDIT_EVENT_TYPE));
            assertThat(events).as("audit event in tenant %s", tenant).isNotEmpty();
            AuditLogEvent event = events.getFirst();
            assertThat(event.getActorId()).isEqualTo(OPERATOR);
            assertThat(event.getEntityId()).isEqualTo(targetTenant.toString());
            assertThat(event.getEntityType()).isEqualTo("Tenant");
            assertThat(event.getNewValue()).contains(expiresAt.toString());
            assertThat(event.getContext())
                    .contains("corr-ws2b-4")
                    .contains("droppedGrants")
                    .contains(WIDENED_GRANT)
                    .contains(claims.getId())
                    .contains("support:" + OPERATOR + "@alpha")
                    .doesNotContain(token);
        }
    }

    @Test
    @DisplayName(
            "a tenant-bound caller is refused even with the permission; without it the platform binding is not enough")
    void tenantCallersAreRefused() throws Exception {
        mintAs(OPERATOR, PLATFORM_AUTHORITIES, targetTenant, targetTenant)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLATFORM_TENANT_REQUIRED"));
        mintAs(OPERATOR, "ROLE_PLATFORM_ADMIN,platform:tenant:provision", PlatformTenant.ID, targetTenant)
                .andExpect(status().isForbidden());
        assertThat(TenantContext.callAs(
                        targetTenant, () -> jwtTokenRepository.findAllBySubject("support:" + OPERATOR + "@alpha")))
                .isEmpty();
    }

    @Test
    @DisplayName("an unknown tenant is 404 TENANT_NOT_FOUND; a suspended one and the platform tenant are 409")
    void unknownAndInactiveTenants() throws Exception {
        mintAs(OPERATOR, PLATFORM_AUTHORITIES, PlatformTenant.ID, UNKNOWN_TENANT)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TENANT_NOT_FOUND"));
        mintAs(OPERATOR, PLATFORM_AUTHORITIES, PlatformTenant.ID, SUSPENDED_TENANT)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TENANT_NOT_IMPERSONABLE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("SUSPENDED")));
        mintAs(OPERATOR, PLATFORM_AUTHORITIES, PlatformTenant.ID, PlatformTenant.ID)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TENANT_NOT_IMPERSONABLE"));
    }

    @Test
    @DisplayName("an operator without a platform-tenant user row cannot mint")
    void unknownOperatorIsRefused() throws Exception {
        mintAs("nobody.platform", PLATFORM_AUTHORITIES, PlatformTenant.ID, targetTenant)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }
}
