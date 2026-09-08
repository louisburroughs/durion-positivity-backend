# pos-security-common

Shared security library for all Durion Positivity microservices. Provides Spring Security configuration that trusts gateway-forwarded identity headers, permission manifest loading, and security context helpers. This is a library dependency, not a deployable service.

## Responsibilities

- Configure Spring Security to trust `X-Authorities`, `X-User`, and `X-User-Id` headers injected by the API gateway
- Load permission manifests from `permissions.yaml` at startup via `PermissionManifestLoader`
- Support startup registration of module permissions with the security service via `PermissionRegistrationSupport`
- Provide `SecurityContextHelper` for extracting user identity from the Spring Security context
- Provide `LocationScope` — the shared location-scope check (`covers(permission, locationId)`) for endpoints that accept a `locationId` (ADR-0061 §3)
- Define shared constants for gateway security headers and API secret handling

## Key Classes

- `GatewaySecurityConfig` — Spring Security auto-configuration; trusts gateway identity headers and disables stateless CSRF
- `GatewayAuthoritiesFilter` — `OncePerRequestFilter` that reads `X-Authorities` and populates the security context
- `PermissionManifestLoader` — loads `permissions.yaml` from the module classpath
- `PermissionRegistrationSupport` — helper for registering a module's permissions with `pos-security-service` at startup
- `SecurityContextHelper` — typed access to current user UUID, personId, authority list, and `locationScope()`
- `LocationScope` — the caller's location reach for one request; `covers(P, L)` / `require(P, L)` implement the ADR-0061 §2 decision table
- `LocationAncestorResolver` — one-method SPI an adopting module implements as a bean over its own `LocationHierarchyService`
- `LocationScopeDeniedException` / `LocationScopeDeniedExceptionHandler` — a 403 with code `LOCATION_SCOPE_DENIED`, distinguishable from a plain missing-permission `FORBIDDEN`
- `GatewaySecurityConstants` — constants for gateway header names

## Usage

Add to the consuming module's `pom.xml`:

```xml
<dependency>
    <groupId>com.positivity</groupId>
    <artifactId>pos-security-common</artifactId>
</dependency>
```

Place a `permissions.yaml` file in `src/main/resources/` listing the module's permission definitions. `GatewaySecurityConfig` is auto-applied on classpath inclusion.

## Location scope (ADR-0061, #1870)

The gateway forwards three headers derived from the access token: `X-Loc-Fin-Bits` and `X-Loc-Oth-Bits`
(Base64URL bitsets over the same indexes and `X-Perm-Ver` as `X-Perm-Bits`, naming the permissions that
are location-scoped on the `FINANCIAL` / `OTHER` hierarchy dimension) and `X-Loc-Scope` (Base64URL of the
compact JSON `{"v":1,"nodes":["<uuid>",...]}` — the caller's assigned nodes; omitted when the token has
none). `GatewayAuthoritiesFilter` decodes them into one `LocationScope` in the authentication details.

At an endpoint that accepts a `locationId`, after the usual `@PreAuthorize`:

```java
SecurityContextHelper.locationScope().require(WipPermissions.WIP_VIEW, locationId);
```

| bitset state | `nodes` | result |
| --- | --- | --- |
| headers absent (pre-rollout token) | — | allow — today's behaviour |
| `P` in neither bitset | any | allow — the grant is global |
| `P` in a bitset | absent / malformed | deny — fail closed |
| `P` financial-scoped | intersects `ancestorsOf(L).financial` | allow |
| `P` other-scoped | intersects `ancestorsOf(L).other` | allow |
| `P` scoped | disjoint, `L` unknown to the replica, or not a UUID | deny |

A permission scoped on both dimensions passes if either covers. Ancestor sets are inclusive of self, so a
directly assigned node needs no special case. The module supplies the ancestor sets by declaring one
`LocationAncestorResolver` bean over its own location replica — never a per-request call to pos-location:

```java
@Bean
LocationAncestorResolver locationAncestorResolver(LocationHierarchyService hierarchy) {
    return hierarchy::ancestorsOf;
}
```

Without that bean every scoped permission is denied (and a warning logged once); it is never treated as
unrestricted. A denial is `LocationScopeDeniedException` (an `AccessDeniedException`), rendered by the
auto-configured `LocationScopeDeniedExceptionHandler` as `403 LOCATION_SCOPE_DENIED` in the `ApiError`
envelope with a correlation id.

## Dependencies

Internal: `pos-web-common` (re-exported) and `pos-domain-events` (for `LocationAncestry.AncestorSets`, the
ancestor-set shape every location replica materialises). Depends on Spring Security and Spring Boot Web.

This module is a library dependency — there is no runnable service to start.
