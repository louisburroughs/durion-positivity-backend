# Permissions Encoding and Token-Based User Validation Plan

## Decision

Use validated JWT claims as the source of user identity and permissions.

- Identity source: token claims (`sub` as canonical identifier, `username` as secondary/user-facing claim)
- Authorization source: decoded permissions from `perm_bits` + `perm_ver`
- Request headers such as `X-User` and `X-Authorities` are not trusted from external callers

## Best-Practice Validation

Using token claims instead of caller-provided headers is a security best practice because:

- JWT claims are signed and can be cryptographically verified
- External headers are client-controlled and spoofable unless stripped/overwritten
- A single trust source (validated token) prevents identity and permission drift between layers

Implementation rule:

- Always validate token first, then derive identity/authorities from token claims
- Strip/ignore inbound identity headers at gateway edge
- If forwarding identity headers downstream, generate them from validated token claims only

## Scope

- Implement compact permission encoding (`perm_bits`, `perm_ver`) end-to-end in gateway authorization flow
- Implement user validation based on token claims instead of header identity
- Harden authorization behavior for version mismatch, decode failures, and unknown users

## Implementation Checklist

- [ ] Define canonical claim contract and update docs/contracts where needed
- [ ] Implement/verify bitset decode pipeline in gateway
- [ ] Replace header-based identity resolution with token-claim-based validation
- [ ] Enforce inbound header stripping/ignore policy
- [ ] Add failure-path controls and observability
- [ ] Add unit/integration/security regression tests
- [ ] Roll out with feature flags and monitoring

## Phase Plan

### Phase 1: Contract and Security Rules

- [ ] Lock JWT claim contract:
  - Required: `sub`, `perm_bits`, `perm_ver`, `iat`, `exp`
  - Recommended: `username`, `jti`
- [ ] Define claim precedence rule: token claims always override request headers
- [ ] Document fail-closed behavior for missing/invalid claims
- [ ] Confirm catalog version policy for `perm_ver`

### Phase 2: Gateway Token Validation and Permission Decode

- [ ] Ensure JWT signature/issuer/audience/expiry validation occurs before claim use
- [ ] Decode `perm_bits` via BitSet codec and map to authorities using `perm_ver`
- [ ] Reject unknown `perm_ver` and malformed `perm_bits`
- [ ] Build authenticated principal from token claims (`sub`, optional `username`)

### Phase 3: User Validation by Token Username/Subject

- [ ] Replace header-based user validation path with token-claim-based lookup
- [ ] Validate token user (`sub` and/or `username`) against active user/principal record
- [ ] Fail closed when user is missing/disabled/mismatched
- [ ] Prefer `sub` for canonical joins; use `username` for display/audit and compatibility

### Phase 4: Header Trust Boundary Hardening

- [ ] Strip inbound `X-User`, `X-Authorities`, and related identity headers from external requests
- [ ] If downstream headers are required, generate them from validated token context only
- [ ] Add defensive checks to block mixed-source identity (header identity != token identity)

### Phase 5: Authorization Enforcement

- [ ] Enforce endpoint authorization from decoded token authorities only
- [ ] Remove/disable legacy paths that trust caller-provided authority headers
- [ ] Keep role/permission mapping deterministic and catalog-version-aware

### Phase 6: Observability and Incident Readiness

- [ ] Add metrics:
  - tokenValidationFailures
  - permissionDecodeFailures
  - unknownPermissionCatalogVersion
  - userValidationFailures
- [ ] Add structured logs for auth failures (without token secrets/PII leakage)
- [ ] Add dashboards/alerts for spikes in deny/failure rates

### Phase 7: Testing

- [ ] Unit tests:
  - bitset decode correctness
  - `perm_ver` mismatch handling
  - identity extraction precedence (token over headers)
- [ ] Integration tests:
  - spoofed `X-User` ignored
  - valid token accepted and authorities propagated
  - disabled/unknown user denied
- [ ] Security tests:
  - invalid signature
  - expired token
  - missing required claims
  - malformed `perm_bits`

### Phase 8: Rollout Strategy

- [ ] Add temporary feature flags for staged migration if needed:
  - `auth.token-identity-required`
  - `auth.strip-inbound-identity-headers`
  - `auth.reject-header-token-mismatch`
- [ ] Deploy in monitor mode first, then enforce mode
- [ ] Remove legacy header-trust code after stabilization

## Open Questions

1. Canonical identity key finalization:
- Should all persistence joins use `sub` only, with `username` non-authoritative?

2. Downstream compatibility:
- Which services still require propagated identity headers, and can they read token-derived context directly instead?

3. Token claim naming consistency:
- Keep `username` claim name or standardize to a single claim key across services?

4. Revocation strategy depth:
- Is short token TTL sufficient, or should `jti` revocation checks be mandatory for sensitive endpoints?

5. Migration window policy:
- What is the acceptable period for dual-read/compatibility mode before full enforcement?

## Exit Criteria

- [ ] No production path trusts caller-supplied identity headers
- [ ] User identity and authorities are consistently derived from validated token claims
- [ ] All tests pass for success and failure paths
- [ ] Monitoring confirms stable deny/error rates after enforcement
