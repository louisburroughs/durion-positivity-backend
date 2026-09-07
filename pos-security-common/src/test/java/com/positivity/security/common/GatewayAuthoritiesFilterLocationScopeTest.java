package com.positivity.security.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.domainevents.location.LocationAncestry.AncestorSets;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.BitSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The location-scope half of {@link GatewayAuthoritiesFilter} (#1870): the three {@code X-Loc-*}
 * headers become one {@link LocationScope} in the authentication details, decoded through the
 * same bitset path and catalog as {@code X-Perm-Bits}.
 */
@DisplayName("GatewayAuthoritiesFilter — decoding X-Loc-* into a LocationScope")
class GatewayAuthoritiesFilterLocationScopeTest {

    private static final UUID SHOP = UUID.fromString("00000000-0000-7000-8000-000000000003");
    private static final UUID REGION = UUID.fromString("00000000-0000-7000-8000-000000000001");

    // Bits 27/28 are crm:party:view / crm:party:search in DownstreamPermissionCatalog, and bit 0 is
    // accounting:je:view; the tests below assert by name so a catalog append cannot move them.
    private static final int BIT_PARTY_VIEW = 27;
    private static final int BIT_PARTY_SEARCH = 28;
    private static final int BIT_JE_VIEW = 0;

    private static final LocationAncestorResolver RESOLVER = locationId ->
            SHOP.equals(locationId) ? new AncestorSets(Set.of(SHOP), Set.of(SHOP, REGION)) : AncestorSets.EMPTY;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static String bits(int... indexes) {
        BitSet bitSet = new BitSet();
        for (int index : indexes) {
            bitSet.set(index);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bitSet.toByteArray());
    }

    private static String scopeHeader(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String scopeHeaderFor(UUID... nodes) {
        StringBuilder json = new StringBuilder("{\"v\":1,\"nodes\":[");
        for (int i = 0; i < nodes.length; i++) {
            json.append(i == 0 ? "" : ",").append('"').append(nodes[i]).append('"');
        }
        return scopeHeader(json.append("]}").toString());
    }

    private static MockHttpServletRequest permBitsRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/workorders/wip");
        request.addHeader(GatewaySecurityConstants.HEADER_USER, "alice");
        request.addHeader(GatewaySecurityConstants.HEADER_PERM_BITS, bits(BIT_PARTY_VIEW, BIT_JE_VIEW));
        request.addHeader(
                GatewaySecurityConstants.HEADER_PERM_VER, String.valueOf(DownstreamPermissionCatalog.CATALOG_VERSION));
        return request;
    }

    private static LocationScope run(GatewayAuthoritiesFilter filter, MockHttpServletRequest request)
            throws ServletException, IOException {
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        Object scope = ((Map<?, ?>) authentication.getDetails()).get(GatewaySecurityConstants.DETAIL_LOCATION_SCOPE);
        assertThat(scope).isInstanceOf(LocationScope.class);
        return (LocationScope) scope;
    }

    @Test
    @DisplayName("no X-Loc-* headers → the unscoped, permissive scope (pre-rollout gateway)")
    void absentHeadersGiveUnscoped() throws Exception {
        LocationScope scope = run(new GatewayAuthoritiesFilter(RESOLVER), permBitsRequest());

        assertThat(scope.claimsPresent()).isFalse();
        assertThat(scope.covers("crm:party:view", UUID.randomUUID())).isTrue();
    }

    @Test
    @DisplayName("legacy X-Authorities requests carry the unscoped scope too")
    void legacyAuthoritiesPathIsUnscoped() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/customers");
        request.addHeader(GatewaySecurityConstants.HEADER_USER, "alice");
        request.addHeader(GatewaySecurityConstants.HEADER_AUTHORITIES, "PERM_crm:party:view");
        // Even with scope headers present, the legacy path predates the claims and X-Perm-Ver
        // (which the scope bitsets depend on) was never validated.
        request.addHeader(GatewaySecurityConstants.HEADER_LOC_OTH_BITS, bits(BIT_PARTY_VIEW));

        LocationScope scope = run(new GatewayAuthoritiesFilter(RESOLVER), request);

        assertThat(scope.claimsPresent()).isFalse();
    }

    @Test
    @DisplayName("both bitsets decode through the catalog to plain permission names on the right dimension")
    void bitsetsDecodeToPermissionNames() throws Exception {
        MockHttpServletRequest request = permBitsRequest();
        request.addHeader(GatewaySecurityConstants.HEADER_LOC_FIN_BITS, bits(BIT_JE_VIEW));
        request.addHeader(GatewaySecurityConstants.HEADER_LOC_OTH_BITS, bits(BIT_PARTY_VIEW, BIT_PARTY_SEARCH));
        request.addHeader(GatewaySecurityConstants.HEADER_LOC_SCOPE, scopeHeaderFor(REGION));

        LocationScope scope = run(new GatewayAuthoritiesFilter(RESOLVER), request);

        assertThat(scope.claimsPresent()).isTrue();
        assertThat(scope.financialScoped()).containsExactly("accounting:je:view");
        assertThat(scope.otherScoped()).containsExactly("crm:party:view", "crm:party:search");
        assertThat(scope.nodes()).contains(Set.of(REGION));
        // End to end: REGION is an OTHER-ancestor of SHOP, so the OTHER-scoped permission covers it
        // and the FINANCIAL-scoped one does not.
        assertThat(scope.covers("crm:party:view", SHOP)).isTrue();
        assertThat(scope.covers("PERM_crm:party:view", SHOP)).isTrue();
        assertThat(scope.covers("accounting:je:view", SHOP)).isFalse();
    }

    @Test
    @DisplayName("empty-string bitsets → claims present, every permission global")
    void emptyBitsetsAreGlobal() throws Exception {
        MockHttpServletRequest request = permBitsRequest();
        request.addHeader(GatewaySecurityConstants.HEADER_LOC_FIN_BITS, "");
        request.addHeader(GatewaySecurityConstants.HEADER_LOC_OTH_BITS, "");

        LocationScope scope = run(new GatewayAuthoritiesFilter(RESOLVER), request);

        assertThat(scope.claimsPresent()).isTrue();
        assertThat(scope.financialScoped()).isEmpty();
        assertThat(scope.otherScoped()).isEmpty();
        assertThat(scope.nodes()).isEmpty();
        assertThat(scope.covers("crm:party:view", UUID.randomUUID())).isTrue();
    }

    @Test
    @DisplayName("bits present, X-Loc-Scope absent → scoped permissions deny (the issuer's fail-closed signal)")
    void bitsWithoutScopeDeny() throws Exception {
        MockHttpServletRequest request = permBitsRequest();
        request.addHeader(GatewaySecurityConstants.HEADER_LOC_FIN_BITS, "");
        request.addHeader(GatewaySecurityConstants.HEADER_LOC_OTH_BITS, bits(BIT_PARTY_VIEW));

        LocationScope scope = run(new GatewayAuthoritiesFilter(RESOLVER), request);

        assertThat(scope.nodes()).isEmpty();
        assertThat(scope.covers("crm:party:view", SHOP)).isFalse();
        assertThat(scope.covers("accounting:je:view", SHOP)).isTrue();
    }

    @ParameterizedTest(name = "X-Loc-Scope = {0}")
    @ValueSource(
            strings = {
                "!!!not-base64!!!",
                "", // decodes to no bytes at all
                "QUxM", // "ALL" — a bare string, not an object
                "e30", // {}
                "eyJ2IjoyLCJub2RlcyI6W119", // {"v":2,"nodes":[]}
                "eyJ2IjoiMSIsIm5vZGVzIjpbXX0", // {"v":"1","nodes":[]}
                "eyJ2IjoxfQ", // {"v":1}
                "eyJ2IjoxLCJub2RlcyI6IngifQ", // {"v":1,"nodes":"x"}
                "eyJ2IjoxLCJub2RlcyI6WyJub3QtYS11dWlkIl19", // {"v":1,"nodes":["not-a-uuid"]}
                "eyJ2IjoxLCJub2RlcyI6WzQyXX0" // {"v":1,"nodes":[42]}
            })
    @DisplayName(
            "a malformed X-Loc-Scope is treated as absent → scoped permissions deny, the request still authenticates")
    void malformedScopeTreatedAsAbsent(String header) throws Exception {
        MockHttpServletRequest request = permBitsRequest();
        request.addHeader(GatewaySecurityConstants.HEADER_LOC_FIN_BITS, "");
        request.addHeader(GatewaySecurityConstants.HEADER_LOC_OTH_BITS, bits(BIT_PARTY_VIEW));
        request.addHeader(GatewaySecurityConstants.HEADER_LOC_SCOPE, header);

        LocationScope scope = run(new GatewayAuthoritiesFilter(RESOLVER), request);

        assertThat(scope.claimsPresent()).isTrue();
        assertThat(scope.nodes()).isEmpty();
        assertThat(scope.covers("crm:party:view", SHOP)).isFalse();
        // The authorities are untouched: this is a scope failure, not an authentication one.
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(a -> a.getAuthority())
                .contains("crm:party:view");
    }

    @Test
    @DisplayName("a well-formed X-Loc-Scope with several nodes decodes them all")
    void multiNodeScopeDecodes() throws Exception {
        MockHttpServletRequest request = permBitsRequest();
        request.addHeader(GatewaySecurityConstants.HEADER_LOC_FIN_BITS, "");
        request.addHeader(GatewaySecurityConstants.HEADER_LOC_OTH_BITS, bits(BIT_PARTY_VIEW));
        request.addHeader(GatewaySecurityConstants.HEADER_LOC_SCOPE, scopeHeaderFor(SHOP, REGION));

        LocationScope scope = run(new GatewayAuthoritiesFilter(RESOLVER), request);

        assertThat(scope.nodes()).contains(Set.of(SHOP, REGION));
    }

    @Test
    @DisplayName("a scope bitset that does not decode clears the context, exactly like a bad X-Perm-Bits")
    void malformedBitsetFailsClosed() throws Exception {
        MockHttpServletRequest request = permBitsRequest();
        request.addHeader(GatewaySecurityConstants.HEADER_LOC_FIN_BITS, "!!!not-valid-base64!!!");
        request.addHeader(GatewaySecurityConstants.HEADER_LOC_OTH_BITS, "");

        new GatewayAuthoritiesFilter(RESOLVER).doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("only one bitset header present → still 'claims present'; the missing one is empty")
    void oneBitsetHeaderIsStillPresent() throws Exception {
        MockHttpServletRequest request = permBitsRequest();
        request.addHeader(GatewaySecurityConstants.HEADER_LOC_OTH_BITS, bits(BIT_PARTY_VIEW));

        LocationScope scope = run(new GatewayAuthoritiesFilter(RESOLVER), request);

        assertThat(scope.claimsPresent()).isTrue();
        assertThat(scope.financialScoped()).isEmpty();
        assertThat(scope.otherScoped()).containsExactly("crm:party:view");
    }

    @Test
    @DisplayName("the no-arg filter (no resolver bean) denies scoped permissions even for a directly assigned node")
    void noResolverDenies() throws Exception {
        MockHttpServletRequest request = permBitsRequest();
        request.addHeader(GatewaySecurityConstants.HEADER_LOC_FIN_BITS, "");
        request.addHeader(GatewaySecurityConstants.HEADER_LOC_OTH_BITS, bits(BIT_PARTY_VIEW));
        request.addHeader(GatewaySecurityConstants.HEADER_LOC_SCOPE, scopeHeaderFor(SHOP));

        LocationScope scope = run(new GatewayAuthoritiesFilter(), request);

        assertThat(scope.covers("crm:party:view", SHOP)).isFalse();
        assertThat(scope.covers("accounting:je:view", SHOP)).isTrue();
    }
}
