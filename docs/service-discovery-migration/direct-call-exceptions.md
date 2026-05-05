---
title: Service Discovery Migration Direct Call Exceptions
wave: 0
branch: cap/641-service-discovery-migration
status: draft
---

## Purpose

This register captures all non-gateway classifications from the Wave 0 inventory:

- direct-exception
- startup-infra
- external
- tax-exemption

These entries should not be migrated using the default gateway-routed policy without an explicit decision.

## Exception Register

| Client File | Category | Reason / ADR Reference | Required URL Format Going Forward |
| --- | --- | --- | --- |
| EventTypeInitializer (all modules listed in matrix) | startup-infra | Startup registration should not depend on Eureka readiness. ADR-0014 and startup operational constraints. | Direct Docker DNS hostname for event receiver/events endpoint; no localhost defaults. |
| PermissionRegistration / PermissionInitializer (all modules listed in matrix) | startup-infra | Permission bootstrap must be reliable during startup before full discovery convergence. ADR-0011 security ownership and startup operational constraints. | Direct Docker DNS hostname for security-service endpoint; no localhost defaults. |
| DocumentTemplateInitializerSupport | startup-infra | Template registration helper is startup bootstrap behavior for documents. | Direct Docker DNS hostname for documents registration endpoint during startup. |
| EventTypeInitializer (pos-workorder) | startup-infra | Currently goes through gateway URL; startup policy prefers direct bootstrap path to avoid gateway/discovery ordering risk. | Direct Docker DNS event receiver endpoint (not localhost, avoid gateway dependency). |
| ExternalAvailabilityClientImpl | direct-exception | Calls non-gateway internal availability endpoint contract (`/positivity/v1/availability/external`) not represented in gateway route inventory. | Keep direct URL to owning internal provider until explicit gateway contract is added. |
| ProductSubstituteClientImpl | direct-exception | Calls non-gateway substitute resolution contract (`/product/v1/substitutes:resolve`) not represented in gateway route inventory. | Keep direct URL to owning internal provider until explicit gateway contract is added. |
| VehicleReferenceService (pos-workorder -> pos-vehicle internal service) | direct-exception | No `/vehicle/**` gateway route exists in the gateway route catalog. Internal pos-vehicle service direct call preserved until gateway route is added. | Direct Docker DNS hostname (`http://pos-vehicle:8088`) until gateway route is available. |
| SourceDocumentStubClient | direct-exception | Stub-only source document lookup path is an internal testing/stub utility boundary. | Keep direct configurable stub base URL/path. |
| McpServerConfiguration | direct-exception | MCP SSE server/client transport base URL is protocol transport wiring, not downstream business-service authorization traffic. | Keep explicit transport URL configuration. |
| TaxServiceClient (pos-invoice) | tax-exemption | ADR-0021: pos-tax is internal-only and exempt from gateway/Eureka migration. ADR-0014 also keeps tax out of gateway route whitelist. | Direct Docker DNS to pos-tax (for example `http://pos-tax:<port>/v1/tax`). |
| TaxFacadeTool (pos-mcp-server) | tax-exemption | ADR-0021 tax exemption. | Direct Docker DNS to pos-tax base URL. |
| TaxClient (pos-workorder) | tax-exemption | ADR-0021 tax exemption. | Direct Docker DNS to pos-tax base URL. |
| TaxClientConfig (pos-workorder) | tax-exemption | ADR-0021 tax exemption. | Keep direct pos-tax hostname configuration. |
| ExaWebSearchTool | external | Third-party Exa API. | Keep external HTTPS endpoint (`https://api.exa.ai`). |
| VehicleFitmentServiceImpl | external | Third-party NHTSA vPIC API. | Keep external HTTPS endpoint (`https://vpic.nhtsa.dot.gov/v1/vehicles`). |
| VehicleReferenceService (pos-vehicle-reference-carapi) | external | Third-party CarAPI. | Keep external HTTPS endpoint (`https://carapi.app/api`). |
| VehicleReferenceService (pos-vehicle-reference-nhtsa) | external | Third-party NHTSA vPIC API. | Keep external HTTPS endpoint (`https://vpic.nhtsa.dot.gov/v1/vehicles`). |
| TaxConfiguration + ExternalTaxServiceClient (pos-tax) | external | pos-tax consumes external provider API by design. ADR-0021 constrains inbound access, not outbound external tax usage. | Keep configured external base URL from tax properties. |

## Notes

- ADR-0040 confirms downstream services should rely on trusted `X-Authorities` produced at the gateway boundary for standard internal API authorization flows.
- direct-exception entries above are limited to cases where the current endpoint shape is not represented in the gateway route catalog or where the traffic is not a standard business-service call.
- Any future conversion of a direct-exception entry to gateway-routed should be accompanied by explicit gateway route and contract updates.
