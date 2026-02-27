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
- Validates JWTs issued by `pos-security-service`
- Enforces required claims (issuer, audience, expiration, subject, token id) per ADR-0011
- Uses canonical authorities for downstream authorization decisions
- Injects headers to downstream services:
  - `X-Authorities`: comma-separated authorities (e.g., `crm:party:view,crm:vehicle:edit,...`)
  - `X-User`: token subject (username)
  - `X-User-Id`: stable user/person ID from token when available
  - `X-API-Version`: forwarded from client request (enables per-endpoint versioning)
- Public paths bypass authentication: `/actuator/**`, `/swagger-ui/**`, `/v3/api-docs/**`, `/swagger-resources/**`, `/eureka/**`

Source: 
- API Versioning & routing: `src/main/java/com/positivity/gateway/filter/ApiVersionHeaderToPathFilter.java`
- Authentication & authorization: `src/main/java/com/positivity/gateway/config/SecurityGatewayConfig.java`

## Configuration
- Security service lookup is via Eureka service discovery (`lb://security-service`) in `SecurityGatewayConfig`.
- Ensure `pos-security-service` is registered in Eureka as `security-service`.
- Configure JWT validation to trust tokens from `pos-security-service` and audience `api-gateway`.

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

2) Issue a token (example)
```bash
curl -s -X POST "http://localhost:8086/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"subject":"alice","roles":["CSR","FLEET_MANAGER"]}'
# Returns JSON containing accessToken
```

3) Call a backend endpoint **with required X-API-Version header**
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

4) Downstream service receives (after gateway processing)
```
GET /v1/crm/accounts/11111111-1111-1111-1111-111111111111/tier
Headers:
  Authorization: Bearer eyJhbGc...token...
  X-Authorities: crm:party:view,crm:vehicle:edit,...
  X-User: alice
  X-User-Id: person-uuid
  X-API-Version: 1
```
