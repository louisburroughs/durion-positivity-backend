# pos-people-contact

Identity, contact, and user-link authority service for the Durion Positivity platform
(ADR-0044 §6 Phase 3, issue #874). Split out of pos-people, which retains the HR domain
(employees, timekeeping, availability, staffing).

## Responsibilities

- Own `Person`, `PersonContactPoint`, and `UserPersonLink` records (ADR-0015)
- Person directory search (name / email) and weighted person resolution
- Typed contact-point management (email, phone) — source of truth for contacts
- User ↔ person link management (keyed by username; ADR-0043)
- Person → role assignment proxying to pos-security-service (utility sync call,
  allowed by the ADR-0044 whitelist)

## Eventing (ADR-0044)

- Publishes identity facts to `people-contact.events.v1` via a transactional outbox:
  `people-contact.person.updated`, `people-contact.person.deleted`,
  `people-contact.user-person-link.updated`, `people-contact.user-person-link.removed`
- Publishes reconciliation manifests to `people-contact.manifest.v1`
- Consumes `people-contact.commands.v1` (`people-contact.outbox.replay-requested`
  for replica bootstrap / drift repair)
- Feature flag: `pos.people-contact.kafka.enabled` (`POS_PEOPLE_CONTACT_KAFKA_ENABLED`)

## Permissions

`people-contact:person:{view,create,edit,delete}`, `people-contact:role:{view,assign,revoke}`,
`people-contact:userLink:{view,write}` — registered with pos-security-service at startup.

## Notes

- Gateway route: `/people-contact/**` → `lb://PEOPLE-CONTACT`
- Employment data (employee status, assignments) lives in pos-people; this service's
  directory search has no employment filters.
- Consumers needing person reference data maintain `ext_people_contact_person` replicas
  fed from `people-contact.events.v1` (Phases 3.2–3.4: #875, #876, #877).
