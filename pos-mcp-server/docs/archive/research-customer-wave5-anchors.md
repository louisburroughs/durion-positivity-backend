---
title: research-customer-wave5-anchors
module: pos-customer
issue: 1124
wave: 5
generatedAt: 2026-07-29
sourceBoundary: /home/n541342/IdeaProjects/durion-positivity-backend/pos-customer
---

## Scope

This document provides source-verified anchors for Wave 5 of issue #1124, limited to
pos-customer sources only.

Coverage requested:

- crm.party-account-model
- crm.codes
- Endpoint, permission, and event anchors for CrmAccountsController,
  CrmContactsController, CrmPartyRelationshipController
- PartyRelationshipServiceImpl behavior (effective dating, one-primary-billing,
  overlap conflict)
- PartyServiceImpl merge and alias behavior
- Token seed lists for PartyType, PartyRelationshipRole, ContactRole,
  AccountStatus, AccountTier
- Permission constants from CrmPermissionRegistry and hardcoded
  crm:relationship:* literals
- Address/contact-point representation facts where directly evident
- Declared mismatches and open ambiguities

## Verified source inventory table

| Source file | Why reviewed | Key anchors extracted | Evidence lines |
| --- | --- | --- | --- |
| src/main/java/com/positivity/customer/internal/controller/CrmAccountsController.java | CRM account/party endpoints | Paths, permissions, EmitEvent ids, parameter doc tokens | 60, 86-91, 120-126, 155-160, 214-219, 271-276, 303-308, 328-333, 362-367, 395-400, 427-432, 462-467, 498-503 |
| src/main/java/com/positivity/customer/internal/controller/CrmContactsController.java | CRM contacts endpoints | Paths, permissions, EmitEvent ids | 39, 71-76, 111-116 |
| src/main/java/com/positivity/customer/internal/controller/CrmPartyRelationshipController.java | CRM relationship endpoints | Paths, hardcoded crm:relationship:* permissions, EmitEvent ids | 57, 79-85, 128-133, 170-176, 210-216 |
| src/main/java/com/positivity/customer/internal/service/PartyRelationshipServiceImpl.java | Relationship business rules | Overlap check behavior, primary billing demotion flow, status filtering | 99-107, 116-117, 124-127, 135-137, 193-197, 261-272, 291-321 |
| src/main/java/com/positivity/customer/internal/repository/PartyRelationshipRepository.java | Dating/overlap query semantics | Active and overlap query predicates, demotion update query | 36-40, 59-63, 72-75, 86-88, 113-118, 129-133 |
| src/main/java/com/positivity/customer/internal/entity/PartyRelationship.java | Effective dating model | isActive and deactivate semantics | 97-103, 131-136, 143-145 |
| src/main/java/com/positivity/customer/internal/service/PartyServiceImpl.java | Merge/alias behavior | Merge flow, loser status MERGED, response fields actually populated | 469-523 |
| src/main/java/com/positivity/customer/internal/dto/MergePartiesResponse.java | Declared merge response contract | Declared mergeAuditId and mergedPartyAlias fields | 34, 65 |
| src/main/java/com/positivity/customer/internal/security/CrmPermissionRegistry.java | Permission constants | Available crm constants and absence of crm:relationship:* constants | 26-55 |
| src/main/resources/permissions.yaml | Declared permission catalog | Presence of crm:relationship:* literals | 65-72 |
| src/main/java/com/positivity/customer/internal/config/EventTypes.java | Event registration catalog | Registered event ids for account/relationship/contact controllers | 41-64, 77-79, 96-108 |
| src/main/java/com/positivity/customer/internal/enums/PartyType.java | Token list | PERSON, COMMERCIAL, UNKNOWN | 13-24 |
| src/main/java/com/positivity/customer/internal/enums/PartyRelationshipRole.java | Token list | APPROVER, BILLING, PRIMARY_CONTACT, DRIVER, TECHNICAL | 10-25 |
| src/main/java/com/positivity/customer/internal/entity/ContactRole.java | Token list | BILLING, PAYMENT_AUTHORIZER, OPERATIONS, PRIMARY_BUSINESS_CONTACT, TECHNICAL | 19-45 |
| src/main/java/com/positivity/customer/internal/enums/AccountStatus.java | Token list | ACTIVE, INACTIVE, ON_HOLD, MERGED | 9-20 |
| src/main/java/com/positivity/customer/internal/enums/AccountTier.java | Token list | STANDARD, BRONZE, SILVER, GOLD, PLATINUM, ENTERPRISE | 19-61 |
| src/main/java/com/positivity/customer/internal/entity/CommercialParty.java | Address representation | primaryAddress stored as String | 88-91 |
| src/main/java/com/positivity/customer/internal/service/PersonDirectoryService.java | Contact point representation from pos-people replica | Typed contact point record and EMAIL/PHONE extraction rules | 185, 188-190, 199-221, 225 |
| src/main/java/com/positivity/customer/internal/dto/CreateCommercialAccountRequest.java | Declared request token docs | partyType doc tokens | 49-53 |
| src/main/java/com/positivity/customer/internal/dto/CreateCommercialAccountResponse.java | Declared response token docs | status doc tokens | 41-44 |

