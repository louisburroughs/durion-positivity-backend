---
title: "PRD: Spring Authentication and Account State Hardening"
status: "PROPOSED"
capability: "security-auth-hardening"
version: "1.0"
created: "2026-03-12"
authors: ["github-copilot"]
modules: ["pos-security-service", "pos-api-gateway"]
---

## Product Requirements Document — Spring Authentication and Account State Hardening

**Capability ID:** AUTH-HARDENING
**Module Scope:** `pos-security-service` authentication, user state management,
and JWT issuance; `pos-api-gateway` JWT enforcement alignment
**Platform:** Java 25, Spring Boot 4.x, Spring Security, JPA, JWT
**Priority:** Critical

## Executive Summary

### Problem Statement

`pos-security-service` currently performs user login with manual controller-level
credential checks and a minimal user state model. This limits Spring Security
integration, weakens account abuse controls, and does not provide first-class
support for account lockout, enablement, expiration states, or hardened
authentication workflows.

### Proposed Solution

Adopt Spring Security authentication mechanisms for credential verification
while retaining `pos-security-service` as the canonical JWT issuer. Harden the
user model with explicit account state and lockout metadata, and ensure issued
JWT access tokens continue to use compact permission encoding via `perm_bits`
and `perm_ver` as defined in the permission encoding design.

### Success Criteria

- 100% of interactive username/password authentication flows use Spring
  Security authentication components instead of manual controller password
  checks.
- 100% of successful access tokens issued by `pos-security-service` include the
  required greenfield claims: `sub`, `personId`, `jti`, `iat`, `exp`,
  `perm_bits`, and `perm_ver`.
- Repeated failed login attempts trigger automatic lockout according to policy,
  with no successful authentication allowed while locked.
- Disabled, expired, locked, and credentials-expired accounts are rejected with
  explicit, testable failure handling.
- Test coverage includes unit, integration, and security regression scenarios
  for all account states and lockout transitions.

## User Experience & Functionality

### User Personas

- Platform user signing in to obtain an access token.
- Security administrator enabling, disabling, unlocking, or expiring user
  accounts.
- API gateway consuming compact JWT permission claims.
- Backend service relying on gateway-authenticated context and canonical JWT
  claim semantics.

### User Stories

- As a platform user, I want to sign in with my username and password so that I
  receive a valid JWT access token.
- As a platform user, I want invalid credentials to fail consistently so that
  authentication behavior is predictable and secure.
- As a platform user, I want my account to be temporarily locked after repeated
  failed attempts so that credential stuffing and brute-force attacks are
  mitigated.
- As a security administrator, I want to disable or enable accounts so that I
  can control access without deleting user records.
- As a security administrator, I want to unlock locked accounts and inspect
  account state so that I can respond to support and security incidents.
- As an API gateway, I want issued tokens to contain `perm_bits` and `perm_ver`
  so that I can enforce authorization without synchronous round-trips.

### Acceptance Criteria

- Login requests are authenticated via Spring Security using
  `AuthenticationManager` and a `UserDetailsService` or equivalent provider.
- Controller code does not directly compare raw passwords against stored hashes.
- Successful login issues JWT access tokens from `JwtService` only after Spring
  Security reports authentication success.
- Lockout policy supports all of the following:
  fixed failure threshold, time-window evaluation, progressive backoff,
  automatic cooldown unlock, and administrator-triggered unlock.
- User records explicitly support the following states:
  enabled, locked, account expired, and credentials expired.
- Failure responses distinguish invalid credentials from account state failures
  while still preventing unnecessary credential disclosure.
- Administrative account-state mutations are auditable and exposed through
  explicit service operations.
- JWT claim generation remains aligned with greenfield permission encoding and
  does not reintroduce `authorities` as the token contract.

### Non-Goals

- No migration strategy for legacy data or legacy clients; this is a greenfield
  design.
- No support for browser session login, form-login pages, or cookie-based
  authentication.
- No OAuth2 authorization server implementation in this phase.
- No MFA, password reset, or self-service account recovery in this phase.
- No compatibility layer for the current `/v1/users/login` contract.

## AI System Requirements

Not applicable.

## Technical Specifications

### Architecture Overview

The target design separates authentication from token issuance while keeping
both inside `pos-security-service`.

1. Client submits credentials to a dedicated authentication endpoint such as
   `POST /v1/auth/login`.
