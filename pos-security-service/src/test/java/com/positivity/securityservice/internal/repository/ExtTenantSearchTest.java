package com.positivity.securityservice.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.securityservice.internal.dto.TenantSearchResponse;
import com.positivity.securityservice.internal.entity.ExtTenant;
import com.positivity.securityservice.internal.service.TenantSearchService;
import com.positivity.tenancy.replica.TenantDisplayName;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Exercises the organization search against a real database rather than a mock, so the JPQL — the
 * {@code ESCAPE} clauses in particular — is parsed and run, and the matching rule is pinned:
 * a prefix of the name or of any word in it, never an unanchored substring.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DisplayName("ExtTenant organization search")
class ExtTenantSearchTest {

    @Autowired
    private ExtTenantRepository extTenantRepository;

    @Autowired
    private TenantSearchService tenantSearchService;

    private void given(String slug, String displayName, String status) {
        extTenantRepository.save(ExtTenant.builder()
                .tenantId(UUID.randomUUID())
                .slug(slug)
                .displayName(displayName)
                .displayNameKey(displayName == null ? null : TenantDisplayName.normalize(displayName))
                .status(status)
                .aggregateVersion(1L)
                .updatedAt(Instant.now())
                .build());
    }

    /** Drives the real call path — normalize, escape, query — not just the repository method. */
    private List<String> search(String query) {
        return tenantSearchService.search(query).stream()
                .map(TenantSearchResponse::slug)
                .toList();
    }

    @BeforeEach
    void setUp() {
        extTenantRepository.deleteAll();
        given("acme-tire", "Acme Tire & Auto", "ACTIVE");
        given("acme-tucson", "Acme Tire & Auto — Tucson", "ACTIVE");
        given("bobs", "Bobs Tires", "ACTIVE");
        given("pending-co", "Acme Pending Co", "PENDING");
        given("suspended-co", "Acme Suspended Co", "SUSPENDED");
        given("nameless", null, "ACTIVE");
    }

    @Test
    @DisplayName("matches a prefix of the whole name")
    void matchesLeadingPrefix() {
        assertThat(search("acme")).containsExactlyInAnyOrder("acme-tire", "acme-tucson");
    }

    @Test
    @DisplayName("matches a prefix of any word in the name")
    void matchesWordPrefix() {
        // "Tires" in "Bobs Tires" starts with "tire" too — a word prefix, so it is offered.
        assertThat(search("tire")).containsExactlyInAnyOrder("acme-tire", "acme-tucson", "bobs");
        assertThat(search("tucson")).containsExactly("acme-tucson");
    }

    @Test
    @DisplayName("does not match an unanchored substring")
    void doesNotMatchMidWord() {
        // "cme" sits inside "Acme" but starts no word, so the directory cannot be swept by fragment.
        assertThat(search("cme")).isEmpty();
        assertThat(search("ucson")).isEmpty();
    }

    @Test
    @DisplayName("is case- and whitespace-insensitive, like the stored key")
    void ignoresCaseAndSpacing() {
        assertThat(search("  ACME   Tire ")).isEqualTo(search("acme tire"));
        assertThat(search("  ACME   Tire ")).containsExactlyInAnyOrder("acme-tire", "acme-tucson");
    }

    @Test
    @DisplayName("offers only ACTIVE tenants")
    void skipsNonActiveTenants() {
        assertThat(search("acme")).doesNotContain("pending-co", "suspended-co");
    }

    @Test
    @DisplayName("a wildcard query matches the character, not every organization")
    void wildcardMatchesLiterally() {
        // Below the 3-character minimum these never reach the database; the longer ones do.
        assertThat(search("%%%")).isEmpty();
        assertThat(search("%%%%%")).isEmpty();
        assertThat(search("___")).isEmpty();

        given("literal", "100% Tire", "ACTIVE");
        assertThat(search("100%")).containsExactly("literal");
    }

    @Test
    @DisplayName("shortest name first, so the exact match leads")
    void ordersShortestFirst() {
        assertThat(search("acme")).startsWith("acme-tire");
    }

    @Test
    @DisplayName("a projection with no display name is never offered")
    void skipsRowsWithoutADisplayName() {
        assertThat(search("acme")).doesNotContain("nameless");
    }
}