## crm.party-account-model facts

### Controller endpoints, permissions, and event ids

Base paths:

- CrmAccountsController: /v1/crm/accounts
- CrmContactsController: /v1/crm/parties
- CrmPartyRelationshipController: /v1/crm/commercial-accounts/{partyId}

| Controller | Method | Path | Permission source | Permission value | EmitEvent id |
| --- | --- | --- | --- | --- | --- |
| CrmAccountsController | GET | /{accountId}/tier | CrmPermissionRegistry.PARTY_VIEW | crm:party:view | CUSTOMER_ACCOUNT_TIER_GET |
| CrmAccountsController | POST | /tierResolve | CrmPermissionRegistry.PARTY_VIEW | crm:party:view | CUSTOMER_ACCOUNT_TIER_RESOLVE |
| CrmAccountsController | POST | /parties | CrmPermissionRegistry.PARTY_CREATE | crm:party:create | CUSTOMER_PARTY_CREATE |
| CrmAccountsController | GET | /parties/{partyId} | CrmPermissionRegistry.PARTY_VIEW | crm:party:view | none |
| CrmAccountsController | GET | /parties | CrmPermissionRegistry.PARTY_VIEW | crm:party:view | CUSTOMER_PARTY_BROWSE |
| CrmAccountsController | POST | /parties/search | CrmPermissionRegistry.PARTY_VIEW | crm:party:view | CUSTOMER_PARTY_SEARCH |
| CrmAccountsController | POST | /parties:resolve | CrmPermissionRegistry.PARTY_VIEW | crm:party:view | CUSTOMER_PARTY_RESOLVE |
| CrmAccountsController | POST | /parties/{partyId}/merge | CrmPermissionRegistry.PARTY_MERGE | crm:party:merge | CUSTOMER_PARTY_MERGE |
| CrmAccountsController | GET | /parties/{partyId}/communicationPreferences | CrmPermissionRegistry.CONTACT_PREFERENCE_VIEW | crm:contact_preference:view | none |
| CrmAccountsController | POST | /parties/{partyId}/communicationPreferences | CrmPermissionRegistry.CONTACT_PREFERENCE_EDIT | crm:contact_preference:edit | CUSTOMER_COMMUNICATION_PREFERENCE_UPSERT |
| CrmAccountsController | POST | /parties/{partyId}/vehicles | CrmPermissionRegistry.VEHICLE_CREATE | crm:vehicle:create | CUSTOMER_VEHICLE_CREATE |
| CrmAccountsController | GET | /parties/duplicate-check | CrmPermissionRegistry.PARTY_SEARCH | crm:party:search | CUSTOMER_PARTY_DUPLICATE_CHECK |
| CrmAccountsController | PUT | /parties/{partyId}/billing-rules | CrmPermissionRegistry.BILLING_RULES_EDIT | crm:billing_rules:edit | CUSTOMER_BILLING_RULES_UPSERT |
| CrmContactsController | GET | /{partyId}/contacts | CrmPermissionRegistry.CONTACT_VIEW | crm:contact:view | CRM_CONTACTS_LIST |
| CrmContactsController | PUT | /{partyId}/contacts/{contactId}/roles | CrmPermissionRegistry.CONTACT_ROLE_ASSIGN | crm:contact_role:assign | CRM_CONTACT_ROLES_UPDATE |
| CrmPartyRelationshipController | POST | /relationships | hardcoded | crm:relationship:create | CRM_RELATIONSHIP_CREATE |
| CrmPartyRelationshipController | GET | /contacts | hardcoded | crm:relationship:read | CRM_ACCOUNT_CONTACTS_GET |
| CrmPartyRelationshipController | PUT | /relationships/{relationshipId}/primary-billing | hardcoded | crm:relationship:update | CRM_RELATIONSHIP_PRIMARY_BILLING_UPDATE |
| CrmPartyRelationshipController | DELETE | /relationships/{relationshipId} | hardcoded | crm:relationship:delete | CRM_RELATIONSHIP_DEACTIVATE |

