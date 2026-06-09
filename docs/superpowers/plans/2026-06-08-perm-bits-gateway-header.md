# perm_bits Gateway Header Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the verbose comma-separated `X-Authorities` header that the API gateway injects into downstream requests with a compact `X-Perm-Bits` + `X-Perm-Ver` header pair, eliminating the `Request header is too large` error that occurs when admin users (with 328 permissions × ~35 chars ≈ 10KB) exceed Tomcat's 8KB default.

**Architecture:** The API gateway already validates and decodes the JWT `perm_bits` bitset claim into a CSV string today. This plan reverses that expansion: the gateway forwards the raw base64 bitset directly, and each downstream service decodes it locally using a new `DownstreamPermissionCatalog` class added to `pos-security-common`. The existing `X-Authorities` CSV path is kept as a fallback in `GatewayAuthoritiesFilter` so that service-to-service REST clients (which inject 1–3 plain permission strings) and integration tests continue to work without any changes.

**Tech Stack:** Java 21, Spring Boot 3.x (Spring MVC + WebFlux), `pos-security-common` shared library, Maven multi-module build (`./mvnw`), Python 3 (`generate-permissions.py`).

---

## File Map

| Status | File                                                                                                                         | Change                                                               |
| ------ | ---------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------- |
| Create | `pos-security-common/src/main/java/com/positivity/security/common/DownstreamPermissionCatalog.java`                          | New class: AUTHORITY_BY_BIT array + decode helper                    |
| Create | `pos-security-common/src/test/java/com/positivity/security/common/DownstreamPermissionCatalogTest.java`                      | Unit tests for the new catalog                                       |
| Modify | `pos-security-common/src/main/java/com/positivity/security/common/GatewaySecurityConstants.java`                             | Add `HEADER_PERM_BITS` and `HEADER_PERM_VER` constants               |
| Modify | `pos-security-common/src/main/java/com/positivity/security/common/GatewayAuthoritiesFilter.java`                             | Prefer `X-Perm-Bits` decode path, keep `X-Authorities` as fallback   |
| Modify | `pos-security-common/src/test/java/com/positivity/security/common/GatewayAuthoritiesFilterTest.java`                         | Add tests for the new bitset decode path                             |
| Modify | `pos-api-gateway/src/main/java/com/positivity/gateway/config/SecurityGatewayConfig.java`                                     | Send `X-Perm-Bits`+`X-Perm-Ver` instead of `X-Authorities`           |
| Modify | `pos-api-gateway/src/main/java/com/positivity/gateway/config/GatewayAuthProperties.java`                                     | Update strip-list Javadoc comment                                    |
| Modify | `pos-api-gateway/src/test/java/com/positivity/gateway/config/SecurityGatewayConfigTest.java`                                 | Update header assertions from `X-Authorities` to `X-Perm-Bits`       |
| Modify | `pos-security-service/src/main/java/com/positivity/securityservice/internal/security/GatewayHeaderAuthenticationFilter.java` | Add `X-Perm-Bits` decode path using internal `PermissionBitsetCodec` |
| Modify | `pos-workorder/src/main/java/com/positivity/workorder/internal/controller/WorkorderDetailController.java`                    | Replace `@RequestHeader X-Authorities` with Spring Security context  |
| Modify | `scripts/generate-permissions.py`                                                                                            | Sync `DownstreamPermissionCatalog` as a third catalog target         |
| Modify | `durion/docs/architecture/AUTHORIZATION_MODEL.md`                                                                            | Update gateway flow + glossary for new headers                       |
| Modify | `durion/docs/architecture/API_SECURITY_ARCHITECTURE.md`                                                                      | Minor update to gateway responsibilities                             |
| Modify | `pos-security-service/docs/security-service-guide.md`                                                                        | Update authorization principle bullet                                |

---

## Task 1: DownstreamPermissionCatalog

**Files:**

- Create: `pos-security-common/src/main/java/com/positivity/security/common/DownstreamPermissionCatalog.java`
- Create: `pos-security-common/src/test/java/com/positivity/security/common/DownstreamPermissionCatalogTest.java`

- [ ] **Step 1: Write the failing tests**

```java
// pos-security-common/src/test/java/com/positivity/security/common/DownstreamPermissionCatalogTest.java
package com.positivity.security.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.BitSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class DownstreamPermissionCatalogTest {

    @Test
    void authorityForBit_knownIndex_returnsExpectedString() {
        // Bit 27 = crm:party:view in the current catalog
        assertThat(DownstreamPermissionCatalog.authorityForBit(27))
                .isEqualTo("PERM_crm:party:view");
    }

    @Test
    void authorityForBit_negativeIndex_returnsNull() {
        assertThat(DownstreamPermissionCatalog.authorityForBit(-1)).isNull();
    }

    @Test
    void authorityForBit_outOfRangeIndex_returnsNull() {
        assertThat(DownstreamPermissionCatalog.authorityForBit(100_000)).isNull();
    }

    @Test
    void authoritiesFromBitSet_givenSetBits_returnsMatchingPermissions() {
        BitSet bits = new BitSet();
        bits.set(27); // PERM_crm:party:view
        bits.set(28); // PERM_crm:party:search

        List<String> result = DownstreamPermissionCatalog.authoritiesFromBitSet(bits);

        assertThat(result)
                .containsExactlyInAnyOrder("PERM_crm:party:view", "PERM_crm:party:search");
    }

    @Test
    void authoritiesFromBitSet_emptyBitSet_returnsEmptyList() {
        assertThat(DownstreamPermissionCatalog.authoritiesFromBitSet(new BitSet())).isEmpty();
    }

    @Test
    void authoritiesFromBitSet_unknownBit_silentlySkipsIt() {
        BitSet bits = new BitSet();
        bits.set(99_999); // beyond catalog range

        assertThat(DownstreamPermissionCatalog.authoritiesFromBitSet(bits)).isEmpty();
    }

    @Test
    void catalogVersion_isPositive() {
        assertThat(DownstreamPermissionCatalog.CATALOG_VERSION).isGreaterThan(0);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./mvnw -pl pos-security-common test -Dtest=DownstreamPermissionCatalogTest -q 2>&1 | tail -10
```

