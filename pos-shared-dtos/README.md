# pos-shared-dtos

Shared DTO library for cross-module data transfer across the Durion POS platform. Contains invoice request/response types, vehicle request/response types, shared enumerations, error envelope, and UUIDv7 ID generation utilities. This is a library dependency, not a deployable service.

## Responsibilities

- Provide `InvoiceGenerationRequest`, `InvoiceCreationRequest`, and `InvoiceGenerationResponse` DTOs for the workorder-to-invoice handoff
- Provide `CreateVehicleRequest`, `UpdateVehicleRequest`, and `VehicleResponse` DTOs for shared vehicle data
- Expose `InvoiceGroupingStrategy` and `InvoiceDeliveryMethod` enumerations
- Supply `ApiError` error envelope record used consistently across service exception handlers
- Provide `UUIDv7Generator` and `UUIDv7HibernateGenerator` for time-ordered UUID primary keys

## Key Classes

- `InvoiceGenerationRequest` — request DTO sent from `pos-workorder` to `pos-invoice` to create an invoice
- `InvoiceLineItem` — individual line item within an invoice generation request
- `ApiError` — standard error envelope (`timestamp`, `status`, `code`, `message`, `path`)
- `UUIDv7Generator` — generates time-ordered UUIDv7 identifiers
- `UUIDv7HibernateGenerator` — Hibernate `IdentifierGenerator` integration for UUIDv7 PKs

## Dependencies

No internal `pos-*` module dependencies. Depends on Lombok, JSpecify, Jackson annotations, and Jakarta Validation.

This module is a library dependency — there is no runnable service to start.
