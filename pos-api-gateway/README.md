# POS API Gateway — Security Blurb

A lightweight authentication/authorization filter enriches requests with user identity and authorities, keeping the gateway thin and pushing policy to services.

## What It Does
- Validates JWT via Security Service: `GET /v1/auth/validate?token=...`
- Expands roles → authorities: `GET /v1/auth/authorities?token=...`
- Fetches subject: `GET /v1/auth/subject?token=...`
- Injects headers to downstream services:
  - `X-Authorities`: comma-separated authorities (e.g., `crm:party:view,crm:vehicle:edit,...`)
  - `X-User`: token subject (username)
- Public paths bypass: `/actuator/**`, `/swagger-ui/**`, `/v3/api-docs/**`, `/swagger-resources/**`, `/eureka/**`

Source: src/main/java/com/positivity/gateway/config/SecurityGatewayConfig.java

## Configuration
- Property: `security.service.url` (default `http://pos-security-service:8086`)
- Example env:

```bash
export SECURITY_SERVICE_URL=http://pos-security-service:8086
```

## Notes
- Gateways remain thin; no policy logic here. Services still enforce `@PreAuthorize("hasAuthority('crm:...')")`.
- Tokens issued by Security Service already carry `authorities` claim; the gateway can still call `/authorities` for consistency or fallback.
- Downstream services may read `authorities` directly from JWT or use `X-Authorities` if desired.

## Quick Test
1) Build gateway
```bash
./mvnw -pl pos-api-gateway -am -DskipTests clean compile
```
2) Issue a token (example)
```bash
curl -s "http://pos-security-service:8086/v1/auth/login?subject=alice&roles=CSR,FLEET_MANAGER"
```
3) Call any routed backend endpoint with `Authorization: Bearer <token>`; gateway will validate and inject headers automatically.
