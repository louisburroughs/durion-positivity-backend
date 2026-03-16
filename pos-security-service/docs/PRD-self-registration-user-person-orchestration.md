---
title: "PRD: Self-Registration with User-Person Orchestration"
status: "PROPOSED"
capability: "security-self-registration"
version: "1.0"
created: "2026-03-16"
authors: ["Codex"]
modules: ["pos-security-service", "pos-people", "pos-customer"]
---

## Product Requirements Document — Self-Registration with User-Person Orchestration

**Capability ID:** SELF-REG-001  
**Module Scope:** `pos-security-service` self-registration and duplicate-account enforcement; `pos-people` person resolution, creation, and linking; `pos-customer` customer/contact identity lookup  
**Platform:** Java 21, Spring Boot 4.x, Spring Security, JPA, internal REST/event orchestration  
**Priority:** High

## Executive Summary

### Problem Statement

Durion needs a self-registration flow that creates a `User` in `pos-security-service` and finds or creates the corresponding `Person` in `pos-people` without creating duplicate identities.

This is harder than a simple "create account" flow because:

- `pos-security-service` owns authentication users.
- `pos-people` owns the canonical internal `Person` record and `UserPersonLink`.
- `pos-customer` owns CRM party and contact relationships for individual customers and commercial contacts.
- platform guidance already expects one active user per person and 1:1 `User`→`Person` behavior in v1.

The biggest failure mode is creating a second active user for a person who already exists as:

- an employee,
- a commercial account contact,
- an individual customer,
- or some combination of the above.

### Proposed Solution

Add a self-registration capability in `pos-security-service` that orchestrates a read-before-write identity flow:

1. check for existing user accounts first,
2. search for an existing person before creating a user,
3. if a person candidate exists, check whether that person is already linked to a different active user,
4. only create a new user when no active user conflict exists,
5. reuse the existing `Person` when confidence is high,
6. create a new `Person` only when no credible match exists,
7. link the new user to the person as part of the registration transaction or compensating saga.

### Success Criteria

- 100% of self-registration requests perform an existing-user check before user creation.
- 100% of successful self-registration requests resolve or create a `Person` in `pos-people`.
- 0 successful self-registration requests create a second active user for the same person.
- When a matching person already has a different active user, the API returns a deterministic conflict and does not create a new user.
- When identity matching is ambiguous, the API does not auto-create a duplicate person; it returns a review-required conflict.
- The registration flow does not infer elevated roles from employee, contact, or customer status.

## User Experience & Functionality

### User Personas

- External user creating a login for customer-facing access.
- Existing employee who may already exist as a `Person` and attempts to self-register.
- Commercial account contact who may already exist in CRM and attempts to self-register.
- Support or admin user investigating why registration was blocked.

### Core User Stories

- As a new user, I want to register once and end up with a valid user and linked person record.
- As an existing person in the system, I do not want self-registration to create a duplicate identity.
- As a person who already has an active user account, I want duplicate registration attempts to be blocked and redirected to login or recovery.
- As a platform operator, I want deterministic conflict responses that explain whether the problem is a duplicate user, duplicate person, or ambiguous match.

### Acceptance Criteria

- Self-registration is exposed through a public auth endpoint in `pos-security-service`, recommended as `POST /v1/auth/self-register`.
- The caller supplies identity attributes required for duplicate detection:
  - `email`
  - `password`
  - `firstName`
  - `lastName`
  - optional `phone`
  - optional `username`
  - optional `idpSubject` for future-proofing or external IdP onboarding
- If `username` is not supplied, the service derives it from the canonical email according to existing security domain guidance.
- The service first checks for an existing user by normalized username and normalized email-derived username before doing any create.
- The service performs person lookup before user creation using `pos-people` and, when helpful, `pos-customer`.
- If an existing person has a different active linked user, the service returns `409 Conflict` and does not create a new user.
- If only disabled, expired, or otherwise inactive linked users exist, the default behavior is still to block self-registration and route to recovery/reactivation rather than create a second account.
- If no matching person exists, the service creates a new `Person` in `pos-people`, then creates the user, then creates the link.
- A successful response returns the created `userId`, linked `personId`, and link status.

### Non-Goals

- No auto-merge of duplicate users.
- No auto-granting of employee or administrative roles during self-registration.
- No support for multiple active users per person.
- No attempt to collapse all CRM and People data models into a single physical table in this phase.
- No browser-specific password reset or account recovery flow in this PRD.

## Cross-Service Ownership

