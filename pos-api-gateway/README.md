# pos-api-gateway

Spring Cloud Gateway (WebFlux) acting as the single external entry point for the Durion Positivity ETSMS platform. Validates JWT bearer tokens, strips or propagates identity headers, routes requests to downstream microservices discovered through Eureka, and aggregates Swagger UI documentation from all services.

## Responsibilities

- Authenticate and authorise all inbound requests via local JWT validation (JJWT)
- Reject tokens that have been revoked, by consulting the shared Redis revocation key space
- Decode the `perm_bits` BitSet claim and map bit indexes to canonical `PERM_*` authority strings
- Route traffic to registered `pos-*` microservices using Eureka load-balanced routes (`lb://`)
- Strip inbound identity-spoofing headers before forwarding to downstream services
- Rewrite API version header (`X-API-Version: 1`) into URL path prefix (`/v1/`)
- Serve a unified Swagger UI aggregating OpenAPI specs from every registered service

## Key Classes

- `SecurityGatewayConfig` — reactive Spring Security filter chain; JWT validation and header injection
- `GatewayPermissionCatalog` — maps bit indexes to canonical authority strings; enforces `perm_ver`
- `GatewayAuthProperties` — binds `auth.*` and `pos.gateway.security.*` configuration properties
- `TokenRevocationChecker` / `RedisTokenRevocationChecker` — per-request revocation lookup; fails open
- `GatewayRevocationConfig` — picks the checker at startup and says at WARN when there is none
- `ApiVersionHeaderToPathFilter` — rewrites `X-API-Version` header value to URL path segment
- `OpenApiConfig` — aggregates per-service OpenAPI docs into the unified Swagger UI

## Routes (selected)

Routes strip the leading path prefix before forwarding to the upstream service.

| Prefix                 | Upstream Service        |
| ---------------------- | ----------------------- |
| `/accounting/**`       | `lb://ACCOUNTING`       |
| `/catalog/**`          | `lb://CATALOG`          |
| `/customer/**`         | `lb://CUSTOMER`         |
| `/inventory/**`        | `lb://INVENTORY`        |
| `/invoice/**`          | `lb://INVOICE`          |
| `/order/**`            | `lb://ORDER`            |
| `/workorder/**`        | `lb://WORKORDER`        |
| `/security-service/**` | `lb://SECURITY-SERVICE` |
| `/shop-manager/**`     | `lb://SHOP-MANAGER`     |

Discovery locator is disabled; only explicitly configured routes are exposed. `pos-tax` and `pos-events` are intentionally not routed externally.

## Configuration

| Property                                            | Default                     | Description                                            |
| --------------------------------------------------- | --------------------------- | ------------------------------------------------------ |
| `pos.gateway.security.strict-jwt-header-validation` | `true`                      | Reject unsafe JWT header patterns before introspection |
| `pos.gateway.security.allowed-jwt-algorithms`       | `HS256`                     | Permitted JWT `alg` values                             |
| `auth.token-identity-required`                      | `false`                     | Reject tokens missing `perm_bits` claim                |
| `auth.strip-inbound-identity-headers`               | `true`                      | Strip inbound `X-User`, `X-User-Id`, `X-Authorities`, `X-Perm-Bits`, `X-Perm-Ver`, `X-Roles`, `X-Loc-Fin-Bits`, `X-Loc-Oth-Bits`, `X-Loc-Scope` |
| `auth.tenant-host-suffix`                           | empty                       | When set (e.g. `.durionpos.org`), the login route gets `X-Tenant-Slug` from the request host's first label (`acme.durionpos.org` → `acme`); an inbound copy is always dropped (ADR-0062 §3) |
| `auth.auth-path-root`                               | `/security-service/v1/auth` | Public auth path that bypasses JWT checks              |
| `pos.gateway.security.revocation-check.enabled`     | `true`                      | Consult the revocation key space on every authenticated request |
| `pos.gateway.security.revocation-check.timeout`     | `150ms`                     | Ceiling on one lookup; on timeout the request is forwarded unchecked |
| `spring.data.redis.host` / `.port`                  | `localhost` / `6379`        | Redis holding the revocation keys (`SPRING_DATA_REDIS_HOST`/`_PORT`) |

## Location-scope passthrough (ADR-0061 §3, #1869)

From the validated access token the gateway derives three additional downstream headers and makes no scope
decision itself — the owning service enforces (`LocationScope` in `pos-security-common`):