### PartyRelationshipServiceImpl behavior

Effective dating and overlap behavior:

- createRelationship writes request.effectiveStartDate and request.effectiveEndDate directly to
  PartyRelationship.
- Overlap conflict checks are performed per requested role using
  findOverlappingRelationships(partyId, personPartyId, role, today).
- Overlap query predicate defines active overlap as effectiveEndDate is null or >= today.
- No check is present for requested effectiveStartDate/effectiveEndDate chronology
  (for example start after end), and no overlap calculation against requested date window;
  check is anchored to today.

One-primary-billing behavior:

- If request.isPrimaryBillingContact is true, service requires BILLING role.
- Service saves new relationship with primary flag first, then calls
  demoteExistingPrimaryBillingContact(partyId, newRelationshipId) to unset any other primary rows.
- designatePrimaryBillingContact also validates relationship belongs to party, validates
  BILLING role, requires relationship.isActive(today), sets primary=true, then demotes others.

Deactivation and active-status boundary:

- deactivateRelationship sets effectiveEndDate=today through entity method deactivate(today).
- Entity isActive(today) returns false when effectiveEndDate == today (strict isAfter check).
- Repository active queries use effectiveEndDate >= today.
- Net effect: a row ending today can still be returned by active repository queries,
  while status mapping using entity.isActive(today) will label it INACTIVE.

### PartyServiceImpl merge and alias behavior

Observed merge behavior:

- mergeParties requires request.losingPartyId and request.justification.
- Both survivor and loser must resolve as CommercialParty.
- Service reassigns all loser relationships to survivor.
- Service merges loser externalIdentifiers and vehicleVins into survivor.
- Service marks loser status as AccountStatus.MERGED.
- Service publishes partyChanged for both parties and personIdentityChanged for affected contacts.
- Response sets only survivorPartyId, losingPartyId, status=COMPLETED.

Alias/audit behavior in implementation vs DTO declaration:

- MergePartiesResponse declares mergeAuditId and mergedPartyAlias fields.
- PartyServiceImpl.mergeParties does not populate either field.
- pos-customer contains PartyAlias and MergeAudit entities/repositories, but no usage in
  PartyServiceImpl merge flow within reviewed sources.

### Address and contact-point representation facts

Address representation:

- CommercialParty stores primaryAddress as a String field (label/identifier style,
  not structured address components).

Contact points sourced from pos-people replica:

- PersonDirectoryService is backed by ext_people_contact_person replica.
- Contact points are typed records: ContactPoint(contactType, value, isPrimary).
- Email list is derived from contact points where contactType == EMAIL; falls back to primaryEmail.
- Phone list is derived from contact points where contactType starts with PHONE.
- PartyServiceImpl.resolveNames primary phone selection prefers isPrimary phone point,
  else first non-blank PHONE* point.

Service-level event facts:

- The three controllers above use @EmitEvent ids listed in the endpoint table.
- PartyRelationshipServiceImpl does not use @EmitEvent directly; it calls
  customerFactPublisher.personIdentityChanged(...) after create/deactivate/merge-side impacts.
- PartyServiceImpl.mergeParties triggers customerFactPublisher.partyChanged(...) for survivor and loser.
- PartyServiceImpl.upsertBillingRulesForParty publishes a domain event envelope using
  BillingRulesUpdatedV1.EVENT_TYPE.

## crm.codes token catalog seed lists

### Party/account/relationship tokens

- PartyType: PERSON, COMMERCIAL, UNKNOWN
- PartyRelationshipRole: APPROVER, BILLING, PRIMARY_CONTACT, DRIVER, TECHNICAL
- ContactRole: BILLING, PAYMENT_AUTHORIZER, OPERATIONS, PRIMARY_BUSINESS_CONTACT, TECHNICAL
- AccountStatus: ACTIVE, INACTIVE, ON_HOLD, MERGED
- AccountTier: STANDARD, BRONZE, SILVER, GOLD, PLATINUM, ENTERPRISE

### Permission constants from CrmPermissionRegistry relevant to these controllers

