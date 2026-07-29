---
rag_id: crm.party-account-model
rag_scope: customer
required_permissions:
  - crm:party:view
---

## Purpose

RAG id: crm.party-account-model
RAG scope: customer
Required permissions: crm:party:view
Audience: internal staff.

This document describes customer party/account and relationship behavior implemented in pos-customer.

## Endpoint Surfaces

- Account and party APIs: /v1/crm/accounts
- Contact APIs: /v1/crm/parties
- Relationship APIs: /v1/crm/commercial-accounts/{partyId}

## Relationship Behavior Facts

- Relationship rows are effective-dated with start and optional end dates.
- Overlap conflict check is performed against active overlaps for the same party, person, and role.
- Primary billing designation requires BILLING role.
- When a new primary billing contact is set, existing primary billing rows for the same party are demoted.

## Party Merge Facts

- Merge reassigns loser relationships to survivor.
- Merge combines external identifiers and vehicle VIN sets.
- Losing party status is set to MERGED.

## Token Catalog

PartyType:
- PERSON
- COMMERCIAL
- UNKNOWN

PartyRelationshipRole:
- APPROVER
- BILLING
- PRIMARY_CONTACT
- DRIVER
- TECHNICAL

ContactRole:
- BILLING
- PAYMENT_AUTHORIZER
- OPERATIONS
- PRIMARY_BUSINESS_CONTACT
- TECHNICAL

AccountStatus:
- ACTIVE
- INACTIVE
- ON_HOLD
- MERGED

AccountTier:
- STANDARD
- BRONZE
- SILVER
- GOLD
- PLATINUM
- ENTERPRISE

## Address and Contact Representation

- Commercial party primaryAddress is stored as a String.
- Contact points are sourced via person-directory integration with typed contact channels.

## Verified Facts

- _Verified: pos-customer CrmAccountsController, CrmContactsController, and CrmPartyRelationshipController endpoint mappings, permission guards, and @EmitEvent ids._
- _Verified: pos-customer PartyRelationshipServiceImpl overlap checks, primary billing demotion, and active-status mapping behavior._
- _Verified: pos-customer PartyServiceImpl merge flow sets losing account status to MERGED and reassigns relationships._
- _Verified: pos-customer enums PartyType, PartyRelationshipRole, ContactRole, AccountStatus, and AccountTier._