| Header | Claim | Encoding |
| --- | --- | --- |
| `X-Loc-Fin-Bits` | `loc_fin_bits` | verbatim (Base64URL bitset, same indexes as `perm_bits`); set even when empty |
| `X-Loc-Oth-Bits` | `loc_oth_bits` | verbatim; set even when empty |
| `X-Loc-Scope` | `loc_scope` | Base64URL (no padding) of the claim's compact JSON `{"v":1,"nodes":[...]}`; **omitted** when the claim is absent — never synthesised, because "bits present, scope absent" is the issuer's fail-closed signal |

A token carrying none of the claims produces none of the headers. Inbound copies are stripped with the
other identity headers and, under `auth.reject-header-token-mismatch`, a conflicting inbound value is a 401.
A claim of the wrong JSON type is rejected as `auth.perm.decode.failure{reason=malformed_loc_claim}`.

## Token revocation (ADR-0061 §4, #1883)

Signature, issuer, audience and expiry are not enough. A token revoked by logout,
`revokeAllTokensForUser`, refresh rotation, or an ADR-0061 §4 reach-narrowing staffing change used to
keep passing the gateway until its `exp` — and the gateway is the boundary every downstream service
trusts. It now checks.

**The lookup.** After the claims validate, one `EXISTS jwt:revoked:{jti}` against the key space
`pos-security-service`'s `TokenRevocationManager` writes (key TTL = the token's own remaining life,
so revocations expire with what they revoke). It is reactive — the check runs inside the WebFlux auth
filter and must not block the event loop — and it never reads the `jwt_token` table: that is
pos-security-service's own schema, and cross-service DB access is not how this platform shares state.

A revoked token gets `401` with `code: TOKEN_REVOKED`. Every other auth failure gets `401` with
`code: UNAUTHORIZED`; the specific reason (bad signature, unknown `perm_ver`, malformed claim) is
logged and counted, never returned, because it is diagnostic for us and a probing oracle for anyone
else. Both carry the platform `ApiError` envelope (`docs/ERROR_ENVELOPE.md`) — before #1883 a gateway
401 had no body at all.

**Redis unavailable: fail open, loudly.** A lookup that errors or exceeds
`revocation-check.timeout` forwards the request unchecked. This matches the decision #1874 recorded
for the write side (`pos-security-service/README.md` → "Revocation on assignment change"): failing
closed would refuse every request platform-wide for the length of a Redis outage, while the ADR-0061
§4 `exp` clamp already bounds how long a stale token can live. The degraded state is never silent —
`auth.token.revocation.degraded` rises, and entering and leaving the degraded state each log a WARN
once, so an outage is a pair of log lines rather than one per request. For the same reason the Redis
health indicator is off (`management.health.redis.enabled: false`): a store the gateway is willing to
run without must not mark the platform's ingress unhealthy.

**Nothing is cached.** A negative cache is the obvious latency optimisation and its TTL is exactly
the window in which a revoked token still passes — the behaviour this exists to remove.

**Coverage gap.** A legacy token with no `jti` claim cannot be looked up and is forwarded;
`auth.token.revocation.skipped{reason=no_jti}` counts it. Only pre-`perm_ver` tokens lack the claim,
and the claim set is fixed by the issuer's signature, so this is not something a caller can arrange —
but it is real until legacy tokens stop being accepted.

### Metrics

| Metric | Type | Meaning |
| --- | --- | --- |
| `auth.token.revocation.rejected` | counter | Requests rejected because the token was revoked |
| `auth.token.revocation.degraded` | counter (`reason=timeout\|error`) | Lookups that could not be answered; the request was forwarded unchecked |
| `auth.token.revocation.skipped` | counter (`reason=no_jti`) | Tokens with no `jti` to look up |
| `auth.token.revocation.check.duration` | timer (`outcome=revoked\|clear\|degraded`) | Lookup latency |

### Latency

Measured on the auth filter's hot path (10 000 authenticated requests per arm, loopback Redis 7,
JDK 25; the full filter is timed, so the delta is the check):

| | p50 | p95 | p99 | mean |
| --- | --- | --- | --- | --- |
| without the check | 51 µs | 97 µs | 133 µs | 58 µs |
| with the check | 269 µs | 428 µs | 541 µs | 290 µs |

**+218 µs p50 / +331 µs p95** per authenticated request, which is one Redis round trip; a Redis
across a network adds its RTT on top of that. `revocation-check.timeout` (150 ms) bounds the worst
case: past it the request is forwarded rather than held. Read the real distribution from
`auth.token.revocation.check.duration` in a deployed environment rather than from this table.


## Dependencies

No internal `pos-*` module dependencies. Requires Spring Cloud Gateway, Netflix Eureka client, JJWT, and
Spring Data Redis (reactive) for the revocation check.

## Development

```bash
./mvnw -pl pos-api-gateway -am spring-boot:run
```

Swagger UI: `http://localhost:8080/swagger-ui.html`
