# Security Service — Administrator Guide

This guide is for platform administrators and security operators who manage user accounts, roles, permissions, and access policies in the Durion Positivity platform. It describes what each capability does and who is authorised to use it.

---

## What the Security Service Does

The security service is the platform's single authority for:

- authenticating users and issuing access tokens,
- managing user accounts and their active/locked/disabled state,
- defining roles and assigning them to users,
- maintaining the permission catalog that backend services enforce,
- enforcing automated account lockout after repeated failed logins,
- providing an immutable audit trail of all state-changing operations.

All platform API calls pass through the API gateway, which validates the caller's token before any backend service receives the request. The security service issues those tokens; it does not participate in normal API traffic once a token has been issued.

---

## Concepts

| Term | What it means |
|---|---|
| **User** | A login account that can authenticate to the platform. Each user is linked to exactly one Person record. Only one active user account per person is permitted at any time. |
| **Person** | The canonical human identity record managed by the people service. The person identifier is the stable audit actor used across all platform services — it does not change when a username changes. |
| **Role** | A named grouping of permissions that is assigned to a user. The frontend uses roles for high-level access decisions (e.g. showing or hiding features). |
| **Permission** | A granular authorisation unit in the form `domain:resource:action` (e.g. `security:user:create`). Backend services enforce permissions on individual API operations. Permissions are derived from a user's assigned roles. |
| **Account state** | A set of flags on a user account: enabled/disabled, locked/unlocked, account-expired, and credentials-expired. Any non-active flag prevents the user from logging in. |

---

## Authentication and Sessions

### Login

Users authenticate with a username and password. A successful login returns an access token and a refresh token.

- **Access tokens** are short-lived. They expire after one hour.
- **Refresh tokens** are longer-lived and are used to obtain a new access token without re-entering credentials. They expire after seven days.

An access token carries the user's current permissions at the moment it was issued. Changes to a user's roles or permissions take effect at the next token refresh or re-login.

### Token refresh

Clients should refresh the access token automatically before it expires. The new access token reflects the user's current role and permission assignments at the time of refresh — any role changes since the previous login take effect immediately on the next refresh.

### Token revocation

An administrator or the user themselves can immediately invalidate an access token. Revoked tokens are rejected even if they have not yet expired. This is used to force a user out of active sessions during a security incident or following account deactivation.

### Session security

- Access tokens must be treated as secrets. They must not be logged or stored in insecure locations.
- Refresh tokens must be stored in secure, session-bound storage only.
- Clients should treat an expired or rejected token as a signal to re-authenticate.

---

## Self-Registration

External users can create their own low-privilege account through the self-registration flow. The flow is designed to prevent duplicate identities from being created.

### What happens during self-registration

1. The service checks whether a user account already exists for the provided username or email. If one does, registration is blocked.
2. The service searches for an existing Person record matching the provided identity attributes (name, email, phone). If a matching person is found who already has an active linked account, registration is blocked and the user is directed to log in or contact support.
3. If no credible person match exists, a new Person record is created.
4. A user account is created and linked to the person record.
5. The new account is assigned the `SELF_SERVICE_CUSTOMER` role only. No staff, administrative, or workflow permissions are granted automatically.
6. Registration succeeds without issuing a token — the user must perform a separate login.

### Why registration may be blocked

| Reason | Admin action |
|---|---|
| An active account already exists for this email or username. | Direct the user to log in or use account recovery. |
| The matched Person already has a different active account. | Investigate whether this is a duplicate registration attempt or a legitimate access request. |
| Identity attributes are ambiguous — multiple possible matches found. | A review case is created; see the review queue below. |
| The matched Person has a prior inactive or disabled account. | Reactivate or unlock the existing account rather than creating a new one. |

### Self-registration review queue

When a registration attempt is blocked due to an ambiguous identity match, a review case is created for administrator investigation. Administrators can list open cases, view the details of a specific case, and mark a case as resolved once the situation has been clarified.

**Required role(s):** Admin (to view and resolve review cases)

---

## User Management

### Creating a user

New user accounts can be created by an administrator with a username, initial password, and optional role assignments.

**Required role(s):** Admin

### Viewing users

Administrators can list all user accounts or retrieve the details of a specific user by their unique identifier.

**Required role(s):** Admin

### Updating a user

User details — including username, password, and enabled state — can be updated by an administrator at any time.

**Required role(s):** Admin

### Deleting a user

Permanently removes a user record. Consider disabling the account instead if the record may be needed for audit purposes.

**Required role(s):** Admin

---

## Account State Management

Account state flags control whether a user can authenticate. Any flag that is non-active causes the login attempt to fail. Administrators manage these flags through the account state controls.

### Account state flags

| Flag | Blocked condition | How it is set |
|---|---|---|
| **Enabled** | Account is disabled. | Manually by an administrator. |
| **Locked** | Account is locked. | Automatically by the lockout policy, or manually by an administrator. |
| **Account expired** | Account has expired. | Manually by an administrator. |
| **Credentials expired** | Password has expired. | Manually by an administrator; also used to force password resets. |

### Viewing account state

