## People Backend Contract Guide

**Version:** 1.0  
**Audience:** Backend developers and API consumers  
**Last Updated:** 2026-02-16  
**OpenAPI Source:** `pos-people/docs/openapi.json`

## Overview

The `pos-people` service provides people domain APIs, including person management and access control integration with `pos-security-service`.

Person access control APIs are exposed under `/v1/people/{personUuid}/access/...` and delegate role operations to `pos-security-service` after translating `personUuid` to `userId` using person-user link data.

## Person Access Control Endpoints

### GET /v1/people/{personUuid}/access/roles

- Purpose: List available roles for people access assignment.
- Path parameter:
  - `personUuid` (UUID): Person identifier in `pos-people`.
- Behavior:
  - Returns combined role lists for scope types `LOCATION` and `GLOBAL`.
- Response:
  - `200 OK` with `List<RoleDto>`.

`RoleDto` fields:

- `code` (String)
- `name` (String)
- `description` (String)
- `scopeType` (String)
- `active` (Boolean)

### GET /v1/people/{personUuid}/access/assignments

- Purpose: List role assignments for the person's linked user account.
- Path parameter:
  - `personUuid` (UUID)
- Query parameters:
  - `includeHistory` (Boolean, optional)
  - `endDate` (LocalDateTime, optional, ISO date-time)
- Response:
  - `200 OK` with `List<UserRoleDto>`.

`UserRoleDto` fields:

- `userId` (String)
- `roleCode` (String)
- `locationId` (UUID)
- `startDate` (LocalDateTime)
- `endDate` (LocalDateTime)
- `active` (Boolean)

### POST /v1/people/{personUuid}/access/assignments

- Purpose: Assign a role to the linked user for a person.
- Path parameter:
  - `personUuid` (UUID)
- Request body: `PersonRoleAssignmentRequest`
  - `roleCode` (String, required)
  - `locationId` (UUID, optional)
  - `startDate` (LocalDateTime, optional)
  - `endDate` (LocalDateTime, optional)
- Response:
  - `201 Created` with `UserRoleDto`.

### DELETE /v1/people/{personUuid}/access/assignments/{roleCode}

- Purpose: Revoke role assignment for the linked user.
- Path parameters:
  - `personUuid` (UUID)
  - `roleCode` (String)
- Query parameter:
  - `endDate` (LocalDateTime, optional, ISO date-time)
- Response:
  - `204 No Content`.

## Integration Flow

`pos-people` does not store security roles directly. It resolves person identity to security identity and proxies role operations to `pos-security-service`.

1. Endpoint receives `personUuid`.
2. `PeopleAccessControlServiceImpl` resolves `userId` through `UserPersonTranslationService`.
3. `UserPersonTranslationServiceImpl` looks up `UserPersonLinkRepository.findByPersonId(personUuid)` and selects the first link.
4. If no link exists, the service throws `EntityNotFoundException` (`No user link found for personUuid: ...`).
5. For successful translation, `SecurityServiceClient` calls `pos-security-service` role APIs.

## Service Client Details

`SecurityServiceClient` is a typed HTTP adapter around Spring `RestClient`.

- Bean wiring:
  - Uses `@Qualifier("securityServiceRestClient")`.
  - Configured in `RestClientConfig` with timeouts:
    - connect timeout: `pos.restclient.connect.timeout` (default `3000ms`)
    - read timeout: `pos.restclient.read.timeout` (default `5000ms`)
  - Base URL property: `pos.security-service.base-url`
    - `application.yml` default: `http://pos-security-service:8086`
    - Bean fallback default: `http://localhost:8084`

- Outbound endpoints:
  - `GET /v1/roles?scopeType={LOCATION|GLOBAL}`
  - `GET /v1/user-roles?userId=...&includeHistory=...&endDate=...`
  - `POST /v1/user-roles`
  - `DELETE /v1/user-roles/{userId}/{roleCode}?endDate=...`

- Error mapping behavior:
  - `400` → `IllegalArgumentException`
  - `404` → `EntityNotFoundException`
  - `5xx` → `IllegalStateException`

## Notes

- Access control endpoints emit events using `@EmitEvent`:
  - `PEOPLE_ACCESS_ROLES_LIST`
  - `PEOPLE_ACCESS_ASSIGNMENTS_LIST`
  - `PEOPLE_ACCESS_ASSIGNMENT_CREATE`
  - `PEOPLE_ACCESS_ASSIGNMENT_REVOKE`

- The endpoint path includes `personUuid` for domain consistency, while role authority is managed in `pos-security-service` with `userId` as the target identity.