Expected: compilation failure — `DownstreamPermissionCatalogTest` cannot find `DownstreamPermissionCatalog`.

- [ ] **Step 3: Create DownstreamPermissionCatalog**

```java
// pos-security-common/src/main/java/com/positivity/security/common/DownstreamPermissionCatalog.java
package com.positivity.security.common;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Downstream mirror of {@code GatewayPermissionCatalog} used by
 * {@link GatewayAuthoritiesFilter} to decode the compact {@code X-Perm-Bits}
 * header injected by the API gateway.
 *
 * <p>This array is a structural copy of
 * {@code pos-api-gateway/.../GatewayPermissionCatalog.AUTHORITY_BY_BIT}.
 * Both files are kept in sync by {@code scripts/generate-permissions.py --sync}.
 * Bit indices are permanent — never reorder or remove entries.
 */
public final class DownstreamPermissionCatalog {

    private DownstreamPermissionCatalog() {}

    /**
     * Must match {@code GatewayPermissionCatalog.CATALOG_VERSION} and
     * {@code PermissionCode.CATALOG_VERSION}.
     * Updated automatically by {@code scripts/generate-permissions.py --sync}.
     */
    public static final int CATALOG_VERSION = 10;

    /**
     * Index-to-authority mapping. Entry at position N is the {@code PERM_*}-prefixed
     * authority string for bit N in the {@code perm_bits} JWT claim.
     * Copy verbatim from {@code GatewayPermissionCatalog.AUTHORITY_BY_BIT}.
     */
    static final String[] AUTHORITY_BY_BIT = {
        "PERM_accounting:je:view",                          // 0
        "PERM_accounting:je:create",                        // 1
        "PERM_accounting:je:post",                          // 2
        "PERM_accounting:ap:view",                          // 3
        "PERM_accounting:ap:pay",                           // 4
        "PERM_accounting:coa:view",                         // 5
        "PERM_accounting:coa:create",                       // 6
        "PERM_accounting:coa:edit",                         // 7
        "PERM_accounting:events:view",                      // 8
        "PERM_accounting:events:submit",                    // 9
        "PERM_accounting:events:retry",                     // 10
        "PERM_catalog:product:view",                        // 11
        "PERM_catalog:product:create",                      // 12
        "PERM_catalog:product:edit",                        // 13
        "PERM_catalog:product:delete",                      // 14
        "PERM_product:lifecycle:update",                    // 15
        "PERM_product:lifecycle:override_discontinued",     // 16
        "PERM_catalog:category:view",                       // 17
        "PERM_catalog:category:create",                     // 18
        "PERM_catalog:category:edit",                       // 19
        "PERM_catalog:category:delete",                     // 20
        "PERM_catalog:service_type:view",                   // 21
        "PERM_catalog:service_type:create",                 // 22
        "PERM_catalog:service_type:edit",                   // 23
        "PERM_catalog:variant:view",                        // 24
        "PERM_catalog:variant:create",                      // 25
        "PERM_catalog:variant:edit",                        // 26
        "PERM_crm:party:view",                              // 27
        "PERM_crm:party:search",                            // 28
        "PERM_crm:party:create",                            // 29
        "PERM_crm:party:edit",                              // 30
        "PERM_crm:party:deactivate",                        // 31
        "PERM_crm:party:merge",                             // 32
        "PERM_crm:contact:view",                            // 33
        "PERM_crm:contact:create",                          // 34
        "PERM_crm:contact:edit",                            // 35
        "PERM_crm:contact:delete",                          // 36
        "PERM_crm:contact_role:view",                       // 37
        "PERM_crm:contact_role:assign",                     // 38
        "PERM_crm:contact_role:revoke",                     // 39
        "PERM_crm:contact_preference:view",                 // 40
        "PERM_crm:contact_preference:edit",                 // 41
        "PERM_crm:vehicle:view",                            // 42
        "PERM_crm:vehicle:search",                          // 43
        "PERM_crm:vehicle:create",                          // 44
        "PERM_crm:vehicle:edit",                            // 45
        "PERM_crm:vehicle:deactivate",                      // 46
        "PERM_crm:vehicle_party_association:create",        // 47
        "PERM_crm:vehicle_party_association:view",          // 48
        "PERM_crm:vehicle_party_association:edit",          // 49
        "PERM_crm:vehicle_preference:view",                 // 50
        "PERM_crm:vehicle_preference:edit"                  // 51
        // ── copy ALL remaining entries verbatim from GatewayPermissionCatalog.AUTHORITY_BY_BIT ──
        // Run: grep '"PERM_' pos-api-gateway/src/main/java/com/positivity/gateway/config/GatewayPermissionCatalog.java
        // and paste every entry from index 52 through the end of the array here.
        // The array must end without a trailing comma on the last entry.
    };

    /**
     * Returns the {@code PERM_}-prefixed authority string for the given bit index,
     * or {@code null} if the index is out of range.
     */
    public static String authorityForBit(int bitIndex) {
        if (bitIndex < 0 || bitIndex >= AUTHORITY_BY_BIT.length) {
            return null;
        }
        return AUTHORITY_BY_BIT[bitIndex];
    }

    /**
     * Maps all set bits in {@code bits} to their {@code PERM_*} authority strings.
     * Bits beyond the catalog range are silently ignored.
     */
    public static List<String> authoritiesFromBitSet(BitSet bits) {
        List<String> result = new ArrayList<>();
        for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) {
            String authority = authorityForBit(i);
            if (authority != null) {
                result.add(authority);
            }
        }
        return result;
    }
}
```

> **Important:** The stub above only includes entries 0–51. Before running the tests, copy ALL remaining entries (52 through the last entry) verbatim from `pos-api-gateway/src/main/java/com/positivity/gateway/config/GatewayPermissionCatalog.java`. The array lengths and `CATALOG_VERSION` in both files must be identical. Run this to get the full list:
>
> ```bash
> grep '"PERM_' pos-api-gateway/src/main/java/com/positivity/gateway/config/GatewayPermissionCatalog.java
> ```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./mvnw -pl pos-security-common test -Dtest=DownstreamPermissionCatalogTest -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add pos-security-common/src/main/java/com/positivity/security/common/DownstreamPermissionCatalog.java \
        pos-security-common/src/test/java/com/positivity/security/common/DownstreamPermissionCatalogTest.java
