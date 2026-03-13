# POS API Gateway — Security Blurb

A lightweight authentication/authorization filter enriches requests with user identity and authorities, keeping the gateway thin and pushing policy to services.

## What It Does

### API Versioning & Routing

- **X-API-Version Header (REQUIRED)**: All API calls must include the `X-API-Version` header with a simple integer version (e.g., `1`, `2`, `3`).
  - Header format: `X-API-Version: 1`
  - Missing or invalid header returns `400 Bad Request`
  - Gateway rewrites request path: `/{domain}/{resource}` + header `X-API-Version: 1` → `/{domain}/v1/{resource}`
  - Example: `GET /customer/crm/accounts` with header `X-API-Version: 1` → routes to `GET /customer/v1/crm/accounts`
- **Path Format**: Clients call `http://localhost:8080/{domain}/{resource}` with the version header; gateway inserts `/v{version}` after `{domain}`
  - Domain examples: `customer`, `inventory`, `order`, `accounting`
  - Gateway routes to internal service via service discovery (e.g., `lb://CUSTOMER`)
  - Service receives the request after gateway strips `/{domain}` prefix

### Authentication & Authorization

- Validates JWTs locally in the gateway using configured signing secret (`security.jwt.secret`)
- Decodes `perm_bits` (`Base64URL` bitset) with `perm_ver` catalog version checks
- Maps decoded bits to canonical `PERM_*` authorities for downstream authorization decisions
- Strips inbound identity headers (`X-User`, `X-User-Id`, `X-Authorities`, `X-Roles`) and regenerates trusted identity headers from verified token claims
- Injects headers to downstream services:
  - `X-Authorities`: comma-separated authorities (e.g., `PERM_crm:party:view,PERM_crm:vehicle:edit,...`)
  - `X-User`: token subject (username)
  - `X-User-Id`: token `uid` claim when present
  - `X-API-Version`: forwarded from client request (enables per-endpoint versioning)
- Public paths bypass authentication: `/actuator/**`, `/swagger-ui/**`, `/v3/api-docs/**`, `/swagger-resources/**`, `/eureka/**`

Source:

- API Versioning & routing: `src/main/java/com/positivity/gateway/filter/ApiVersionHeaderToPathFilter.java`
- Authentication & authorization: `src/main/java/com/positivity/gateway/config/SecurityGatewayConfig.java`

## Configuration

- Configure local JWT validation via `security.jwt.secret` and gateway auth flags under `auth.*`.
- Configure JWT validation to trust tokens from `pos-security-service` and audience `api-gateway`.

## Authentication & Authorization

1. Local JWT Validation

   - The gateway validates JWT signatures locally using JJWT and the
     configured `security.jwt.secret` value. No call to
     `pos-security-service` is required for token signature validation.

2. Permission Bitset Decode

   - Tokens issued by the security service include a `perm_bits` claim.
   - `perm_bits` is a Base64URL-encoded BitSet. The gateway decodes it to
     a `BitSet` and maps set bit indexes to authorities using
     `GatewayPermissionCatalog.AUTHORITY_BY_BIT[bitIndex]`.

3. Catalog Version

   - Tokens must include `perm_ver`. The gateway requires
     `perm_ver == GatewayPermissionCatalog.CATALOG_VERSION`.
   - A mismatched or missing `perm_ver` (when no legacy authorities
     fallback applies) results in `401 Unauthorized`.

4. Identity Header Hardening

   - Inbound identity headers are stripped before forwarding to
     downstream services: `X-User`, `X-User-Id`, `X-Authorities`,
     `X-Roles`.
   - The gateway injects trusted headers derived from the validated
     JWT: `X-User` (token `sub`), `X-User-Id` (token `uid` when present),
     and `X-Authorities` (comma-separated canonical authorities).

5. Feature Flags (under `auth:` prefix)

   - `auth.token-identity-required` (default: `false`)
     — when `true`, tokens without `perm_bits` are rejected.
   - `auth.strip-inbound-identity-headers` (default: `true`)
     — controls stripping of inbound identity headers.
   - `auth.reject-header-token-mismatch` (default: `false`)
     — when `true`, requests whose inbound headers conflict with token
     derived identity are rejected.

6. Observability Counters

   - The gateway increments the following Micrometer counters for auth
     observability and rollout monitoring:
     - `auth.token.validation.failure`
     - `auth.user.identity.missing`
     - `auth.perm.decode.failure`
     - `auth.perm.catalog.version.unknown`
     - `auth.header.strip.count`

7. Legacy Backward-Compat

   - During rollout the gateway supports a temporary fallback: if
     `perm_ver` is absent but an `authorities` claim exists, the filter
     will use those legacy authorities. The gateway increments
     `auth.legacy.decode.count` in this case. This behavior is
     explicitly timeboxed for the PERM rollout and should be removed
     after clients have migrated.

Example — gateway decoding `perm_bits` (conceptual):

```java
// Base64URL decode then BitSet.valueOf(bytes)
byte[] decoded = Base64.getUrlDecoder().decode(permBits);
BitSet bits = BitSet.valueOf(decoded);
List<String> authorities = bits.stream()
    .mapToObj(GatewayPermissionCatalog::authorityForBit)
    .filter(Objects::nonNull)
    .toList();
```

## Notes

- **API Versioning is mandatory**: All client requests must include `X-API-Version` with a simple integer (e.g., `1`, `2`). This enables independent versioning of endpoints across the platform.
- **Gateway is thin & stateless**: Validates versions, injects auth headers, rewrites paths. No policy logic here; services enforce authorization via `@PreAuthorize("hasAuthority('crm:...')")`.
- **Version header forwarding**: The gateway forwards `X-API-Version` to downstream services, enabling per-endpoint version tracking and observability.
- **Role and permission management ownership**: `pos-security-service` is the source of truth for roles, permissions, and assignments.
- Downstream services authorize on canonical authorities from gateway-established security context.

## Quick Test

### Basic Request Flow with API Version

1) Build gateway

```bash
./mvnw -pl pos-api-gateway -am -DskipTests clean compile
```

1) Issue a token (example)

```bash
curl -s -X POST "http://localhost:8086/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"subject":"alice","roles":["CSR","FLEET_MANAGER"]}'
# Returns JSON containing accessToken
```

1) Call a backend endpoint **with required X-API-Version header**

```bash
# ✓ CORRECT: Version header provided
curl -X GET "http://localhost:8080/customer/crm/accounts/11111111-1111-1111-1111-111111111111/tier" \
  -H "Authorization: Bearer eyJhbGc...token..." \
  -H "X-API-Version: 1"
# Gateway rewrites to: /customer/v1/crm/accounts/{id}/tier and routes to customer service

# ✗ WRONG: Missing version header
curl -X GET "http://localhost:8080/customer/crm/accounts/11111111-1111-1111-1111-111111111111/tier" \
  -H "Authorization: Bearer eyJhbGc...token..."
# Returns: 400 Bad Request — X-API-Version header required
```

1) Downstream service receives (after gateway processing)

```java
GET /v1/crm/accounts/11111111-1111-1111-1111-111111111111/tier
Headers:
  Authorization: Bearer eyJhbGc...token...
  X-Authorities: crm:party:view,crm:vehicle:edit,...
  X-User: alice
  X-API-Version: 1
```