### `pos-security-service`

Owns:

- self-registration endpoint,
- user uniqueness checks,
- user creation,
- account-state checks,
- token issuance after successful registration if enabled,
- audit and security events.

### `pos-people`

Owns:

- canonical `Person`,
- person resolution,
- person creation,
- `UserPersonLink`,
- person-to-user cardinality enforcement from the people side.

### `pos-customer`

Owns:

- individual customer party records,
- commercial account contact relationships,
- party/contact search and relationship context used as lookup input during registration.

This split aligns with ADR-0011 and ADR-0015: security owns users, people owns person identity, and CRM owns external/customer/contact relationships.

## Existing Platform Constraints

- ADR-0015: a `Person` can only have one active `User` at a time.
- ADR-0022: `personId` is the stable identity claim for audit and token lineage.
- ADR-0017: conflicts and validation failures should use deterministic status codes and the standard error envelope.
- People domain guidance: default to 1:1 `User`→`Person` in v1.
- Security domain guidance: provisioning identity should prefer stable identity keys and require email-to-person match for safe linking.
- CRM domain guidance: a customer is a person-party, and a commercial contact is also a person-party related to an organization. These are roles and relationships, not proof that separate human identities exist.

## Proposed End-to-End Flow

### Recommended Endpoint

`POST /v1/auth/self-register`

Recommended request:

```json
{
  "email": "jane.smith@example.com",
  "password": "plaintext-or-secret-input",
  "firstName": "Jane",
  "lastName": "Smith",
  "phone": "+1-555-123-4567",
  "username": "jane.smith",
  "idpSubject": "optional-external-idp-subject"
}
```

Recommended success response:

```json
{
  "userId": "uuid",
  "personId": "uuid",
  "username": "jane.smith",
  "linkStatus": "LINKED",
  "matchedExistingPerson": true,
  "issuedTokens": false
}
```

### Orchestration Steps

#### 1. Normalize input

- Lowercase and trim email.
- Normalize phone if provided.
- Derive `username` from email when omitted, following existing security guidance.
- Reject malformed payloads with `400`.

#### 2. Check for existing user accounts first

This is a hard requirement.

`pos-security-service` must check for:

- exact normalized username,
- exact normalized derived username from email,
- optional `idpSubject` uniqueness if present.

Recommended outcomes:

- same active user already exists: `409 USER_ALREADY_EXISTS`
- same disabled/expired/locked user exists: `409 ACCOUNT_RECOVERY_REQUIRED`
- no user hit: continue

This check should happen inside `pos-security-service` service logic, not by exposing an insecure public enumeration endpoint.

#### 3. Search for an existing person before creating the user

This is also a hard requirement.

Use `pos-people` as the primary person-resolution authority:

- `POST /v1/people/resolve`

Use `pos-customer` as supplemental evidence when available:

- `GET /v1/crm/persons?email=...&phone=...&name=...`

Why both:

- `pos-people` decides the canonical person record for user linkage.
- `pos-customer` helps detect that the human already exists as an individual customer or commercial contact even when the security team would otherwise create a fresh person.

#### 4. Evaluate person match result

Recommended decision model:

- high-confidence single match: reuse the existing `Person`
- no credible match: create a new `Person`
- multiple or ambiguous matches: return `409 PERSON_MATCH_AMBIGUOUS`

The flow must not auto-create a new person when there is a credible ambiguous match by primary email, normalized phone, and name combination.

#### 5. If person exists, check linked users before creating new user

If a person candidate is selected:

- call `GET /v1/people/{personId}/users`
- evaluate each returned user in `pos-security-service`

Recommended rule:

- if any linked user is active and has a different `userId`, block with `409 PERSON_ALREADY_HAS_ACTIVE_USER`
- if linked users exist but none are active, still block self-registration by default with `409 ACCOUNT_RECOVERY_REQUIRED`

This keeps self-registration from becoming a duplicate-account generator and stays aligned with ADR-0015.

#### 6. Create or reuse person

- If matched existing person: reuse it.
- If no match: create new person in `pos-people`, recommended via `POST /v1/people`.

Recommended minimum person creation data:

- `firstName`
- `lastName`
- canonical primary email
- optional primary phone

#### 7. Create the user in `pos-security-service`

After the person decision is complete:

- create user with no elevated roles,
- populate `personId` in the security user record if supported,
- default to the lowest-privilege self-service role or no role at all depending on policy.

Caller-supplied roles must not be accepted in self-registration.