- PARTY_VIEW = crm:party:view
- PARTY_SEARCH = crm:party:search
- PARTY_CREATE = crm:party:create
- PARTY_MERGE = crm:party:merge
- CONTACT_VIEW = crm:contact:view
- CONTACT_ROLE_ASSIGN = crm:contact_role:assign
- CONTACT_PREFERENCE_VIEW = crm:contact_preference:view
- CONTACT_PREFERENCE_EDIT = crm:contact_preference:edit
- VEHICLE_CREATE = crm:vehicle:create
- BILLING_RULES_EDIT = crm:billing_rules:edit

### Hardcoded crm:relationship:* literals (not through CrmPermissionRegistry constants)

In controller annotations:

- crm:relationship:create
- crm:relationship:read
- crm:relationship:update
- crm:relationship:delete

In permissions catalog (permissions.yaml):

- crm:relationship:create
- crm:relationship:read
- crm:relationship:update
- crm:relationship:delete

## declared-but-unused/mismatch notes

1. Status token docs mismatch in browse endpoint
- CrmAccountsController browse parameter docs say ACTIVE|PENDING|SUSPENDED|INACTIVE.
- Actual AccountStatus enum is ACTIVE, INACTIVE, ON_HOLD, MERGED.
- Evidence: CrmAccountsController.java 233-235; AccountStatus.java 9-20.

2. Party type token docs mismatch in browse endpoint and create request docs
- CrmAccountsController browse parameter docs say ORGANIZATION|INDIVIDUAL.
- CreateCommercialAccountRequest partyType docs also say ORGANIZATION|INDIVIDUAL.
- Actual PartyType enum is PERSON, COMMERCIAL, UNKNOWN.
- Evidence: CrmAccountsController.java 236-237; CreateCommercialAccountRequest.java 49-53; PartyType.java 13-24.

3. Create response status docs mismatch
- CreateCommercialAccountResponse status docs declare ACTIVE|PENDING|SUSPENDED.
- Actual status values in enum are ACTIVE, INACTIVE, ON_HOLD, MERGED.
- Evidence: CreateCommercialAccountResponse.java 41-44; AccountStatus.java 9-20.

4. Effective-date boundary inconsistency
- Repository active predicates use effectiveEndDate >= today.
- Entity isActive(today) uses effectiveEndDate.isAfter(today).
- A relationship ending today can be returned by active query and then labeled INACTIVE.
- Evidence: PartyRelationshipRepository.java 39, 61, 74, 87, 131; PartyRelationship.java 131-136.

5. Event registration mismatch for contacts controller events
- CrmContactsController emits CRM_CONTACTS_LIST and CRM_CONTACT_ROLES_UPDATE.
- EventTypes registry includes CUSTOMER_CONTACT_ROLE_UPDATE and
  CUSTOMER_CONTACT_ROLE_UPDATE_LEGACY, but not CRM_CONTACTS_LIST or
  CRM_CONTACT_ROLES_UPDATE.
- Evidence: CrmContactsController.java 76, 116; EventTypes.java 50-53, 77-79.

6. Merge response contract vs implementation mismatch
- MergePartiesResponse declares mergeAuditId and mergedPartyAlias.
- PartyServiceImpl.mergeParties response builder does not set those fields.
- Evidence: MergePartiesResponse.java 34, 65; PartyServiceImpl.java 518-522.

7. Relationship permission constants gap
- CrmPermissionRegistry does not define crm:relationship:* constants.
- CrmPartyRelationshipController uses hardcoded permission literals directly.
- Evidence: CrmPermissionRegistry.java 26-55; CrmPartyRelationshipController.java 83-84, 131-132, 174-175, 214-215.

8. Additional stale/ambiguous status wording
- Party interface comments still mention SUSPENDED status token, which is not present
  in AccountStatus enum.
- Evidence: Party.java 27-29; AccountStatus.java 9-20.

## open risks/ambiguities

- Overlap conflict check is anchored to today and effectiveEndDate only. Without requested-date
  window comparison, future-dated overlap conflicts may not be prevented as intended.
- Active-status semantics are split between repository and entity logic; behavior may vary by call path
  for relationships ending on current date.
- Merge alias and merge-audit entities exist but are not wired in the reviewed merge path,
  leaving ambiguity whether redirect/audit is intentionally deferred or unintentionally missing.
- Relationship permission literals are duplicated across controller annotations and permissions.yaml,
  increasing drift risk until centralized in CrmPermissionRegistry constants.
