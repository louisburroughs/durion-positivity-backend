---
title: "Platform Auth and Token Usage Guide"
status: "ACTIVE"
capability: "platform-auth-token-usage"
version: "1.0"
created: "2026-03-17"
authors: ["github-copilot"]
modules: ["pos-security-service", "pos-api-gateway"]
---

## Platform-Wide Auth and Token Usage Guide

This guide is for gateway consumers calling Durion backend APIs through
`pos-api-gateway`.

## Audience and Scope

Use this guide if your client:

- calls backend modules through gateway route prefixes,
- authenticates users via `pos-security-service`,
- needs access and refresh token handling patterns,
- needs consistent request headers across all module calls.

This guide covers:

- token issuance,
- token refresh,
- token validation,
- token revocation,
- calling protected APIs with bearer tokens,
- request header conventions (`X-API-Version`, `X-Correlation-Id`,
  `Idempotency-Key`).

## Gateway URL Model

Gateway base URL example:

```text
http://localhost:8080
```

Security service route prefix at the gateway:

```text
/security-service
```

Security API login path in service OpenAPI:

```text
/v1/auth/login
```

Gateway consumer path for login:

```text
/security-service/v1/auth/login
```

The same route-prefix rule applies for other modules, for example:

```text
/workorder/v1/workorders/estimates
/inventory/v1/... 
/order/v1/...
```

## Required and Recommended Headers

Use these headers consistently:

- `Authorization: Bearer <access-token>` for protected endpoints.
- `X-API-Version: 1` for explicit API versioning.
- `X-Correlation-Id: <uuid>` on every request for traceability.
- `Idempotency-Key: <stable-key>` for retry-safe mutation endpoints.

Example standard header set:

```http
Authorization: Bearer eyJhbGciOiJIUzI1NiIs...
X-API-Version: 1
X-Correlation-Id: 550e8400-e29b-41d4-a716-446655440000
```

## Token Lifecycle

### 1. Login (Access + Refresh)

Preferred endpoint for a token pair:

- `POST /security-service/v1/auth/token-pair`

Alternative endpoint used by some clients:

- `POST /security-service/v1/auth/login`

Example request:

```bash
curl -X POST "http://localhost:8080/security-service/v1/auth/token-pair" \
  -H "Content-Type: application/json" \
  -H "X-API-Version: 1" \
  -H "X-Correlation-Id: 550e8400-e29b-41d4-a716-446655440000" \
  -d '{
    "username": "advisor1",
    "password": "<redacted>"
  }'
```

Example successful response shape:

```json
{
  "accessToken": "<jwt-access-token>",
  "refreshToken": "<jwt-refresh-token>",
  "tokenType": "Bearer",
  "expiresIn": 3600
}
```

### 2. Use Access Token on Protected APIs

Example protected call to a gateway-exposed module:

```bash
curl -X GET "http://localhost:8080/workorder/v1/workorders/estimates" \
  -H "Authorization: Bearer <jwt-access-token>" \
  -H "X-API-Version: 1" \
  -H "X-Correlation-Id: 550e8400-e29b-41d4-a716-446655440001"
```

### 3. Refresh Access Token

Use refresh endpoint when access token is expired or near expiry:

- `POST /security-service/v1/auth/refresh`

Example request:

```bash
curl -X POST "http://localhost:8080/security-service/v1/auth/refresh" \
  -H "Content-Type: application/json" \
  -H "X-API-Version: 1" \
  -H "X-Correlation-Id: 550e8400-e29b-41d4-a716-446655440002" \
  -d '{
    "refreshToken": "<jwt-refresh-token>"
  }'
```

### 4. Validate Token

Use validate endpoint for diagnostics and session checks:

- `POST /security-service/v1/auth/validate`

Example request:

```bash
curl -X POST "http://localhost:8080/security-service/v1/auth/validate" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <jwt-access-token>" \
  -H "X-API-Version: 1" \
  -H "X-Correlation-Id: 550e8400-e29b-41d4-a716-446655440003"
```

### 5. Revoke Token

Use revoke endpoint for explicit session invalidation:

- `POST /security-service/v1/auth/revoke`

Example request:

```bash
curl -X POST "http://localhost:8080/security-service/v1/auth/revoke" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <jwt-access-token>" \
  -H "X-API-Version: 1" \
  -H "X-Correlation-Id: 550e8400-e29b-41d4-a716-446655440004"
```

## Idempotent Mutation Example Through Gateway

For retry-safe create/update APIs that support idempotency, pass a stable
`Idempotency-Key` per logical request.

Example (`workorder` estimate create):

```bash
curl -X POST "http://localhost:8080/workorder/v1/workorders/estimates" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <jwt-access-token>" \
  -H "X-API-Version: 1" \
  -H "X-Correlation-Id: 550e8400-e29b-41d4-a716-446655440005" \
  -H "Idempotency-Key: estimate-create-veh-1234-20260317" \
  -d '{
    "customerId": "550e8400-e29b-41d4-a716-446655440010",
    "vehicleId": "550e8400-e29b-41d4-a716-446655440011",
    "locationId": "550e8400-e29b-41d4-a716-446655440020"
  }'
```

## Error Handling Expectations

Treat these statuses consistently:

- `401 Unauthorized`: token missing, invalid, expired, or malformed.
- `403 Forbidden`: token valid but principal lacks required permission.
- `404 Not Found`: resource not found or not visible to caller context.
- `409 Conflict`: state conflict or idempotency collision semantics.
- `422 Unprocessable Entity`: semantic validation failure.
- `500 Internal Server Error`: unexpected backend failure.

Recommended behavior:

- On `401`: attempt refresh once, then require re-login.
- On `403`: surface actionable permission error to caller.
- On `409`: follow endpoint-specific conflict/replay behavior.
- Always log `X-Correlation-Id` for diagnostics.

## Client Pattern (TypeScript)

```ts
type TokenPair = {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
};

const gatewayBaseUrl = "http://localhost:8080";

async function login(username: string, password: string): Promise<TokenPair> {
  const response = await fetch(
    `${gatewayBaseUrl}/security-service/v1/auth/token-pair`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-API-Version": "1",
        "X-Correlation-Id": crypto.randomUUID()
      },
      body: JSON.stringify({ username, password })
    }
  );

  if (!response.ok) {
    throw new Error(`Login failed with status ${response.status}`);
  }

  return (await response.json()) as TokenPair;
}

async function callEstimates(accessToken: string): Promise<Response> {
  return fetch(`${gatewayBaseUrl}/workorder/v1/workorders/estimates`, {
    method: "GET",
    headers: {
      Authorization: `Bearer ${accessToken}`,
      "X-API-Version": "1",
      "X-Correlation-Id": crypto.randomUUID()
    }
  });
}
```

## Security Notes

- Do not log raw access or refresh tokens.
- Keep refresh tokens in secure storage only.
- Use short-lived access tokens and refresh rotation.
- Never call internal token endpoints from external clients unless explicitly
  approved for internal platform use.

## Operational Checklist for Gateway Consumers

- Use gateway route prefixes (do not call internal service hostnames directly).
- Send `Authorization` for protected endpoints.
- Send `X-API-Version` and `X-Correlation-Id` on every request.
- Use `Idempotency-Key` for retry-safe mutations.
- Implement refresh-on-401 once, then force re-auth.
- Capture correlation IDs in logs and support tickets.
