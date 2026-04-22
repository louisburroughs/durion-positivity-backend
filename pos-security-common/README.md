# pos-security-common

Shared security library for all Durion POS microservices. Provides Spring Security configuration that trusts gateway-forwarded identity headers, permission manifest loading, and security context helpers. This is a library dependency, not a deployable service.

## Responsibilities

- Configure Spring Security to trust `X-Authorities`, `X-User`, and `X-User-Id` headers injected by the API gateway
- Load permission manifests from `permissions.yaml` at startup via `PermissionManifestLoader`
- Support startup registration of module permissions with the security service via `PermissionRegistrationSupport`
- Provide `SecurityContextHelper` for extracting user identity from the Spring Security context
- Define shared constants for gateway security headers and API secret handling

## Key Classes

- `GatewaySecurityConfig` — Spring Security auto-configuration; trusts gateway identity headers and disables stateless CSRF
- `GatewayAuthoritiesFilter` — `OncePerRequestFilter` that reads `X-Authorities` and populates the security context
- `PermissionManifestLoader` — loads `permissions.yaml` from the module classpath
- `PermissionRegistrationSupport` — helper for registering a module's permissions with `pos-security-service` at startup
- `SecurityContextHelper` — typed access to current user UUID, personId, and authority list
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

## Dependencies

No internal `pos-*` module dependencies. Depends on Spring Security and Spring Boot Web.

This module is a library dependency — there is no runnable service to start.