#### 8. Link user to person

Call one of:

- `POST /v1/people/users/link`
- or `POST /v1/people/user-links`

Recommended result:

- success: return created registration response
- link conflict: compensate by disabling or deleting the just-created user and return deterministic failure

#### 9. Post-registration authentication behavior

Self-registration should not issue tokens immediately.

Recommended behavior:

- create the person if needed,
- create the user only after person resolution or creation succeeds,
- create the user-person link,
- return registration success without tokens,
- require the caller to perform a follow-up login.

## Recommended API and Contract Changes

### `pos-security-service`

Add:

- `POST /v1/auth/self-register`

Recommended request DTO:

- `email`
- `password`
- `firstName`
- `lastName`
- optional `phone`
- optional `username`
- optional `idpSubject`

Recommended response DTO:

- `userId`
- `personId`
- `username`
- `linkStatus`
- `matchedExistingPerson`
- `crmMatchSummary`

Recommended internal additions:

- service-level lookup by normalized username
- service-level lookup by `idpSubject` when present
- service-level account-state read used during person-linked-user checks

### `pos-people`

Existing operations are close, but the PRD recommends:

- using `POST /v1/people/resolve` as the first person lookup,
- using `GET /v1/people/{personId}/users` to enforce linked-user checks,
- using `POST /v1/people/users/link` for final linkage.

Recommended enhancement:

- enrich `ResolvePersonResponse` with machine-friendly match classification such as `EXACT`, `HIGH_CONFIDENCE`, `AMBIGUOUS`, `CREATED`.

### `pos-customer`

Existing `GET /v1/crm/persons` search is sufficient for the current phase.

Recommended current approach:

- use `searchPersons` as-is for self-registration support,
- interpret its results as supplemental evidence during person resolution,
- defer any dedicated identity-summary endpoint until experience shows the current search response is insufficient.

Recommended future enhancement if needed:

- enrich person search results to indicate whether the CRM person is:
  - an individual customer,
  - a commercial contact,
  - both.

At minimum, the registration orchestrator needs a CRM identity summary, not just raw person search rows.

Recommended shape:

```json
{
  "personId": "uuid",
  "displayName": "Jane Smith",
  "primaryEmail": "jane.smith@example.com",
  "isIndividualCustomer": true,
  "isCommercialContact": true,
  "commercialAccountCount": 2
}
```

## Decision Rules

### Duplicate User Rules

- Username duplicate: block.
- Email-derived username duplicate: block.
- `idpSubject` duplicate: block.
- Existing linked active user for matched person: block.
- Existing linked inactive user for matched person: block and route to recovery by default.

### Person Resolution Rules

- Exact email match to a single person with supporting name or phone match: auto-reuse allowed.
- Phone+name match without email: only reuse when the score clears threshold and no ambiguity exists.
- Multiple people with the same or similar evidence: return manual-review conflict.
- CRM hit without a confident people match: do not auto-create a fresh person without recording the ambiguity; return conflict or hold for review.

### Linking Rules

- One active user per person is mandatory.
- Self-registration must not create an unlinked active user.
- If linking fails after user creation, the system must compensate before returning success.

### Role Rules

- Self-registration must never infer employee or commercial account permissions.
- If a role is assigned automatically, it must be a dedicated low-privilege self-service role.
- Employee, admin, location-scoped, or commercial account workflow roles remain separate approval flows.

## Recommendation for Shared-Identity Cases

### Recommended Model

For a human who is any combination of:

- employee,
- commercial party contact,
- individual customer,

the platform should treat that human as:

- one canonical `Person` in `pos-people`,
- one active `User` in `pos-security-service`,
- one or more CRM party relationships in `pos-customer`.

In other words:

- employee status belongs to People,
- customer/contact relationships belong to CRM,
- login identity belongs to Security,
- but they should all point to the same human identity.

### Strong Recommendation

Do **not** create separate people or separate active users for:

- "employee Jane Smith"
- "commercial contact Jane Smith"
- "customer Jane Smith"

when they are the same person.

Instead:

- reuse the same `Person`,
- keep one active `User`,
- let CRM model the multiple external relationships on that same person-party.

### Why This Recommendation Fits The Current Platform

- ADR-0015 explicitly allows only one active user per person.
- People guidance defaults to 1:1 `User`→`Person` in v1.
- CRM guidance already models contacts and customers as person-party relationships, not separate security identities.
- ADR-0022 requires stable `personId` lineage for audit and tokens.

### Operational Rule For Self-Registration

