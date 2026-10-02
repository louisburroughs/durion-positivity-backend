# Users and Roles Guide — Accounts, Roles, Permission Grants and Sign-in Policy

## Purpose

RAG id: `admin.users-roles`  
RAG scope: `admin`  
Required permissions: `security:user:view`, `security:role:view`  
Audience: platform and security administrators, and managers who assign roles.  
This document is reference context only and grants no access; access is enforced by permission codes at request time.

This guide is the administrator's view of access on the Durion Positivity platform: how a user account differs from an
employee, how to open an account for someone ("Create a user account for Ana"), what a role is and how it carries
permissions, how roles are granted to and taken from users, how far a role reaches across locations, and the sign-in
policy. The service behind it is `pos-security-service`; the security administrator guide (`security.guide`) and the
role-permission matrix (`security.role-permission-matrix`) hold the full operation reference and the baseline grants.

**Every change described here is high risk.** Creating, editing or deleting accounts, granting or revoking roles,
changing what a role grants and changing passwords all change who can use the platform. The assistant treats such a
request as high risk (ADR-0068), confirms the exact account, role and scope before acting, and never treats a bare
"yes" as approval of a change it has not shown.

---

## User accounts, people and employees

| Record | Service | What it is |
| --- | --- | --- |
| **User account** | `pos-security-service` | A sign-in identity: username (unique within the business), password, account-state flags and roles. |
| **Person** | `pos-people-contact` | The human behind the account, and the stable identifier used in audit trails. |
| **Employee** | `pos-people` | Employment facts (employee number, status, job role) and staffing assignments. |

A user account "allows a user to authenticate to a system and potentially to receive authorization to access
resources" (see Sources [1]). It is not an HR record: a customer who registers online has an account and no employee record, and a technician who never signs
in has an employee record and no account. A username is linked to at most one person, but a person may have more
than one linked account: the link store enforces only that each username is unique, and looking up a person's links
returns a list. Only self-registration refuses a person who already has an active account. Employee records are
covered by the employee guide (`people.employees`).

---

## Opening an account for someone

To create a user account for a member of staff (say, Ana):

1. **Create the account** with a username, an initial password and at least one existing role
   (`security:user:create`). Every role named must already exist, or nothing is created (404 `ROLE_NOT_FOUND`); a
   username already in use is refused (409). Each role becomes an open-ended role assignment.
2. **Link it to Ana's person record** so her activity is attributed to her. Either request the link on the account
   (`security:user:edit`; it lands asynchronously, answer 202) or write the user–person link in `pos-people-contact`
   (`people-contact:userLink:write`). A username already linked to a different person is refused (409); linking a
second username to the same person is allowed, so check Ana's existing links first if she should have only one.
3. **If Ana works at a location**, make sure she has an `ACTIVE` employee record and a staffing assignment there
   (`people:employee:edit`). A location-scoped role reaches only the locations she is assigned to; without an
   assignment it reaches none.

Other ways an account comes to exist:

- **Self-registration** creates a customer account with the `SELF_SERVICE_CUSTOMER` role only. It is refused when an
  active account already holds the requested (or email-derived) username (`USER_ALREADY_EXISTS`), or when the matched
  person already has an active linked account (`PERSON_ALREADY_HAS_ACTIVE_USER`). An inactive account in either place
  opens an account-recovery review case (`ACCOUNT_RECOVERY_REQUIRED`), and CRM signals that need a human look open an
  identity review case (`CRM_PERSON_CONFLICT`); both answers carry the case id. A matching person with no active account
  is reused; otherwise a new person is created. The link lands asynchronously (`linkStatus: PENDING`) and no token is
  issued, so the customer signs in separately.
- **Bulk loading** creates accounts that share a starter password; each holder exchanges it for their own password
  before their first sign-in.
- **A new business's first administrator** is created by tenant provisioning on the `ADMIN` role, awaiting activation;
  a platform operator hands over a one-time activation token (valid 72 hours) out of band.

---

## Sign-in policy

