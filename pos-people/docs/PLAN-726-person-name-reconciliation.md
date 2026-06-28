# PLAN-726 — Reconcile Person name model to a single authoritative representation

**Issue:** [#726](https://github.com/louisburroughs/durion-positivity-backend/issues/726) (tech-debt)
**ADR:** [ADR-0015](../../docs/adr/0015-identity-entity-relationships.adr.md) — invariant **I2**: Person name & contact attributes owned by `pos-people`.
**Predecessor:** PR #725 (stop-gap), people-collapse-2026-06 re-baseline (contact half).

---

## Scope correction vs. the issue text

The issue was filed **before** the `people-collapse-2026-06` re-baseline. The **contact** half is already reconciled:

- `contact_info_json` is no longer a stored column — `Person.contactInfoJson` is a read-only Hibernate `@Formula` over `person_contact_point` (EMAIL / PHONE_WORK).
- Contact writes go through `person_contact_point` via `PersonWorkPhoneService` / `PersonEmailService` — the typed/authoritative model per ADR-0015.

So #726 narrows to two things:

1. **Collapse the name duplication** — `first_name`/`last_name` **and** `legal_name`/`preferred_name` are both stored; the first/last split is a heuristic.
2. **Remove the now-vestigial stop-gaps** from PR #725 and the contact-collapse interim.

### Canonical decision

**Structured `firstName` / `lastName` + `preferredName` is canonical. `legal_name` is dropped.**

Rationale: the broad read surface already reads/queries structured first/last —
directory (`PersonServiceImpl`), name-search typeahead
(`findByFirstNameIgnoreCase` / `findByLastNameIgnoreCase`), reports,
availability, timekeeping-approval, user-person-link (6+ readers).
`legal_name`/`preferred_name` is read only by the Employee profile path.

**Accepted trade-off:** free-form full legal name (middle names, suffixes such
as "Robert James Smith Jr.") is no longer stored distinctly. Flag for HR/payroll
if legal-document matching is needed later.

---

## Current state (evidence)

| Concern | State now | File |
| --- | --- | --- |
| `contactInfoJson` | Derived `@Formula`, not stored | `entity/Person.java:54` |
| Contact write | Authoritative via `person_contact_point` | `EmployeeServiceImpl.replaceContactPoints` |
| Name | **Stored twice**: `first_name`/`last_name` + `legal_name`/`preferred_name` | `entity/Person.java:39-47` |

Live stop-gaps to remove (`EmployeeServiceImpl`):

- `applyStructuredName` — heuristic split `legalName` → first/last on write (`:245`)
- `resolveLegalName` — compose first/last when `legalName` blank (`:337`)
- `resolveContactInfo` / `readContactInfo` / `contactInfoFromColumns` — vestigial blob-parse duality now that the blob is derived (`:353-391`)
- `Person.contactInfoJson` `@Formula` field itself (`:54`)

---

## Phase 1 — Backend service + entity

1. **`entity/Person.java`** — drop `legalName` field; drop `contactInfoJson` `@Formula` field. Keep `firstName` / `lastName` / `preferredName`.
2. **`service/EmployeeServiceImpl.java`**
   - Delete `applyStructuredName`, `resolveLegalName`, `resolveContactInfo`, `readContactInfo`, `contactInfoFromColumns`, and the `ObjectMapper` dependency.
   - `applyIdentity` sets `firstName` / `lastName` / `preferredName` directly from the request.
   - `toEmployeeProfile` reads `firstName` / `lastName` / `preferredName`; contact read becomes the single email/phone-service path.
   - Duplicate detection: replace `hasAmbiguousLegalNameMatch` + `findByLegalNameIgnoreCase` with first/last matching (`findByFirstNameIgnoreCase` / `findByLastNameIgnoreCase`).
3. **`repository/PersonRepository.java`** — remove `findByLegalNameIgnoreCase`.
4. **`service/PeopleReportsServiceImpl.java`, `service/TimekeepingApprovalServiceImpl.java`** — switch any `legalName` read → `firstName`/`lastName` composition.

## Phase 2 — DTOs + contract chain (mandatory: controller → OpenAPI → SDK → frontend)

5. **`dto/CreateEmployeeRequest.java` / `dto/UpdateEmployeeRequest.java`** — `legalName`(`@NotBlank`)/`preferredName` → `firstName`(`@NotBlank`)/`lastName`/`preferredName`.
6. **`dto/EmployeeProfileDto.java`** — `legalName` → `firstName`/`lastName`.
7. **`dto/PersonBulkIngestRecord.java` + `controller/PersonBulkIngestController.java`** — `legalName` input → `firstName`/`lastName`.
8. **OpenAPI annotations** on `EmployeeController` + bulk controller → regenerate `OpenAPI.yaml` → update **Angular SDK** → update frontend employee form + profile components (legalName field → first/last).

## Phase 3 — Migration + seed

9. New `V2__drop_legal_name.sql` — one-time backfill for any row with null `first_name` but present `legal_name` (reuse archived `V7__backfill_person_first_last_from_legal_name.sql` split), then `alter table person drop column legal_name`.
10. **`R__seed_reference_people.sql`** — drop `legal_name`, populate `first_name`/`last_name`.

## Phase 4 — Tests

11. Update `EmployeeProfileContractBehaviorIT`, `EmployeeOffboardingContractBehaviorIT`, `PersonBulkIngestControllerTest`.
12. **Delete `EmployeeProfileFallbackTest`** (tests the removed stop-gap); add a single-model assertion if it leaves a coverage gap.

---

## Done criteria

- `legal_name` column gone; `Person` stores name once (`first_name`/`last_name`/`preferred_name`).
- No `contactInfoJson` field; contact read/write only via `person_contact_point`.
- No fallback/sync stop-gaps in `EmployeeServiceImpl`.
- Contract chain in sync: controller annotations ↔ `OpenAPI.yaml` ↔ Angular SDK ↔ frontend.
- All pos-people tests green.
