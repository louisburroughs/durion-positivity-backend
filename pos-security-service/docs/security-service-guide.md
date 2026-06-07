# Security Service Administrator Guide

This guide describes the administrative capabilities exposed by `pos-security-service` and the permissions required to use them.

For the runtime model of roles, `perm_bits`, tokens, gateway decoding, and downstream authorization, see:

- `durion/docs/architecture/AUTHORIZATION_MODEL.md`

## What This Service Owns

`pos-security-service` is the platform authority for:

- user authentication
- token issuance and refresh
- user records and account state
- role definitions
- permission registration
- role assignments and permission lookup
- security audit records

The service does not enforce most business API permissions directly after login. Normal API authorization happens later in the gateway and downstream services.

## Important Authorization Principle

- Roles are coarse assignment and UX signals.
- APIs are enforced with permissions.
- Access tokens carry `roles`, `perm_bits`, and `perm_ver`.
- The gateway turns token permissions into trusted `X-Authorities` for downstream `@PreAuthorize` checks.

Do not treat labels such as "Admin" or "Manager" as the authoritative API contract. The authoritative contract is the permission string on the controller method.

## Authentication Endpoints

### Public authentication

- `POST /v1/auth/login`
- `POST /v1/auth/self-register`
- `POST /v1/auth/token-pair`
- `POST /v1/auth/refresh`
- `GET /v1/auth/validate`

These endpoints are public or self-service entry points and are not guarded by an admin permission.

### Authenticated token utilities

- `DELETE /v1/auth/revoke`
- `GET /v1/auth/roles`
- `GET /v1/auth/subject`
- `GET /v1/auth/user-id`

Required permission:

- authenticated user context for the current token

### Internal token issuance

- `POST /v1/auth/internal/token`

Required permission:

- `security:token:issue_internal`

## User Administration

Endpoints under `/v1/users` use these permissions:

- create user: `security:user:create`
- view users or one user: `security:user:view`
- edit user: `security:user:edit`
- delete user: `security:user:delete`
- assign or replace direct user roles: `security:role:assign`

Account-state operations use:

- view account state: `security:user_account_state:view`
- unlock, enable, disable, expire account, expire credentials: `security:user_account_state:manage`

Self-registration review uses:

- list and view review cases: `security:user_account_state:view`
- resolve review cases: `security:user_account_state:manage`

## Role And Assignment Administration

Role endpoints under `/v1/roles` and `/v1/users/roles` use:

- create role: `security:role:create`
- view roles and assignments: `security:role:view`
- edit role permissions: `security:role:edit`
- delete role: `security:role:delete`
- create or revoke role assignments: `security:role:assign`

Permission lookup helpers use:

- view effective user permissions: `security:permission:view`
- check whether a user has a permission: `security:permission:view`

## Permission Catalog Administration

Permission endpoints under `/v1/permissions` use:

- view permissions, validate names, check existence, decode bitsets: `security:permission:view`
- register permissions: `security:permission:register`

`GET /v1/permissions/catalog-version` is public.

## Audit Operations

Audit endpoints use:

- create audit events and snapshots: `security:audit:create`
- view audit events and snapshots: `security:audit:view`
- export audit data: `security:audit:export`

## Specialized Or Legacy Security Endpoints

Two controllers exist but are not the primary request-authorization path:

- `AuthorizationController`
  - required permission: `security:authorization:decide`
- `PrincipalRoleController`
  - required permission: `security:role:assign`

These support specialized RBAC workflows. They do not replace gateway-enforced authorization on ordinary application APIs.

## Operational Notes

- Role changes affect newly issued or refreshed tokens, not already-issued access tokens.
- Access-token permissions are currently emitted from `RoleAuthorityServiceImpl`, which is a hardcoded role expansion layer.
- Persisted role-permission mappings also exist, so administrators should expect documentation and runtime drift until remediation aligns those models.
- Current documentation of known drift is maintained in `durion/docs/architecture/AUTHORIZATION_MODEL.md`.
