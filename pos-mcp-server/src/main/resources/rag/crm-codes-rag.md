---
rag_id: crm.codes
rag_scope: customer
required_permissions:
  - crm:party:view
---

## Purpose

RAG id: crm.codes
RAG scope: customer
Required permissions: crm:party:view
Audience: internal staff.

Token catalog for CRM/customer lexical retrieval.

## Enum Tokens

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

## Permission Tokens

From CrmPermissionRegistry:
- crm:party:view
- crm:party:search
- crm:party:create
- crm:party:merge
- crm:contact:view
- crm:contact_role:assign
- crm:contact_preference:view
- crm:contact_preference:edit
- crm:vehicle:create
- crm:billing_rules:edit

Hardcoded relationship literals:
- crm:relationship:create
- crm:relationship:read
- crm:relationship:update
- crm:relationship:delete

## Event Tokens

- CUSTOMER_ACCOUNT_TIER_GET
- CUSTOMER_ACCOUNT_TIER_RESOLVE
- CUSTOMER_PARTY_CREATE
- CUSTOMER_PARTY_BROWSE
- CUSTOMER_PARTY_SEARCH
- CUSTOMER_PARTY_RESOLVE
- CUSTOMER_PARTY_MERGE
- CUSTOMER_COMMUNICATION_PREFERENCE_UPSERT
- CUSTOMER_VEHICLE_CREATE
- CUSTOMER_PARTY_DUPLICATE_CHECK
- CUSTOMER_BILLING_RULES_UPSERT
- CRM_CONTACTS_LIST
- CRM_CONTACT_ROLES_UPDATE
- CRM_RELATIONSHIP_CREATE
- CRM_ACCOUNT_CONTACTS_GET
- CRM_RELATIONSHIP_PRIMARY_BILLING_UPDATE
- CRM_RELATIONSHIP_DEACTIVATE

## Verified Facts

- _Verified: pos-customer enum and permission tokens from internal enums, CrmPermissionRegistry, and permissions.yaml._
- _Verified: pos-customer controller @EmitEvent ids for account, contact, and relationship surfaces._
