# Compact Permission Bitset Authorization Design

## Historical Status

This document is a historical design note and not the authoritative description of the live gateway authorization contract.

Use instead:

- `durion/docs/architecture/AUTHORIZATION_MODEL.md`
- `durion/docs/adr/0040-roles-jwt-permission-governance-policy.adr.md`

## Why It Is Historical

The original version explained the idea of compact permission-bitset tokens, but it no longer matched the whole runtime picture. It did not fully capture:

- the current access-token claims actually emitted by `JwtServiceImpl`
- the gateway's `perm_ver` enforcement rules
- the temporary legacy `authorities` fallback path
- the downstream `GatewayAuthoritiesFilter` behavior that expands `PERM_*` authorities
- the current catalog-version drift between security service and gateway

## What The Live Gateway Actually Does

Today the gateway:

1. validates the bearer token
2. rejects unknown `perm_ver` values
3. decodes `perm_bits` with `GatewayPermissionCatalog`
4. forwards trusted `X-Authorities` and `X-Roles`

That runtime contract is documented in the canonical authorization model, not here.
