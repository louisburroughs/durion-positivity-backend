# Customer and Vehicle Guide

## Purpose
RAG id: `crm.customer-vehicle`  
RAG scope: `customer`  
Required permissions: `crm:party:view`, `crm:party:search`, `crm:vehicle:view`, `crm:vehicle:search`, `crm:contact:view`  
Audience: internal staff.  
This document is reference context only and grants no access; access is enforced by permission codes at request time.

This guide grounds customer, party, contact, and vehicle questions. It is internal reference context for staff and admins, not customer-facing material.

## Core concepts
A party is the CRM identity for a person, company, fleet, vendor, or other business participant. A customer is a party in a buying or service relationship. A contact record stores how to reach the party. A vehicle record stores the serviced asset and may include VIN, unit number, make/model, mileage, service history, account relationship, and workorder linkage if available.

The verified CRM/customer/vehicle permissions are `crm:party:view`, `crm:party:search`, `crm:vehicle:view`, `crm:vehicle:search`, and `crm:contact:view`. Use search permissions for lookup-style questions and view permissions for record-detail questions.

## Staff questions and answer patterns
| Question | Interpretation |
|---|---|
| "Find this customer." | Search party/customer records by name, account, phone, email, or known identifier. |
| "Which vehicles are on this account?" | List vehicles connected to the visible party/account context. |
| "Show the service history for this VIN." | Use vehicle identifier and workorder/service history only if visible. |
| "Who should we contact for this fleet?" | Return contact records and role/context if visible. |
| "Is this the same customer?" | Compare identifiers and advise data cleanup; do not merge without verified authority. |

## Vehicle identifiers
VIN is a key exact identifier but the bundle does not verify validation rules or canonical storage. Staff may also use unit number, plate, fleet asset number, customer vehicle number, or workorder history. The assistant should ask a clarifying question when a vehicle reference is ambiguous and should not validate VIN structure without source grounding.

## Customer and workorder hand-off
Customer and vehicle data are prerequisites for service operations. An appointment links a customer, vehicle, time slot, and shop resource. An estimate uses customer and vehicle context before approval. A workorder tracks the vehicle being serviced after estimate approval. Invoice, warranty, claim, and service-history questions often depend on the same customer/vehicle identifiers.

## Privacy and access handling
The assistant should return only the data visible to the caller's permissions. It should avoid exposing contact details unless the user has the required context and permission. It should not infer identity from partial identifiers when multiple matches exist.

## Error and exception patterns
Common CRM issues include duplicate party records, stale contact data, vehicle assigned to wrong account, missing VIN/unit number, mismatched account and invoice, ambiguous fleet hierarchy, and customer/vehicle references that do not match the workorder.

## Verified facts (pos-customer / pos-vehicle-inventory)
- **Party taxonomy** (`PartyType`): `PERSON`, `COMMERCIAL`, `UNKNOWN`. JPA: abstract `AbstractParty` (table-per-class) with `PersonParty` and `CommercialParty`. Every party has a unique `customerNumber`, plus `status` (default ACTIVE) and `tier` (default STANDARD).
- **Fleet / commercial hierarchy:** modeled on `CommercialParty` via self-referencing `parentParty` / `childParties`. Person↔org links use `PartyRelationship` with roles `APPROVER, BILLING, PRIMARY_CONTACT, DRIVER, TECHNICAL`; exactly one primary billing contact per account. "Fleet" is not a party type — it is a CommercialParty with DRIVER-role relationships and fleet vehicles.
- **Merge/dedup:** `crm:party:merge` → `POST /parties/{partyId}/merge` (CommercialParty only). Loser is soft-marked `AccountStatus.MERGED` (not deleted); relationships/external-ids/vehicle VINs reassigned to the survivor; `justification` required.
- **Vehicle (system of record = `pos-vehicle-inventory` `VehicleRecord`):** `vehicleId` (UUID), `accountId`, `vin`/`vinNormalized` (unique), `unitNumber`, `description`, `licensePlate`(+jurisdiction), `year/make/model/trim`, `odometer` (JSONB), `lastServiceDate`, `isActive`. CRM keeps only a denormalized `vehicleVins` set.
- **Service history:** there is NO consolidated service-history API/entity. Per-vehicle work history is retrieved from `pos-workorder` (estimates by `vehicleId`); `VehicleRecord.lastServiceDate` is the only last-service field. Do not claim a unified service-history endpoint.

Verified operations (path → tool name). Customer (`pos-customer`, domain `crm`): getCustomerById → `crm_getcustomerbyid`, getAllCustomers → `crm_getallcustomers`, createCustomer → `crm_createcustomer`, listVehiclesForCustomer → `crm_listvehiclesforcustomer`, searchPersons → `crm_searchpersons`. Vehicle (`pos-vehicle-inventory`): paths span `/v1/vehicles`, `/v1/vehicles-legacy`, `/v1/vehicle-registry` → e.g. getVehicleByVIN → `vehicles-legacy_getvehiclebyvin`, getPreferences → `vehicles_getpreferences`. (Note: vehicle openapi has duplicate operationIds like `getVehicle`/`getVehicle_1` across path groups.)