2. Controller binds a typed request DTO and delegates to an authentication
   service.
3. The authentication service calls Spring Security's `AuthenticationManager`
   using `UsernamePasswordAuthenticationToken`.
4. Spring Security resolves the user through a custom `UserDetailsService`
   backed by the `users` table and account-state metadata.
5. The authentication provider validates password hash, enabled state, locked
   state, account expiration, and credential expiration.
6. On success, the service resolves effective permissions from persisted user
   assignments and roles.
7. `JwtService` issues a JWT containing `perm_bits` and `perm_ver` in the
   greenfield contract.
8. On failure, Spring authentication exceptions are translated to the standard
   API error envelope and audit/event logging.

### Target Component Design

#### Authentication Flow

- Introduce a dedicated authentication controller under `/v1/auth`.
- Use typed request and response DTOs rather than raw `Map` payloads.
- Authenticate with Spring Security components, not manual password checks.
- Keep JWT issuance in `JwtServiceImpl` as the only token-writing component.

#### Spring Security Components

- `AuthenticationManager` remains the central credential verification entry
  point.
- `CustomUserDetailsService` must return a custom `UserDetails`
  implementation or equivalent principal that includes:
  user UUID, username, password hash, enabled, account non-locked,
  account non-expired, credentials non-expired, and roles.
- Introduce a dedicated authentication service to orchestrate:
  authentication, lockout bookkeeping, success bookkeeping, and token issuance.
- Configure exception translation for Spring authentication failures so API
  responses remain explicit and consistent.

#### JWT Contract Requirements

The access token contract for this PRD must remain aligned with the service's
greenfield permission encoding design.

Required access token claims:

- `sub`: stable subject identifier.
- `personId`: stable person identifier for audit lineage.
- `jti`: token identifier.
- `iat`: issued-at timestamp.
- `exp`: expiration timestamp.
- `perm_bits`: Base64URL-encoded permission bitset.
- `perm_ver`: permission catalog version.

Optional claims:

- `roles`: informational only.

Explicit rules:

- Do not trust caller-supplied roles during token issuance.
- Do not include `authorities` in the access token for greenfield mode.
- Authentication success is necessary but not sufficient for issuance;
  permissions must still be resolved from persisted assignments.

### API Surface Changes

This PRD assumes the public API contract may change.

#### Authentication Endpoints

Recommended endpoint set:

- `POST /v1/auth/login`
  - Request: `LoginRequest { username, password }`
  - Success: `200 OK` with `TokenResponse` or `TokenPairResponse`
  - Failure: `401 Unauthorized` for invalid credentials,
    `423 Locked` or `403 Forbidden` for account-state denials depending on
    final ADR decision, and `400 Bad Request` for malformed requests.
- `POST /v1/auth/refresh`
  - Remains available if refresh token flow is retained.
- `POST /v1/auth/logout` or `DELETE /v1/auth/token`
  - Optional revocation endpoint if explicit logout remains required.

#### Administrative Account-State Endpoints

Recommended additions or replacements:

- `POST /v1/users/{id}/unlock`
- `POST /v1/users/{id}/enable`
- `POST /v1/users/{id}/disable`
- `POST /v1/users/{id}/expire-account`
- `POST /v1/users/{id}/expire-credentials`
- `GET /v1/users/{id}/account-state`

These operations may alternatively be modeled through a consolidated admin
command endpoint, but the service contract must support the full account-state
surface explicitly.

### Schema Changes

#### Users Table

Extend the `users` table with the following fields.

| Column | Type | Null | Purpose |
| --- | --- | --- | --- |
| `enabled` | `BOOLEAN` | No | Whether the account may authenticate |
| `account_non_locked` | `BOOLEAN` | No | Canonical lock state for Spring Security |
| `account_non_expired` | `BOOLEAN` | No | Canonical account-expiration state |
| `credentials_non_expired` | `BOOLEAN` | No | Canonical credential-expiration state |
| `failed_login_attempts` | `INT` | No | Consecutive failed attempts counter |
| `last_failed_login_at` | `TIMESTAMP` | Yes | Timestamp of most recent failed login |
| `last_successful_login_at` | `TIMESTAMP` | Yes | Timestamp of most recent successful login |
| `locked_at` | `TIMESTAMP` | Yes | When the account became locked |
| `locked_until` | `TIMESTAMP` | Yes | Auto-unlock deadline for cooldown logic |
| `disabled_at` | `TIMESTAMP` | Yes | When the account was disabled |
| `disabled_by` | `VARCHAR` | Yes | Actor who disabled the account |
| `account_expires_at` | `TIMESTAMP` | Yes | Optional expiration deadline |
| `credentials_expire_at` | `TIMESTAMP` | Yes | Optional credential-expiration deadline |
| `last_login_ip` | `VARCHAR` | Yes | Optional security telemetry |
| `last_login_user_agent` | `VARCHAR` | Yes | Optional security telemetry |