An administrator can retrieve the current state flags for any user account.

**Required role(s):** Admin

### Unlocking an account

Removes the locked flag from a user account, allowing the user to attempt login again. Use this after investigating a lockout triggered by the automated lockout policy.

**Required role(s):** Admin

### Enabling and disabling an account

Disabling an account immediately prevents the user from authenticating without deleting their record. Re-enabling restores access.

**Required role(s):** Admin

### Expiring an account or credentials

Expiring an account prevents all further authentication. Expiring credentials forces the user to reset their password before the next login succeeds.

**Required role(s):** Admin

### Automated lockout policy

The platform enforces an automatic account lockout after repeated failed login attempts. The defaults are:

| Parameter | Default |
|---|---|
| Failed attempts before lockout | 5 |
| Rolling evaluation window | 10 minutes |
| Progressive backoff multiplier | 2× per successive lockout |
| Maximum backoff duration | 30 minutes |

Failed attempts older than the rolling window are not counted. After the threshold is reached, the account is locked automatically. Each successive lockout after the first doubles the lockout duration up to the maximum.

Locked accounts must be unlocked manually by an administrator via the account state controls. The lockout parameters are configurable at deployment time.

---

## Role Management

Roles group permissions together and are assigned to users. Changes to a role's permission set take effect at the user's next token refresh or re-login.

### Creating a role

Roles are created with a name and description.

**Required role(s):** Admin

### Viewing roles

Roles can be listed in full or retrieved individually by name or identifier.

**Required role(s):** Admin, Manager, General Manager

### Deleting a role

Removes the role and all its user assignments. Users who held only this role will lose the associated permissions on their next token refresh.

**Required role(s):** Admin

### Assigning permissions to a role

Individual permissions or sets of permissions can be added to a role. Only registered permissions from the platform's permission catalog may be assigned.

**Required role(s):** Admin

### Removing permissions from a role

Permissions can be removed from a role individually.

**Required role(s):** Admin

---

## Role Assignment

Role assignments link a user to a role. Assignments can have an effective date range — they can be set to expire automatically on a future date. Revoked or expired assignments are retained in history.

### Assigning a role to a user

A role is assigned to a user with an optional effective date range and optional location scope.

**Required role(s):** Admin, Manager, General Manager

### Revoking a role from a user

Removes the effective assignment. The assignment record is retained for audit purposes.

**Required role(s):** Admin, Manager, General Manager

### Viewing a user's role assignments

Returns the user's currently effective role assignments. Historical (expired or revoked) assignments can also be retrieved.

**Required role(s):** Admin, Manager, General Manager

### Viewing a user's effective permissions

Returns the full list of permissions the user currently holds across all their active role assignments.

**Required role(s):** Admin, Manager, General Manager

### Checking whether a user has a specific permission

An administrator or system component can check whether a named user holds a specific permission, optionally scoped to a location.

**Required role(s):** Admin, Manager, General Manager

---

## Permission Catalog

Each backend service registers its permissions with the security service at startup. The catalog assigns each permission a stable identifier. Permissions from all services are consolidated here into a single catalog.

### Viewing permissions

Permissions can be listed and filtered by domain (the owning service area). Individual permissions can be retrieved by their identifier.

**Required role(s):** Admin, Manager, General Manager

### Checking whether a permission exists

Administrators and integration tools can verify whether a named permission is registered in the catalog.

**Required role(s):** Admin, Manager, General Manager

---

## Authorization Decisions

For administrative tooling and programmatic access control checks, the service can evaluate whether a given user holds a specific permission and return an allow or deny decision.

**Required role(s):** Admin

---

## Audit Trail

All state-changing operations in the security service — and operations submitted by other platform services — are recorded as immutable audit events. Audit records cannot be modified or deleted after creation.

### Viewing audit events

Audit events can be searched using filter criteria including actor, event type, date range, and correlation identifier. Individual events can also be retrieved by their identifier.

**Required role(s):** Admin

### Pricing snapshots

Point-in-time pricing records are stored in the audit system for traceability purposes. They can be created and retrieved but not modified.

**Required role(s):** Admin

### Audit exports

Bulk audit data can be exported asynchronously. An export job is submitted and a reference is returned. The job status can be checked until the export is complete.

**Required role(s):** Admin

---

## Role Reference

The table below shows which platform roles grant which security-service capabilities.

| Capability | Admin | Manager / General Manager |
|---|---|---|
| View users | ✓ | |
| Create users | ✓ | |
| Edit users | ✓ | |
| Delete users | ✓ | |
| View roles | ✓ | ✓ |
| Create roles | ✓ | |
| Edit role permissions | ✓ | |
| Delete roles | ✓ | |
| Assign/revoke roles on users | ✓ | ✓ |
| View permissions | ✓ | ✓ |
| Register permissions | ✓ | |
| View account state | ✓ | |
| Manage account state (lock, unlock, enable, disable, expire) | ✓ | |
| View audit events | ✓ | |
| Create audit events | ✓ | |
| Export audit data | ✓ | |
| Evaluate authorization decisions | ✓ | |