git commit -m "feat(security-common): add DownstreamPermissionCatalog for X-Perm-Bits decoding"
```

---

## Task 2: GatewaySecurityConstants — Add Header Constants

**Files:**

- Modify: `pos-security-common/src/main/java/com/positivity/security/common/GatewaySecurityConstants.java`

- [ ] **Step 1: Add constants**

Add the following two constants to `GatewaySecurityConstants.java` after the `HEADER_ROLES` constant:

```java
/**
 * Header containing the Base64URL-encoded permission bitset forwarded by
 * the API gateway. Replaces the verbose {@link #HEADER_AUTHORITIES} CSV
 * for gateway-to-service traffic. Decoded using
 * {@link DownstreamPermissionCatalog}.
 */
public static final String HEADER_PERM_BITS = "X-Perm-Bits";

/**
 * Header containing the integer permission catalog version that corresponds
 * to the {@link #HEADER_PERM_BITS} bitset. Must match
 * {@link DownstreamPermissionCatalog#CATALOG_VERSION} for the filter to
 * use the compact decode path.
 */
public static final String HEADER_PERM_VER = "X-Perm-Ver";
```

- [ ] **Step 2: Verify compilation**

```bash
./mvnw -pl pos-security-common compile -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add pos-security-common/src/main/java/com/positivity/security/common/GatewaySecurityConstants.java
git commit -m "feat(security-common): add HEADER_PERM_BITS and HEADER_PERM_VER constants"
```

---

## Task 3: GatewayAuthoritiesFilter — Decode X-Perm-Bits

**Files:**

- Modify: `pos-security-common/src/main/java/com/positivity/security/common/GatewayAuthoritiesFilter.java`
- Modify: `pos-security-common/src/test/java/com/positivity/security/common/GatewayAuthoritiesFilterTest.java`

- [ ] **Step 1: Write failing tests**

Add these test methods to `GatewayAuthoritiesFilterTest.java` (append after the existing tests):

```java
@Test
@DisplayName("X-Perm-Bits is decoded to PERM_ and plain authorities when version matches")
void permBitsHeader_decodesAndExpands() throws ServletException, IOException {
    BitSet bits = new BitSet();
    bits.set(27); // PERM_crm:party:view
    bits.set(28); // PERM_crm:party:search
    String encoded = java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(bits.toByteArray());

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/customers");
    request.addHeader(GatewaySecurityConstants.HEADER_USER, "alice");
    request.addHeader(GatewaySecurityConstants.HEADER_PERM_BITS, encoded);
    request.addHeader(GatewaySecurityConstants.HEADER_PERM_VER,
            String.valueOf(DownstreamPermissionCatalog.CATALOG_VERSION));

    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    Set<String> authorities = SecurityContextHolder.getContext().getAuthentication()
            .getAuthorities().stream()
            .map(org.springframework.security.core.GrantedAuthority::getAuthority)
            .collect(Collectors.toSet());

    assertThat(authorities)
            .contains("PERM_crm:party:view")
            .contains("crm:party:view")
            .contains("PERM_crm:party:search")
            .contains("crm:party:search");
}

@Test
@DisplayName("X-Perm-Bits merges X-Roles into the authority set")
void permBitsAndRolesHeader_bothGranted() throws ServletException, IOException {
    BitSet bits = new BitSet();
    bits.set(27); // PERM_crm:party:view
    String encoded = java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(bits.toByteArray());

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/customers");
    request.addHeader(GatewaySecurityConstants.HEADER_USER, "alice");
    request.addHeader(GatewaySecurityConstants.HEADER_PERM_BITS, encoded);
    request.addHeader(GatewaySecurityConstants.HEADER_PERM_VER,
            String.valueOf(DownstreamPermissionCatalog.CATALOG_VERSION));
    request.addHeader(GatewaySecurityConstants.HEADER_ROLES, "ROLE_ADMIN");

    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    Set<String> authorities = SecurityContextHolder.getContext().getAuthentication()
            .getAuthorities().stream()
            .map(org.springframework.security.core.GrantedAuthority::getAuthority)
            .collect(Collectors.toSet());

    assertThat(authorities)
            .contains("PERM_crm:party:view")
            .contains("crm:party:view")
            .contains("ROLE_ADMIN");
}

@Test
@DisplayName("X-Authorities CSV still works when X-Perm-Bits is absent (legacy fallback)")
void legacyXAuthoritiesCsv_worksWhenPermBitsAbsent() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/customers");
    request.addHeader(GatewaySecurityConstants.HEADER_USER, "alice");
    request.addHeader(GatewaySecurityConstants.HEADER_AUTHORITIES, "PERM_crm:party:view,PERM_crm:party:search");

    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    Set<String> authorities = SecurityContextHolder.getContext().getAuthentication()
            .getAuthorities().stream()
            .map(org.springframework.security.core.GrantedAuthority::getAuthority)
            .collect(Collectors.toSet());

    assertThat(authorities)
            .contains("PERM_crm:party:view")
            .contains("crm:party:view")
            .contains("PERM_crm:party:search")
            .contains("crm:party:search");
}