If the matched person is already known as an employee or commercial contact and already has an active user:

- reject self-registration,
- return a conflict code,
- direct the person to login, recovery, or support,
- do not create a second self-service account.

If the matched person is an employee or commercial contact but has no active user:

- allow creation of one low-privilege user,
- link it to the existing person,
- do not auto-assign staff or commercial workflow permissions.

Recommended default role:

- create and assign a dedicated low-privilege external customer role for self-registration,
- keep this role intentionally narrow because additional external customer roles are expected in future phases.

## Error Handling

Recommended status mapping per ADR-0017:

- `201 Created`: user, person, and link successfully created
- `200 OK`: existing idempotent registration outcome returned without mutation, if idempotent retries are implemented
- `400 Bad Request`: invalid payload, invalid email, missing required fields
- `401 Unauthorized`: not generally applicable for anonymous self-registration, but may apply to follow-on token flows
- `403 Forbidden`: registration policy disallows the request
- `404 Not Found`: downstream person referenced during linkage no longer exists
- `409 Conflict`: duplicate user, person already has active user, ambiguous person match, email mismatch, link conflict
- `422 Unprocessable Entity`: semantically valid request violates explicit domain policy if that policy is not better expressed as conflict
- `500 Internal Server Error`: unexpected orchestration failure

Recommended error codes:

- `USER_ALREADY_EXISTS`
- `ACCOUNT_RECOVERY_REQUIRED`
- `PERSON_ALREADY_HAS_ACTIVE_USER`
- `PERSON_MATCH_AMBIGUOUS`
- `EMAIL_MISMATCH`
- `USER_PERSON_LINK_CONFLICT`
- `CRM_PERSON_CONFLICT`

## Security, Audit, and Observability

- Self-registration endpoint must be `permitAll`.
- Internal service-to-service calls from security to people/customer must use trusted internal auth.
- Correlation IDs must be propagated end-to-end.
- Audit events should record:
  - attempted email,
  - chosen or created `personId`,
  - created `userId`,
  - duplicate or ambiguity outcome,
  - correlation ID.
- Sensitive values such as raw passwords must never be logged.

Recommended emitted events:

- `SECURITY_SELF_REGISTRATION_ATTEMPT`
- `SECURITY_SELF_REGISTRATION_CREATED`
- `SECURITY_SELF_REGISTRATION_BLOCKED_DUPLICATE`
- `SECURITY_SELF_REGISTRATION_BLOCKED_AMBIGUOUS_PERSON`

## Testing Requirements

### Unit

- username normalization and derivation
- duplicate-user detection
- person resolution decision matrix
- active-user conflict evaluation
- compensation behavior when link creation fails

### Integration

- `pos-security-service` self-registers against real `pos-people` stubs or test containers
- person exists with active different user -> blocked
- person exists with inactive linked user -> blocked and recovery path surfaced
- no person exists -> person created, user created, link created
- CRM suggests possible match but people result ambiguous -> blocked

### Contract

- OpenAPI contract for the new self-registration endpoint
- people resolve and linking responses used by the orchestration
- CRM person search result shape for identity summary if enhanced

## Rollout Plan

### Phase 1

- Add `POST /v1/auth/self-register`
- implement security-side duplicate checks
- integrate with `pos-people` resolve, create, and link APIs
- block on any detected active-user conflict

### Phase 2

- integrate `pos-customer` lookup for richer duplicate detection
- add CRM identity summary to person search
- add support/admin guidance for blocked registration cases

### Phase 3

- add idempotent retry support
- add account recovery integration for disabled or legacy linked accounts
- add explicit review queue for ambiguous human identity matches if needed

## Resolved Decisions

- Self-registration requires a follow-up login and does not issue tokens immediately on success.
- The flow creates the user only after person resolution or person creation succeeds.
- Self-registration should assign a dedicated low-privilege role for external customers, with room for additional external customer roles later.
- `searchPersons` in `pos-customer` is sufficient for now; a dedicated identity-summary endpoint can be deferred unless implementation experience shows a gap.

## Final Recommendation

Build self-registration as a security-owned orchestration flow with hard duplicate prevention:

- check users first,
- search people before creating user,
- block if the person already has a different active user,
- reuse one canonical person across employee, commercial contact, and individual customer roles,
- and never let self-registration create a second active login for the same human.

That gives Durion the cleanest long-term identity model across `pos-security-service`, `pos-people`, and `pos-customer` while staying aligned with current ADRs and domain rules.
