---
title: "Platform Auth and Token Usage Guide"
status: "ACTIVE"
capability: "platform-auth-token-usage"
version: "2.0"
created: "2026-03-17"
updated: "2026-06-07"
modules: ["pos-security-service", "pos-api-gateway"]
---

# Platform Auth And Token Usage Guide

This is the consumer-facing guide for authentication and token lifecycle behavior exposed by `pos-security-service` and enforced by `pos-api-gateway`.

For the deeper runtime explanation of roles, permissions, `perm_bits`, and `X-Authorities`, see:

- `../../../durion/docs/architecture/AUTHORIZATION_MODEL.md`

## Gateway URL Model

Gateway base URL example:

```text
http://localhost:8080
```

Security service route prefix:

```text
/security-service
```

User-facing credential login path:

```text
/security-service/v1/auth/login
```

## Required And Recommended Headers

Use these headers consistently:

- `Authorization: Bearer <access-token>` for protected endpoints
- `X-API-Version: 1` where clients already send explicit version headers
- `X-Correlation-Id: <uuid>` for traceability
- `Idempotency-Key: <stable-key>` for retry-safe mutation endpoints that support it

## Token Lifecycle

### 1. Credential login

Primary login flow:

- `POST /security-service/v1/auth/login`

Example request:

```bash
curl -X POST "http://localhost:8080/security-service/v1/auth/login" \
  -H "Content-Type: application/json" \
  -H "X-Correlation-Id: 550e8400-e29b-41d4-a716-446655440000" \
  -d '{
    "username": "advisor1",
    "password": "<redacted>"
  }'
```

Successful response shape:

```json
{
  "accessToken": "<jwt-access-token>",
  "refreshToken": "<jwt-refresh-token>"
}
```

### 2. Specialized token-pair issuance

Additional issuance endpoint:

- `POST /security-service/v1/auth/token-pair`

This endpoint does not accept username/password. It accepts:

- `subject`
- optional `roles`

Example request:

```bash
curl -X POST "http://localhost:8080/security-service/v1/auth/token-pair" \
  -H "Content-Type: application/json" \
  -d '{
    "subject": "advisor1",
    "roles": ["ADMIN"]
  }'
```

Successful response shape:

```json
{
  "accessToken": "<jwt-access-token>",
  "refreshToken": "<jwt-refresh-token>"
}
```

This is not the normal user-login contract. Consumers should prefer `/v1/auth/login` for interactive authentication.

### 3. Use the access token on protected APIs

```bash
curl -X GET "http://localhost:8080/workorder/v1/workorders/estimates" \
  -H "Authorization: Bearer <jwt-access-token>" \
  -H "X-Correlation-Id: 550e8400-e29b-41d4-a716-446655440001"
```

### 4. Refresh the token pair

- `POST /security-service/v1/auth/refresh`

```bash
curl -X POST "http://localhost:8080/security-service/v1/auth/refresh" \
  -H "Content-Type: application/json" \
  -H "X-Correlation-Id: 550e8400-e29b-41d4-a716-446655440002" \
  -d '{
    "refreshToken": "<jwt-refresh-token>"
  }'
```

Response:

```json
{
  "accessToken": "<new-jwt-access-token>",
  "refreshToken": "<new-jwt-refresh-token>"
}
```

### 5. Validate a token

- `GET /security-service/v1/auth/validate?token=...`

```bash
curl -G "http://localhost:8080/security-service/v1/auth/validate" \
  --data-urlencode "token=<jwt-access-token>"
```

Response:

```json
{
  "valid": true
}
```

### 6. Revoke a token

- `DELETE /security-service/v1/auth/revoke?token=...`

```bash
curl -X DELETE "http://localhost:8080/security-service/v1/auth/revoke?token=<jwt-access-token>" \
  -H "Authorization: Bearer <jwt-access-token>"
```

## Access-Token Claims

Current access tokens include:

- `sub`
- `uid`
- `username`
- `roles`
- `perm_bits`
- `perm_ver`
- optional `personId`
- `iss`, `aud`, `jti`, `iat`, `exp`

Current refresh tokens include:

- `sub`
- `uid`
- `type=refresh`
- `iss`, `aud`, `jti`, `iat`, `exp`

Refresh tokens do not carry `roles`, `perm_bits`, or `perm_ver`.

## How The Gateway Uses The Token

The gateway does not trust caller-supplied identity headers. It validates the bearer token and derives trusted downstream headers:

- `X-User`
- `X-User-Id`
- `X-Authorities`
- `X-Roles`

`X-Authorities` is derived from `perm_bits` plus `perm_ver`, not from a primary `authorities` claim in new tokens.

## Error Handling Expectations

- `401 Unauthorized`: missing, invalid, expired, malformed, or revoked token
- `403 Forbidden`: token valid but caller lacks the required permission
- `409 Conflict`: state conflict where endpoint-specific behavior defines it
- `422 Unprocessable Entity`: semantic validation failure when used by the endpoint

## Notes For Consumers

- Use `roles` for coarse UI gating only.
- Do not treat token `roles` as the backend authorization contract.
- Backend authorization is permission-based after gateway decoding.
- Older examples that describe `POST /validate`, `POST /revoke`, or authority-string access tokens are stale.