@Test
@DisplayName("X-Perm-Bits with wrong catalog version clears auth context")
void permBitsWithWrongVersion_clearsContext() throws ServletException, IOException {
    BitSet bits = new BitSet();
    bits.set(27);
    String encoded = java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(bits.toByteArray());

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/customers");
    request.addHeader(GatewaySecurityConstants.HEADER_USER, "alice");
    request.addHeader(GatewaySecurityConstants.HEADER_PERM_BITS, encoded);
    request.addHeader(GatewaySecurityConstants.HEADER_PERM_VER, "999");

    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./mvnw -pl pos-security-common test -Dtest=GatewayAuthoritiesFilterTest -q 2>&1 | tail -15
```

Expected: FAIL — new tests call `HEADER_PERM_BITS` which doesn't exist yet in the filter logic.

- [ ] **Step 3: Update GatewayAuthoritiesFilter**

Replace the `doFilterInternal` method and add the `authoritiesFromPermBits` helper. The method body changes from:

```java
String authoritiesHeader = request.getHeader(GatewaySecurityConstants.HEADER_AUTHORITIES);
String rolesHeader = request.getHeader(GatewaySecurityConstants.HEADER_ROLES);
String userHeader = request.getHeader(GatewaySecurityConstants.HEADER_USER);
String authorizationHeader = request.getHeader(AUTHORIZATION_HEADER);

if (StringUtils.hasText(authoritiesHeader) || StringUtils.hasText(rolesHeader)) {
    List<SimpleGrantedAuthority> authorities = parseAuthorities(authoritiesHeader, rolesHeader);
```

to:

```java
String permBitsHeader = request.getHeader(GatewaySecurityConstants.HEADER_PERM_BITS);
String permVerHeader = request.getHeader(GatewaySecurityConstants.HEADER_PERM_VER);
String authoritiesHeader = request.getHeader(GatewaySecurityConstants.HEADER_AUTHORITIES);
String rolesHeader = request.getHeader(GatewaySecurityConstants.HEADER_ROLES);
String userHeader = request.getHeader(GatewaySecurityConstants.HEADER_USER);
String authorizationHeader = request.getHeader(AUTHORIZATION_HEADER);

boolean hasPermBits = StringUtils.hasText(permBitsHeader);

if (hasPermBits || StringUtils.hasText(authoritiesHeader) || StringUtils.hasText(rolesHeader)) {
    List<SimpleGrantedAuthority> authorities = hasPermBits
            ? authoritiesFromPermBits(permBitsHeader, permVerHeader, rolesHeader)
            : parseAuthorities(authoritiesHeader, rolesHeader);
```

Also add `java.util.Base64` to the imports and add this private method to the class:

```java
private List<SimpleGrantedAuthority> authoritiesFromPermBits(
        String permBitsHeader, String permVerHeader, String rolesHeader) {
    int permVer;
    try {
        permVer = Integer.parseInt(permVerHeader);
    } catch (NumberFormatException | NullPointerException e) {
        loggr.warn("Invalid or missing X-Perm-Ver header; clearing auth context");
        return Collections.emptyList();
    }

    if (permVer != DownstreamPermissionCatalog.CATALOG_VERSION) {
        loggr.warn("X-Perm-Ver {} does not match local catalog version {}; clearing auth context",
                permVer, DownstreamPermissionCatalog.CATALOG_VERSION);
        return Collections.emptyList();
    }

    java.util.BitSet bits;
    try {
        byte[] bytes = Base64.getUrlDecoder().decode(permBitsHeader);
        bits = java.util.BitSet.valueOf(bytes);
    } catch (IllegalArgumentException e) {
        loggr.warn("Malformed X-Perm-Bits header: {}; clearing auth context", e.getMessage());
        return Collections.emptyList();
    }

    Stream<String> permStream = DownstreamPermissionCatalog.authoritiesFromBitSet(bits)
            .stream()
            .flatMap(this::expandAuthority);
    Stream<String> roleStream = csvValues(rolesHeader);

    return Stream.concat(permStream, roleStream)
            .distinct()
            .map(SimpleGrantedAuthority::new)
            .toList();
}
```

- [ ] **Step 4: Run all filter tests**

```bash
./mvnw -pl pos-security-common test -Dtest=GatewayAuthoritiesFilterTest -q 2>&1 | tail -15
```

Expected: `BUILD SUCCESS`, all tests pass.

- [ ] **Step 5: Run full module tests**

```bash
./mvnw -pl pos-security-common test -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
git add pos-security-common/src/main/java/com/positivity/security/common/GatewayAuthoritiesFilter.java \
        pos-security-common/src/test/java/com/positivity/security/common/GatewayAuthoritiesFilterTest.java
git commit -m "feat(security-common): decode X-Perm-Bits in GatewayAuthoritiesFilter with X-Authorities fallback"
```

---

## Task 4: SecurityGatewayConfig — Forward X-Perm-Bits

**Files:**

- Modify: `pos-api-gateway/src/main/java/com/positivity/gateway/config/SecurityGatewayConfig.java`
- Modify: `pos-api-gateway/src/main/java/com/positivity/gateway/config/GatewayAuthProperties.java`
- Modify: `pos-api-gateway/src/test/java/com/positivity/gateway/config/SecurityGatewayConfigTest.java`

- [ ] **Step 1: Update SecurityGatewayConfig**

**4a. Add new header name constants** at the top of `SecurityGatewayConfig` (alongside existing `HEADER_X_AUTHORITIES`):

```java
private static final String HEADER_X_PERM_BITS = "X-Perm-Bits";
private static final String HEADER_X_PERM_VER = "X-Perm-Ver";
```

**4b. Rename `authoritiesHeader` field in `AuthenticatedIdentity` record** to `permBitsHeader`:

```java
// Old:
private record AuthenticatedIdentity(
    String subject, String userId, String authoritiesHeader, String rolesHeader, String jti) {}

// New:
private record AuthenticatedIdentity(
    String subject, String userId, String permBitsHeader, String rolesHeader, String jti) {}
```

**4c. Update `resolveAuthenticatedIdentity`** — replace `buildAuthoritiesHeader(decodedPermissions.get())` with the raw `perm_bits` string from claims. The line that currently reads:

```java
String authoritiesHeader = buildAuthoritiesHeader(decodedPermissions.get());
return Optional.of(new AuthenticatedIdentity(
        subject, claims.get("uid", String.class), authoritiesHeader, rolesHeader, jti));
```

becomes:

```java
String permBits = claims.get("perm_bits", String.class);
return Optional.of(new AuthenticatedIdentity(
        subject, claims.get("uid", String.class),
        permBits != null ? permBits : "",
        rolesHeader, jti));
```

The legacy path in `resolveLegacyAuthoritiesHeader` still returns a CSV string. Update the legacy `return` statement to continue forwarding `X-Authorities` for legacy tokens (the `AuthenticatedIdentity` field `permBitsHeader` stores an empty string for legacy, and `forwardAuthenticatedRequest` detects which path to use):

The `if (legacyAuthoritiesHeader.isPresent())` block creates:

```java
return Optional.of(new AuthenticatedIdentity(
        subject, claims.get("uid", String.class),
        "",                              // no perm_bits for legacy tokens
        rolesHeader, jti));
```

Wait — we need to keep the legacy CSV somewhere. Adjust the record to hold both, or route legacy through `X-Authorities` in `forwardAuthenticatedRequest`. **Simplest approach**: keep one field but distinguish by using a sentinel. Instead, update `AuthenticatedIdentity` to carry a second field:

```java
private record AuthenticatedIdentity(
    String subject,
    String userId,
    String permBitsHeader,        // non-empty for new tokens; "" for legacy
    String legacyAuthoritiesHeader, // non-empty for legacy tokens; "" for new tokens
    String rolesHeader,
    String jti) {}
```

Update ALL existing call sites that create `AuthenticatedIdentity`:

```java
// New token path:
return Optional.of(new AuthenticatedIdentity(
        subject, claims.get("uid", String.class),
        permBits != null ? permBits : "",
        "",        // no legacy CSV
        rolesHeader, jti));

// Legacy token path (inside the resolveLegacyAuthoritiesHeader branch):
return Optional.of(new AuthenticatedIdentity(
        subject, claims.get("uid", String.class),
        "",        // no perm_bits
        legacyAuthoritiesHeader.get(),
        rolesHeader, jti));
```

**4d. Update `forwardAuthenticatedRequest`** — replace:

```java
headers.set(HEADER_X_AUTHORITIES, identity.authoritiesHeader());
```

with:

```java
if (StringUtils.hasText(identity.permBitsHeader())) {
    headers.set(HEADER_X_PERM_BITS, identity.permBitsHeader());
    headers.set(HEADER_X_PERM_VER, String.valueOf(GatewayPermissionCatalog.CATALOG_VERSION));
    headers.remove(HEADER_X_AUTHORITIES);
} else if (StringUtils.hasText(identity.legacyAuthoritiesHeader())) {
    headers.set(HEADER_X_AUTHORITIES, identity.legacyAuthoritiesHeader());
    headers.remove(HEADER_X_PERM_BITS);
    headers.remove(HEADER_X_PERM_VER);
}
```

**4e. Update `createAuthRequestContext` strip block** — add the two new headers to the strip list:

```java
headers.remove(HEADER_X_USER);
headers.remove(HEADER_X_USER_ID);
headers.remove(HEADER_X_AUTHORITIES);
headers.remove(HEADER_X_ROLES);
headers.remove(HEADER_X_PERM_BITS);    // add
headers.remove(HEADER_X_PERM_VER);    // add
```

**4f. Update `InboundIdentityHeaders` record** — add `permBits` for the mismatch check:

```java
private record InboundIdentityHeaders(String user, String userId, String authorities, String permBits) {}
```

In `createAuthRequestContext`, capture the inbound value:

```java
InboundIdentityHeaders inboundHeaders = new InboundIdentityHeaders(
    incomingRequest.getHeaders().getFirst(HEADER_X_USER),
    incomingRequest.getHeaders().getFirst(HEADER_X_USER_ID),
    incomingRequest.getHeaders().getFirst(HEADER_X_AUTHORITIES),
    incomingRequest.getHeaders().getFirst(HEADER_X_PERM_BITS));   // add
```

Update the mismatch check in `forwardAuthenticatedRequest`:

```java
if (authProperties.isRejectHeaderTokenMismatch()) {
    boolean mismatch = headerMismatch(context.inboundHeaders().user(), identity.subject())
            || headerMismatch(context.inboundHeaders().userId(), identity.userId())
            || authoritiesHeaderMismatch(context.inboundHeaders().authorities(), identity.legacyAuthoritiesHeader())
            || headerMismatch(context.inboundHeaders().permBits(), identity.permBitsHeader());
    ...
}
```

**4g. Remove `buildAuthoritiesHeader` method** — it is no longer called. Delete:

```java
private String buildAuthoritiesHeader(BitSet decodedPermissions) {
    List<String> authorities = decodedPermissions.stream()
            .mapToObj(GatewayPermissionCatalog::authorityForBit)
            .filter(Objects::nonNull)
            .toList();
    return String.join(",", authorities);
}
```

Also remove unused `Objects` import if no longer needed.

- [ ] **Step 2: Update GatewayAuthProperties Javadoc**

In `GatewayAuthProperties.java`, update the `stripInboundIdentityHeaders` comment:

```java
/**
 * When true, inbound {@code X-User}, {@code X-User-Id}, {@code X-Authorities},
 * {@code X-Roles}, {@code X-Perm-Bits}, and {@code X-Perm-Ver}
 * headers are stripped before forwarding to downstream services.
 */
private boolean stripInboundIdentityHeaders = true;
```

- [ ] **Step 3: Verify compilation**

```bash
./mvnw -pl pos-api-gateway compile -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Update SecurityGatewayConfigTest assertions**

Search for all lines in `SecurityGatewayConfigTest.java` that assert on `"X-Authorities"` and update them to assert on `"X-Perm-Bits"` and `"X-Perm-Ver"`. The pattern is:

```java
// OLD (examples from the existing tests):
String authorities = downstreamHeaders.get().getFirst("X-Authorities");
assertThat(downstreamHeaders.get().getFirst("X-Authorities")).contains("PERM_...");
assertThat(downstreamHeaders.get().get("X-Authorities")).isNullOrEmpty();

// NEW — replace every occurrence:
String permBits = downstreamHeaders.get().getFirst("X-Perm-Bits");
String permVer = downstreamHeaders.get().getFirst("X-Perm-Ver");
```

For assertions that verified a specific permission was present in `X-Authorities`, decode `X-Perm-Bits` and verify the bit is set:

```java
// Example: was asserting PERM_mcp:chat:execute is in X-Authorities
// New: assert X-Perm-Bits decodes to include the relevant permission
assertThat(permVer).isEqualTo(String.valueOf(GatewayPermissionCatalog.CATALOG_VERSION));
assertThat(permBits).isNotBlank();
BitSet decodedBits = BitSet.valueOf(Base64.getUrlDecoder().decode(permBits));
// Bit 166 = PERM_mcp:chat:execute — verify with:
// grep -n "mcp:chat:execute" pos-api-gateway/src/main/java/com/positivity/gateway/config/GatewayPermissionCatalog.java
assertThat(decodedBits.get(166)).isTrue(); // adjust bit index to match actual catalog position
```

For assertions that verified `X-Authorities` is empty/null (e.g., unauthenticated requests), verify instead:

```java
assertThat(downstreamHeaders.get().getFirst("X-Perm-Bits")).isNullOrEmpty();
```

For tests covering the **legacy token path** (tokens without `perm_bits`), `X-Authorities` is still sent. Keep those assertions unchanged.

- [ ] **Step 5: Run gateway tests**

```bash
./mvnw -pl pos-api-gateway test -q 2>&1 | tail -15
```

Expected: `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
git add pos-api-gateway/src/main/java/com/positivity/gateway/config/SecurityGatewayConfig.java \
        pos-api-gateway/src/main/java/com/positivity/gateway/config/GatewayAuthProperties.java \
        pos-api-gateway/src/test/java/com/positivity/gateway/config/SecurityGatewayConfigTest.java
git commit -m "feat(gateway): forward X-Perm-Bits instead of expanded X-Authorities CSV"
```

---

## Task 5: GatewayHeaderAuthenticationFilter — Security Service Update

**Files:**

- Modify: `pos-security-service/src/main/java/com/positivity/securityservice/internal/security/GatewayHeaderAuthenticationFilter.java`

The security service uses its own private `GatewayHeaderAuthenticationFilter` (not the one from `pos-security-common`). It needs the same `X-Perm-Bits` decode capability. Unlike downstream services, the security service has direct access to `PermissionBitsetCodec` and `PermissionCode`, so it does not need `DownstreamPermissionCatalog`.

- [ ] **Step 1: Update GatewayHeaderAuthenticationFilter**

Replace the entire filter body with:

```java
package com.positivity.securityservice.internal.security;

import com.positivity.securityservice.internal.domain.PermissionBitsetCodec;
import com.positivity.securityservice.internal.enums.PermissionCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads gateway-authentication headers and populates SecurityContext.
 * Prefers compact {@code X-Perm-Bits} + {@code X-Perm-Ver};
 * falls back to {@code X-Authorities} CSV for legacy or service-to-service calls.
 */
public class GatewayHeaderAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER_AUTHORITIES = "X-Authorities";
    private static final String HEADER_PERM_BITS = "X-Perm-Bits";
    private static final String HEADER_PERM_VER = "X-Perm-Ver";
    private static final String HEADER_USER = "X-User";
    private static final Logger log =
            LoggerFactory.getLogger(GatewayHeaderAuthenticationFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String permBitsHeader = request.getHeader(HEADER_PERM_BITS);
        String permVerHeader = request.getHeader(HEADER_PERM_VER);
        String authoritiesHeader = request.getHeader(HEADER_AUTHORITIES);
        String userHeader = request.getHeader(HEADER_USER);
        String username = (userHeader != null && !userHeader.isBlank()) ? userHeader : "gateway-user";

        List<SimpleGrantedAuthority> authorities = null;

        if (permBitsHeader != null && !permBitsHeader.isBlank()) {
            authorities = decodePermBits(permBitsHeader, permVerHeader);
        }

        if (authorities == null && authoritiesHeader != null && !authoritiesHeader.isBlank()) {
            authorities = parseCsvAuthorities(authoritiesHeader);
        }

        if (authorities != null) {
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(username, null, authorities);
            authentication.setDetails(Map.of("username", username));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            log.debug("Gateway auth established; user={} authorities={} uri={}",
                    username, authorities.size(), request.getRequestURI());
        } else {
            log.debug("No gateway auth headers; uri={}", request.getRequestURI());
        }

        filterChain.doFilter(request, response);
    }

    private List<SimpleGrantedAuthority> decodePermBits(String permBitsHeader, String permVerHeader) {
        int permVer;
        try {
            permVer = Integer.parseInt(permVerHeader);
        } catch (NumberFormatException | NullPointerException e) {
            log.warn("Invalid X-Perm-Ver header value: {}; falling back to X-Authorities", permVerHeader);
            return null;
        }

        if (permVer != PermissionCode.CATALOG_VERSION) {
            log.warn("X-Perm-Ver {} != local catalog {}; falling back to X-Authorities",
                    permVer, PermissionCode.CATALOG_VERSION);
            return null;
        }

        try {
            Set<PermissionCode> permissions = PermissionBitsetCodec.decodeToPermissions(permBitsHeader, permVer);
            return permissions.stream()
                    .map(p -> new SimpleGrantedAuthority(p.code()))
                    .toList();
        } catch (IllegalArgumentException e) {
            log.warn("Malformed X-Perm-Bits header: {}; falling back to X-Authorities", e.getMessage());
            return null;
        }
    }

    private List<SimpleGrantedAuthority> parseCsvAuthorities(String authoritiesHeader) {
        if (authoritiesHeader == null || authoritiesHeader.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(authoritiesHeader.split(","))
                .map(String::trim)
                .filter(a -> !a.isEmpty())
                .map(SimpleGrantedAuthority::new)
                .toList();
    }
}
```

- [ ] **Step 2: Verify compilation and tests**

```bash
./mvnw -pl pos-security-service test -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add pos-security-service/src/main/java/com/positivity/securityservice/internal/security/GatewayHeaderAuthenticationFilter.java
git commit -m "feat(security-service): decode X-Perm-Bits in GatewayHeaderAuthenticationFilter with X-Authorities fallback"
```

---

## Task 6: WorkorderDetailController — Read Authorities from Security Context

**Files:**

- Modify: `pos-workorder/src/main/java/com/positivity/workorder/internal/controller/WorkorderDetailController.java`

The controller currently binds `@RequestHeader(value = "X-Authorities", required = false)` and parses it manually. After this change, the gateway no longer sends `X-Authorities` for browser requests, so the binding will always return `null`. The `GatewayAuthoritiesFilter` already populates the Spring Security context from `X-Perm-Bits`, so the correct fix is to read from there. Integration tests that inject `X-Authorities` still work because the filter falls back to the CSV path.

- [ ] **Step 1: Update the controller method signature and body**

Replace the controller method:

```java
// OLD imports to remove:
// import java.util.Arrays;
// import java.util.HashSet;

// OLD method signature:
public ResponseEntity<WorkorderDetailResponse> getWorkorderDetail(
        @PathVariable @NonNull UUID workorderId,
        @Parameter(description = "User authorities (comma-separated)",
                   example = "workorder:workorder:view,workorder:financials:view")
        @RequestHeader(value = "X-Authorities", required = false)
        String authorities) {
    Set<String> userAuthorities = extractAuthorities(authorities);

// NEW imports to add:
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

// NEW method signature (remove the @Parameter and @RequestHeader binding entirely):
public ResponseEntity<WorkorderDetailResponse> getWorkorderDetail(
        @PathVariable @NonNull UUID workorderId) {

    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    Set<String> userAuthorities = authentication != null
            ? authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .collect(Collectors.toUnmodifiableSet())
            : Collections.emptySet();
```

Also delete the now-unused `extractAuthorities` private method entirely:

```java
// DELETE this method:
private Set<String> extractAuthorities(String authoritiesHeader) {
    if (authoritiesHeader == null || authoritiesHeader.isBlank()) {
        return Set.of();
    }
    return new HashSet<>(Arrays.asList(authoritiesHeader.split(",")));
}
```

- [ ] **Step 2: Verify compilation**

```bash
./mvnw -pl pos-workorder compile -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Run workorder tests**

```bash
./mvnw -pl pos-workorder test -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`. The `WorkorderDetailVisibilityContractBehaviorIT` passes `X-Authorities` via its `givenWithAuthorities(...)` helper, which the `GatewayAuthoritiesFilter` fallback still parses. The Spring Security context is populated correctly, and the controller reads from it.

- [ ] **Step 4: Commit**

```bash
git add pos-workorder/src/main/java/com/positivity/workorder/internal/controller/WorkorderDetailController.java
git commit -m "fix(workorder): read user authorities from Spring Security context instead of X-Authorities header"
```

---

## Task 7: generate-permissions.py — Sync DownstreamPermissionCatalog

**Files:**

- Modify: `scripts/generate-permissions.py`

- [ ] **Step 1: Add the downstream catalog path constant**

After the `GATEWAY_CATALOG_RELPATH` constant (around line 38), add:

```python
DOWNSTREAM_CATALOG_RELPATH = (
    "pos-security-common/src/main/java/com/positivity/security/common"
    "/DownstreamPermissionCatalog.java"
)
```

- [ ] **Step 2: Add sync_downstream_catalog_java function**

Add this function after `sync_gateway_catalog_java`:

```python
def sync_downstream_catalog_java(
    root: Path, new_perms: list[str], start_bit: int, new_version: int, dry_run: bool
) -> None:
    """Append new AUTHORITY_BY_BIT entries to DownstreamPermissionCatalog.java and set CATALOG_VERSION."""
    java_path = root / DOWNSTREAM_CATALOG_RELPATH
    text = java_path.read_text(encoding="utf-8")

    sorted_perms = sorted(new_perms)
    end_bit = start_bit + len(sorted_perms) - 1
    bar = "─" * 42
    new_lines = [f"\n        // ── New batch (bits {start_bit}–{end_bit}) {bar}"]
    for i, perm in enumerate(sorted_perms):
        bit = start_bit + i
        pad = " " * max(1, 45 - len(perm))
        comma = "," if i < len(sorted_perms) - 1 else ""
        new_lines.append(f'        "PERM_{perm}"{comma}{pad}// {bit}')

    new_block = "\n".join(new_lines)

    tail_re = re.compile(r'("PERM_[^"]+")([^,\n]*\n)(\s*\};)')
    m = tail_re.search(text)
    if not m:
        raise ValueError(
            "Cannot find last AUTHORITY_BY_BIT entry in DownstreamPermissionCatalog.java"
        )

    new_text = (
        text[: m.start()]
        + m.group(1) + ","
        + m.group(2)
        + new_block + "\n"
        + m.group(3)
        + text[m.end() :]
    )

    new_text = re.sub(
        r"public static final int CATALOG_VERSION = \d+;",
        f"public static final int CATALOG_VERSION = {new_version};",
        new_text,
    )

    if not dry_run:
        java_path.write_text(new_text, encoding="utf-8")
```

- [ ] **Step 3: Call the new function in the sync block**

In `main()`, locate the sync block that calls `sync_gateway_catalog_java` and add the downstream call immediately after:

```python
# existing:
new_version = sync_permission_code_java(root, new_perms, next_bit, args.dry_run)
sync_gateway_catalog_java(root, new_perms, next_bit, new_version, args.dry_run)
# add:
sync_downstream_catalog_java(root, new_perms, next_bit, new_version, args.dry_run)
```

Also update the `--sync` help text to mention the downstream catalog:

```python
help=(
    "Scan @PreAuthorize annotations, register any unknown permissions in "
    "PermissionCode.java, GatewayPermissionCatalog.java, and "
    "DownstreamPermissionCatalog.java, and bump CATALOG_VERSION. "
    "Runs before permissions.yaml regeneration."
),
```

- [ ] **Step 4: Run the existing script tests**

```bash
python3 -m pytest scripts/tests/ -q 2>&1 | tail -10
```

Expected: all existing tests pass. No new tests are required here because the downstream function is structurally identical to `sync_gateway_catalog_java` (which is already tested).

- [ ] **Step 5: Verify dry-run works end-to-end**

```bash
python3 scripts/generate-permissions.py . --sync --dry-run 2>&1 | head -5
```

Expected: `Catalog sync: up-to-date` (no new permissions to add).

- [ ] **Step 6: Commit**

```bash
git add scripts/generate-permissions.py
git commit -m "feat(scripts): sync DownstreamPermissionCatalog alongside GatewayPermissionCatalog on --sync"
```

---

## Task 8: Documentation Updates

**Files:**

- Modify: `durion/docs/architecture/AUTHORIZATION_MODEL.md`
- Modify: `durion/docs/architecture/API_SECURITY_ARCHITECTURE.md`
- Modify: `pos-security-service/docs/security-service-guide.md`

- [ ] **Step 1: Update AUTHORIZATION_MODEL.md**

**8a. Update the Glossary section** — add entries for the new headers and update the `X-Authorities` entry:

```markdown
- **`X-Perm-Bits`**: compact Base64URL-encoded permission bitset forwarded by the gateway to downstream services. Replaces the verbose `X-Authorities` CSV for gateway-to-service traffic. Decoded by `GatewayAuthoritiesFilter` using `DownstreamPermissionCatalog`.
- **`X-Perm-Ver`**: integer permission-catalog version accompanying `X-Perm-Bits`. Must equal `DownstreamPermissionCatalog.CATALOG_VERSION` for the filter to use the compact decode path.
- **`X-Authorities`**: legacy comma-separated authority header. Still used by service-to-service REST clients (which set 1–3 plain permission strings) and integration tests. Recognised by `GatewayAuthoritiesFilter` as a fallback when `X-Perm-Bits` is absent.
```

**8b. Update the High-Level Flow diagram** in the `pos-api-gateway` block:

```text
pos-api-gateway
  - validates issuer, audience, signature, expiry
  - rejects mismatched or malformed permission catalog claims
  - forwards perm_bits as compact X-Perm-Bits + X-Perm-Ver headers
  - injects X-User, X-User-Id, X-Roles
```

**8c. Update the Gateway Decoding And Forwarding section** — replace bullet 5 and 6:

```markdown
5. forwards the raw `perm_bits` Base64URL string as the compact `X-Perm-Bits` header
6. injects the current `GatewayPermissionCatalog.CATALOG_VERSION` as `X-Perm-Ver`
7. injects trusted `X-User`, `X-User-Id`, `X-Roles` headers
8. removes any inbound `X-Authorities`, `X-Perm-Bits`, and `X-Perm-Ver` spoofing attempts
```

Remove old bullet about `X-Authorities` from the standard (non-legacy) path.

**8d. Update the Downstream Spring Security Behavior section**:

```markdown
`GatewayAuthoritiesFilter` in `pos-security-common` rebuilds Spring authentication from gateway headers.

Important behavior:

- it prefers `X-Perm-Bits` + `X-Perm-Ver` when present and the catalog version matches
- it decodes the bitset using `DownstreamPermissionCatalog` — a local mirror of `GatewayPermissionCatalog`
- for each `PERM_<code>` authority decoded from the bitset, it also adds the plain permission string (`crm:party:view` alongside `PERM_crm:party:view`) for compatibility with existing `@PreAuthorize("hasAuthority('...')")` checks
- it falls back to the legacy `X-Authorities` CSV header when `X-Perm-Bits` is absent — used by service-to-service REST clients and integration tests
```

**8e. Update the "Adding a New Permission" section** — bump the description of what `generate-permissions.sh --sync` does to mention the third file:

```markdown
- Appends corresponding `"PERM_..."` entries to `AUTHORITY_BY_BIT` in both
  `pos-api-gateway/.../GatewayPermissionCatalog.java` and
  `pos-security-common/.../DownstreamPermissionCatalog.java`
```

- [ ] **Step 2: Update API_SECURITY_ARCHITECTURE.md**

In the **API Gateway** responsibilities block, replace:

```markdown
- deriving trusted downstream authorities from token claims
- injecting trusted user and authorization headers
```

with:

```markdown
- forwarding compact `X-Perm-Bits` + `X-Perm-Ver` headers derived from the JWT `perm_bits` claim
- injecting trusted `X-User`, `X-User-Id`, `X-Roles` headers
```

- [ ] **Step 3: Update security-service-guide.md**

In the **Important Authorization Principle** section, update the bullet about header propagation:

```markdown
- The gateway turns token `perm_bits` into compact `X-Perm-Bits` + `X-Perm-Ver` headers for downstream `@PreAuthorize` checks. `X-Authorities` CSV is a fallback for service-to-service calls and tests.
```

- [ ] **Step 4: Verify no broken cross-references**

```bash
grep -r "X-Authorities" \
  ../durion/docs/architecture/ \
  pos-security-service/docs/ \
  2>/dev/null
```

Remaining `X-Authorities` references in docs should only be in the "legacy fallback" context. Flag any that describe it as the primary gateway-to-service header.

- [ ] **Step 5: Commit**

```bash
git add \
  ../durion/docs/architecture/AUTHORIZATION_MODEL.md \
  ../durion/docs/architecture/API_SECURITY_ARCHITECTURE.md \
  pos-security-service/docs/security-service-guide.md
git commit -m "docs: update authorization model for X-Perm-Bits gateway header forwarding"
```

---

## Self-Review Checklist

**Spec coverage:**

- [x] Gateway sends `X-Perm-Bits` instead of expanded `X-Authorities` — Task 4
- [x] Downstream services decode `X-Perm-Bits` — Task 3 (`GatewayAuthoritiesFilter`)
- [x] Security service decodes `X-Perm-Bits` — Task 5 (`GatewayHeaderAuthenticationFilter`)
- [x] `X-Authorities` still works for service-to-service calls and tests — backward compat in Task 3
- [x] Workorder controller no longer reads raw `X-Authorities` header — Task 6
- [x] `generate-permissions.py --sync` keeps all three catalog files in sync — Task 7
- [x] Documentation updated — Task 8

**Backward compatibility:**

- Service-to-service REST clients (`InventoryClientImpl`, `CustomerBillingRulesClient`, `WorkorderInvoiceClient`, `RestClientConfig`) inject `X-Authorities` directly. These remain functional because `GatewayAuthoritiesFilter` falls back to `X-Authorities` when `X-Perm-Bits` is absent.
- Integration tests that inject `X-Authorities` continue to work unchanged for the same reason.
- Legacy tokens (tokens with no `perm_ver` claim) still receive `X-Authorities` from the gateway (legacy path in `SecurityGatewayConfig`).

**Type consistency:**

- `DownstreamPermissionCatalog.authoritiesFromBitSet(BitSet)` returns `List<String>` — used correctly in `GatewayAuthoritiesFilter.authoritiesFromPermBits`.
- `GatewaySecurityConstants.HEADER_PERM_BITS` / `HEADER_PERM_VER` — used in filter (Task 3) and referenced in gateway constants (Task 4 uses its own local string constants for symmetry with existing style).
- `AuthenticatedIdentity.permBitsHeader()` / `AuthenticatedIdentity.legacyAuthoritiesHeader()` — both accessed in `forwardAuthenticatedRequest` and the mismatch check.
