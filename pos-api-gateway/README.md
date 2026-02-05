# POS API Gateway — Security Blurb

A lightweight authentication/authorization filter enriches requests with user identity and authorities, keeping the gateway thin and pushing policy to services.

## What It Does

### API Versioning & Routing
- **X-API-Version Header (REQUIRED)**: All API calls must include the `X-API-Version` header with a simple integer version (e.g., `1`, `2`, `3`).
  - Header format: `X-API-Version: 1`
  - Missing or invalid header returns `400 Bad Request`
  - Gateway rewrites request path: `/{domain}/{resource}` + header `X-API-Version: 1` → `/v1/{domain}/{resource}`
  - Example: `GET /customer/accounts` with header `X-API-Version: 1` → routes to `GET /v1/customer/accounts`
- **Path Format**: Clients call `http://localhost:8080/{domain}/{resource}` with the version header; gateway automatically prepends `/v{version}`
  - Domain examples: `customer`, `inventory`, `order`, `accounting`
  - Gateway routes to internal service via service discovery (e.g., `lb://customer`)
  - Service receives the request after gateway strips `/v{version}/{domain}` prefix

### Authentication & Authorization
- Validates JWT via Security Service: `GET /v1/auth/validate?token=...`
- Expands roles → authorities: `GET /v1/auth/authorities?token=...`
- Fetches subject: `GET /v1/auth/subject?token=...`
- Injects headers to downstream services:
  - `X-Authorities`: comma-separated authorities (e.g., `crm:party:view,crm:vehicle:edit,...`)
  - `X-User`: token subject (username)
  - `X-API-Version`: forwarded from client request (enables per-endpoint versioning)
- Public paths bypass authentication: `/actuator/**`, `/swagger-ui/**`, `/v3/api-docs/**`, `/swagger-resources/**`, `/eureka/**`

Source: 
- API Versioning & routing: `src/main/java/com/positivity/gateway/filter/ApiVersionHeaderToPathFilter.java`
- Authentication & authorization: `src/main/java/com/positivity/gateway/config/SecurityGatewayConfig.java`

## Configuration
- Property: `security.service.url` (default `http://pos-security-service:8086`)
- Example env:

```bash
export SECURITY_SERVICE_URL=http://pos-security-service:8086
```

## Notes
- **API Versioning is mandatory**: All client requests must include `X-API-Version` with a simple integer (e.g., `1`, `2`). This enables independent versioning of endpoints across the platform.
- **Gateway is thin & stateless**: Validates versions, injects auth headers, rewrites paths. No policy logic here; services enforce authorization via `@PreAuthorize("hasAuthority('crm:...')")`.
- **Version header forwarding**: The gateway forwards `X-API-Version` to downstream services, enabling per-endpoint version tracking and observability.
- Tokens issued by Security Service already carry `authorities` claim; the gateway can still call `/authorities` for consistency or fallback.
- Downstream services may read `authorities` directly from JWT or use `X-Authorities` header if desired.

## Quick Test

### Basic Request Flow with API Version
1) Build gateway
```bash
./mvnw -pl pos-api-gateway -am -DskipTests clean compile
```

2) Issue a token (example)
```bash
curl -s "http://pos-security-service:8086/v1/auth/login?subject=alice&roles=CSR,FLEET_MANAGER"
# Returns: eyJhbGc...token...
```

3) Call a backend endpoint **with required X-API-Version header**
```bash
# ✓ CORRECT: Version header provided
curl -X GET "http://localhost:8080/customer/accounts" \
  -H "Authorization: Bearer eyJhbGc...token..." \
  -H "X-API-Version: 1"
# Gateway rewrites to: /v1/customer/accounts and routes to customer service

# ✗ WRONG: Missing version header
curl -X GET "http://localhost:8080/customer/accounts" \
  -H "Authorization: Bearer eyJhbGc...token..."
# Returns: 400 Bad Request — X-API-Version header required
```

4) Downstream service receives (after gateway processing)
```
GET /accounts
Headers:
  Authorization: Bearer eyJhbGc...token...
  X-Authorities: crm:party:view,crm:vehicle:edit,...
  X-User: alice
  X-API-Version: 1
```