Required defaults for greenfield creation:

- `enabled = true`
- `account_non_locked = true`
- `account_non_expired = true`
- `credentials_non_expired = true`
- `failed_login_attempts = 0`

#### Supporting Constraints and Indexes

- Index `username` uniquely if not already enforced.
- Add an index on `locked_until` for operational reporting if query volume
  warrants it.
- Add a check or service-level invariant ensuring locked accounts always set
  `account_non_locked = false`.
- Add a check or service-level invariant ensuring disabled accounts may set
  `enabled = false` independently of lock state.

### Model Changes

#### User Entity

Extend `User` to include:

- `boolean enabled`
- `boolean accountNonLocked`
- `boolean accountNonExpired`
- `boolean credentialsNonExpired`
- `int failedLoginAttempts`
- `Instant lastFailedLoginAt`
- `Instant lastSuccessfulLoginAt`
- `Instant lockedAt`
- `Instant lockedUntil`
- `Instant disabledAt`
- `String disabledBy`
- `Instant accountExpiresAt`
- `Instant credentialsExpireAt`
- optional login telemetry fields

The entity must remain auditable with `createdAt` and `updatedAt`.

#### Principal Model

Introduce a dedicated authenticated principal type, for example
`SecurityUserPrincipal`, that exposes:

- user ID
- username
- password hash
- roles
- enabled
- accountNonLocked
- accountNonExpired
- credentialsNonExpired

This principal should back Spring Security authentication and remove the need
for controller-specific auth DTOs that expose password-hash data.

#### DTO Changes

Add or update DTOs for:

- `LoginRequest`
- `TokenResponse` or `TokenPairResponse`
- `UserAccountStateResponse`
- `UpdateUserAccountStateRequest` or explicit command DTOs
- admin-facing response fields that surface state safely without exposing
  secrets

### Lockout and Hardening Policy

The implementation must support all of the following behaviors.

#### Baseline Lockout

- Increment failed-attempt counters on each authentication failure caused by
  invalid credentials.
- Lock the account after a configurable threshold such as
  5 failed attempts within 15 minutes.
- Reset consecutive failure counters after successful authentication.

#### Progressive Backoff

- Before permanent or timed lockout is reached, the system may impose increasing
  delay windows for repeated failures.
- Backoff behavior must be deterministic and configurable.

#### Automatic Cooldown Unlock

- If `locked_until` is in the past, the account may be auto-unlocked by the
  authentication flow or a scheduled reconciliation path.
- Auto-unlock must also clear or reset the related lockout metadata.

#### Administrative Unlock

- Administrators must be able to explicitly unlock a user regardless of
  cooldown timing.
- Administrative unlock must record actor identity and timestamp.

#### Disablement and Expiration

- Disabled accounts must never authenticate, regardless of password validity.
- Account-expired users must be denied until the expiration condition is
  reversed or extended.
- Credentials-expired users must be denied interactive login until credentials
  are rotated or reset.

### Failure Mapping and Error Contract

The implementation must use the standard error envelope for non-2xx responses
with:

- `code`
- `message`
- `status`
- `timestamp`
- `correlationId`

Recommended authentication error codes:

- `INVALID_CREDENTIALS`
- `ACCOUNT_LOCKED`
- `ACCOUNT_DISABLED`
- `ACCOUNT_EXPIRED`
- `CREDENTIALS_EXPIRED`
- `INVALID_REQUEST`

Final status-code mapping must align with platform controller standards and any
auth-specific ADR decisions.

### Security and Privacy Requirements

- Never return password hashes or raw credential details to clients.
- Never expose whether a username exists through divergent low-detail error
  semantics for invalid credentials.
- Preserve detailed server-side audit logging for lockout transitions,
  enablement changes, disablement changes, and successful authentication.
