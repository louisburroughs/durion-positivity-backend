package com.positivity.securityservice.internal.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.securityservice.BaseContractIntegrationTest;
import com.positivity.securityservice.internal.entity.ExtTenant;
import com.positivity.securityservice.internal.repository.ExtTenantRepository;
import com.positivity.tenancy.replica.TenantDisplayName;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Drives {@code GET /v1/auth/tenants} through the real filter chain (ADR-0062 §3).
 *
 * <p>The service tests do not execute that chain, and the chain is where this endpoint can fail
 * without any of them noticing: {@code authSecurityFilterChain} matches {@code /v1/auth/**} and
 * ends in {@code anyRequest().authenticated()}, so a path missing from its allowlist is refused
 * before the controller is reached and {@code @PreAuthorize("permitAll()")} never gets a say. That
 * is exactly how {@code /v1/auth/activate-starter} once shipped answering 401 to everyone.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("GET /v1/auth/tenants is reachable without a token")
class TenantSearchControllerIT extends BaseContractIntegrationTest {

    @Autowired
    private ExtTenantRepository extTenantRepository;

    @BeforeEach
    void seedDirectory() {
        extTenantRepository.save(ExtTenant.builder()
                .tenantId(UUID.randomUUID())
                .slug("acme-tire")
                .displayName("Acme Tire & Auto")
                .displayNameKey(TenantDisplayName.normalize("Acme Tire & Auto"))
                .status("ACTIVE")
                .aggregateVersion(1L)
                .updatedAt(Instant.now())
                .build());
    }

    @Test
    @DisplayName("an anonymous caller gets matches, not a 401")
    void anonymousCallerIsServed() throws Exception {
        mockMvc.perform(get("/v1/auth/tenants").param("q", "acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slug").value("acme-tire"))
                .andExpect(jsonPath("$[0].displayName").value("Acme Tire & Auto"));
    }

    @Test
    @DisplayName("the response carries nothing but the name and the slug")
    void responseLeaksNothingElse() throws Exception {
        mockMvc.perform(get("/v1/auth/tenants").param("q", "acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tenantId").doesNotExist())
                .andExpect(jsonPath("$[0].status").doesNotExist());
    }

    @Test
    @DisplayName("a query below the minimum is answered, empty, rather than refused")
    void shortQueryIsAnsweredEmpty() throws Exception {
        mockMvc.perform(get("/v1/auth/tenants").param("q", "ac"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("a missing query is answered, empty, rather than refused")
    void missingQueryIsAnsweredEmpty() throws Exception {
        mockMvc.perform(get("/v1/auth/tenants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }
}