- **Tenant first.** Sign-in resolves the business from the address the user signs in at (or the form's tenant slug); an
  unknown or inactive business answers exactly like a wrong password.
- **Tokens.** A successful sign-in returns an access token (one hour by default) and a refresh token (seven days by
  default). Both lifetimes are deployment settings.
- **Lockout.** Each wrong password on an existing account adds one to a failure counter, which only a successful
  sign-in, an unlock or the end of a lock resets (unknown usernames are not counted). When the counter reaches five
  (default) and the previous failure was less than ten minutes earlier, the account locks for the ten-minute window
  times the backoff multiplier (2), capped at thirty minutes: twenty minutes with the defaults. While it is locked,
  sign-in answers `ACCOUNT_LOCKED` without checking the password. The lock lifts on its own: the first attempt after it
  expires clears it and resets the counter. An administrator can unlock it sooner.
- **Refusals.** `INVALID_CREDENTIALS`, `ACCOUNT_LOCKED`, `ACCOUNT_DISABLED`, `ACCOUNT_EXPIRED` and
  `CREDENTIALS_EXPIRED` (401) name the reason; valid credentials on an account with no role at all get 403
  `USER_HAS_NO_ROLES`.

**Account state** (`security:user_account_state:view` to read, `security:user_account_state:manage` to change): an
administrator can unlock, enable, disable, expire the account, or expire the credentials to force a new password.
Disabling blocks sign-in and keeps the record; **deleting** (`security:user:delete`) removes the account and its
person link for good. Prefer disabling when the history may matter.

---

## Roles and permissions

In role-based access control, permissions are attached to roles and users receive permissions only through the roles
they are assigned, so access follows job function rather than being granted person by person (see Sources [2]). On the
platform:

- A **permission** is a code of the form `domain:resource:action` (for example `security:user:create`). Each service
  registers its own codes at start-up; the catalog of registered codes is readable with `security:permission:view`.
- A **role** is a named set of permissions. A role grants exactly what is recorded against it and nothing else; a role
  with no grants gives no access.
- A user's effective permissions are the union of the grants of every role they currently hold. A user can read their
  own; reading anyone else's needs `security:permission:view`.

**Where roles come from.** Every business starts with a copy of the platform's role template: the floor roles
(`ADMIN`, `SYSTEM_ADMINISTRATOR`, `DISPATCHER`, `SHOP_MANAGER`, `CONTROLLER`, `SELF_SERVICE_CUSTOMER` and the read-only
`SUPPORT`) plus any roles the platform operator has bulk-loaded into the template (such as `SERVICE_ADVISOR` or
`TECHNICIAN`, where loaded), each with its grants and location scope. Which roles a given business has is listed by
the role catalog (`security:role:view`). Template roles cannot be deleted
(409 `ROLE_TEMPLATE_IMMUTABLE`) and keep their names; change their grants instead. An administrator can create more
roles (`security:role:create`); a new role starts with **no** permissions, not even the assistant entry points, and
with location scope `ALL`. Permissions in the `platform:` family belong to the platform operator's own tenant and are
never granted to a business's roles.

**Roles are not job titles.** The job role on an employee's HR record ("Lead Technician") grants nothing. Only the
application roles described here grant permissions.

---

## Granting and revoking

### What a role grants (`security:role:edit`)

| Action | Effect |
| --- | --- |
| Grant one permission | Adds a code to the role. This variant registers an unknown code on the fly, so check the spelling first. |
| Add one registered permission | Adds a code that must already be registered (404 otherwise). |
| Revoke one permission | Removes a code; removing a code the role does not hold is a no-op. |
| Replace the whole set | Sets the role's grants to exactly the list given; an empty list removes everything. |

A change to what a role grants reaches its holders when their next access token is issued (next sign-in or refresh).
Only codes in the platform's permission catalog can travel in a token, so granting an uncatalogued code has no effect.

### Who holds a role (`security:role:assign`)

| Action | Effect |
| --- | --- |
| Assign a role to a user | Starts now, or within a date window; an overlapping assignment of the same role is refused (409 `ROLE_ASSIGNMENT_CONFLICT`). |
| Reconcile a user's roles by username | The list given becomes the user's complete set: missing roles are granted, others revoked. History is kept. |
| Revoke an assignment | Sets its end date (now by default) and keeps the row for history. |

Revoking a role **ends the holder's live tokens immediately**; their next token is issued without it. Adding a role is
picked up at the next sign-in or refresh. Role assignments for a person can also be made from the people screens
through `pos-people-contact` (`people-contact:role:assign`, `people-contact:role:revoke`), which forwards to this
service through the person's user link. Reading assignments needs `security:role:view` (or `people-contact:role:view`).

**Least privilege.** Grant the narrowest role that does the job, and the narrowest permission set within a role; a
system should give each user only the access their tasks need (see Sources [3]). `ADMIN` holds every domain and is the
intentional high-blast-radius role; `SYSTEM_ADMINISTRATOR` holds the security and assistant-administration surface
only and is deliberately not a superuser.

---

## How far a role reaches: location scope

A role assignment carries **no location**. How far a role's permissions reach is a property of the role (ADR-0061):

- `ALL`: the permissions apply at every location.
- `LOCATION`: the permissions apply only at the locations the holder is assigned to in `pos-people`, and the locations
  below them in the role's hierarchy (`FINANCIAL`, or `OTHER` for the non-financial parent types).

If a user holds a permission through both kinds of role, the broader one wins. A location-scoped permission with no
assigned location to resolve to reaches nowhere. A request outside the reach is refused with 403
`LOCATION_SCOPE_DENIED`. Ending or moving a staffing assignment that narrows someone's reach revokes their live tokens
straight away. Roles created through the API are `ALL`; changing a role's scope is not yet offered through the role
API. More detail, with examples, is in the locations guide (`shop.locations`).

---

## Offboarding

Closing access is a security task, separate from HR offboarding:

- Disabling an employee in `pos-people` ends their staffing assignments but **does not disable their user account**.
- Disable the account (`security:user_account_state:manage`), revoke its roles, or delete it.
- The identity-compliance report in `pos-people` (`people:compliance:view`) lists accounts still linked, actively, to
  a person who is suspended, terminated or disabled.

---

## Permissions at a glance

| Area | Read | Change |
| --- | --- | --- |
| User accounts | `security:user:view` | `security:user:create`, `security:user:edit`, `security:user:delete` |
| Account state (lock, enable, expire) | `security:user_account_state:view` | `security:user_account_state:manage` |
| Roles and their grants | `security:role:view` | `security:role:create`, `security:role:edit`, `security:role:delete` |
| Role assignments | `security:role:view` | `security:role:assign` |
| Permission catalog, others' effective permissions | `security:permission:view` | `security:permission:register` (services, at start-up) |
| A person's roles (people screens) | `people-contact:role:view` | `people-contact:role:assign`, `people-contact:role:revoke` |
| User–person links | `people-contact:userLink:view` | `people-contact:userLink:write` |

---

## Sources

Platform sources:

- `pos-security-service/src/main/java/com/positivity/securityservice/internal/controller/UserController.java`,
  `RoleController.java`, `UserRoleController.java`, `AdminAccountStateController.java`, `AuthController.java`,
  `PermissionController.java`
- `pos-security-service/src/main/java/com/positivity/securityservice/internal/enums/LocationScope.java`;
  `pos-security-service/src/main/resources/application.yml` (lockout and token lifetimes)
- `pos-security-service/src/main/java/com/positivity/securityservice/internal/service/LockoutServiceImpl.java`,
  `AuthenticationServiceImpl.java`, `SelfRegistrationServiceImpl.java`; `internal/config/LockoutPolicy.java`
- `pos-people-contact/src/main/java/com/positivity/peoplecontact/internal/entity/UserPersonLink.java` (unique
  username only); `pos-people-contact/src/test/java/com/positivity/peoplecontact/internal/service/UserPersonLinkServiceTest.java`
- `pos-security-service/src/main/resources/permissions.yaml`;
  `pos-security-service/src/main/resources/db/migration/R__seed_role_permissions.sql`,
  `R__seed_tenant_template.sql` (the role template)
- `pos-security-service/README.md` (Authorization Model, Role policy, Role location scope, Role grants vs. role
  assignments, Tenancy)
- `pos-people-contact/README.md` (Role assignments); `pos-people-contact/src/main/resources/permissions.yaml`;
  `pos-people-contact/src/main/java/com/positivity/peoplecontact/internal/controller/PersonAccessController.java`,
  `UserPersonLinkController.java`
- `pos-people/src/main/java/com/positivity/people/internal/controller/PeopleComplianceController.java`,
  `JobRoleController.java`
- `pos-mcp-server/src/main/java/com/positivity/mcp/internal/orchestration/TaggingQuestions.java` (the `risk` question)
- `durion/docs/adr/0061-location-scope-authorization-ownership.adr.md`,
  `durion/docs/adr/0062-postgres-row-level-multitenancy.adr.md`,
  `durion/docs/adr/0068-mcp-pre-llm-question-tagging-decision-model.adr.md`,
  `durion/docs/adr/0043-user-person-linkage-authority.adr.md`
- `durion/domains/people/.business-rules/AGENT_GUIDE.md` (DECISION-PEOPLE-003, -012)

External sources:

1. "User (computing)", Wikipedia, Wikimedia Foundation. <https://en.wikipedia.org/wiki/User_(computing)>
   (accessed 2026-10-02).
2. "Role-based access control", Wikipedia, Wikimedia Foundation.
   <https://en.wikipedia.org/wiki/Role-based_access_control> (accessed 2026-10-02).
3. "Principle of least privilege", Wikipedia, Wikimedia Foundation.
   <https://en.wikipedia.org/wiki/Principle_of_least_privilege> (accessed 2026-10-02).