- Ensure token issuance is blocked when account-state validation fails.
- Keep secrets externalized and maintain the minimum JWT secret requirements.
- Ensure gateway and downstream services continue to rely on validated token
  claims rather than caller-provided identity headers.

### Eventing and Observability

Authentication and account-state transitions should emit auditable events,
including at minimum:

- login success
- login failure
- account locked
- account unlocked
- account enabled
- account disabled
- credentials expired
- account expired

Metrics should include:

- authentication success count
- authentication failure count
- lockout count
- unlock count
- disabled-login denial count
- expired-account denial count
- expired-credentials denial count

## Test Criteria

### Unit Tests

- `UserDetailsService` correctly maps persisted user state into Spring Security
  account flags.
- Authentication service invokes `AuthenticationManager` and does not compare
  raw passwords directly.
- Failed login bookkeeping increments counters correctly.
- Successful login resets counters and updates last-success metadata.
- Lockout logic transitions accounts into and out of locked state correctly.
- JWT issuance after successful authentication includes `perm_bits` and
  `perm_ver` and omits `authorities` for greenfield mode.

### Integration Tests

- Valid credentials for an enabled, non-expired, unlocked user return a token.
- Invalid credentials return the configured auth failure response.
- Disabled accounts cannot log in.
- Locked accounts cannot log in until unlocked or cooldown expires.
- Account-expired users cannot log in.
- Credentials-expired users cannot log in.
- Automatic cooldown unlock permits authentication after the lock period passes.
- Administrative unlock restores login capability when other states permit it.
- Administrative enable/disable and expiration endpoints mutate state correctly.
- Refresh-token flows, if retained, preserve greenfield token claim semantics.

### Security Regression Tests

- Repeated failed attempts trigger lockout at the configured threshold.
- Progressive backoff delays or denial thresholds behave as configured.
- Successful authentication is impossible while `enabled = false`.
- Successful authentication is impossible while
  `account_non_locked = false` and `locked_until` is still active.
- Successful authentication is impossible while
  `account_non_expired = false` or `account_expires_at` is in the past.
- Successful authentication is impossible while
  `credentials_non_expired = false` or `credentials_expire_at` is in the past.
- JWT issuance never occurs when authentication fails.
- Tokens generated after authentication still decode correctly in the gateway's
  permissions encoding flow.

### Contract Tests

- Authentication endpoint request and response DTOs serialize as documented.
- Non-2xx auth responses include the standard error envelope and correlation ID.
- Administrative account-state endpoints honor expected status codes and error
  semantics.

### Data and Persistence Tests

- New user creation populates default account-state fields.
- State transitions persist expected timestamps and counters.
- Unlock actions clear lock metadata consistently.
- Disable actions preserve lock metadata without re-enabling the user.

## Risks & Roadmap

### Phased Rollout

#### MVP

- Introduce Spring-authenticated login flow.
- Add account-state schema and entity fields.
- Add fixed-threshold lockout and enable/disable support.
- Align JWT issuance with greenfield permission encoding.

#### v1.1

- Add progressive backoff.
- Add account expiration and credential expiration admin controls.
- Add richer audit events and operational dashboards.

#### v2.0

- Add optional step-up auth, password reset, or MFA if required.
- Add risk-based security policies and anomaly detection if needed.

### Technical Risks

- Incorrect state mapping between persisted fields and Spring Security account
  flags may create false positives or false negatives in login denial.
- Lockout bookkeeping bugs may permit brute-force attempts or create permanent
  denial of service for valid users.
- JWT claim rewrites may drift from gateway expectations if permission encoding
  implementation is not completed consistently.
- Administrative state endpoints may become inconsistent if state transition
  rules are not centralized.
- Existing tests may not cover time-dependent lockout behavior without
  consistent `Clock` injection.

### Open Design Decisions

- Final endpoint shape for account-state administration:
  explicit action endpoints versus a consolidated command API.
- Final status code for locked-account login denial.
- Whether login returns access token only or access plus refresh token by
  default.
- Whether login telemetry fields are mandatory or optional in the first phase.

### Exit Criteria

- Spring Security authenticates all username/password login flows.
- The user model supports enabled, locked, account-expired, and
  credentials-expired states with persisted metadata.
- Lockout policy supports threshold, cooldown, progressive backoff, and admin
  unlock.
- Access tokens remain greenfield-compliant with `perm_bits` and `perm_ver`.
- Test suites cover success paths, state denials, lockout behavior, and token
  claim correctness.
