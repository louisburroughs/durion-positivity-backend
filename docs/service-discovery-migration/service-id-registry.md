---
title: Service Discovery Migration Service ID Registry
wave: 0
branch: cap/641-service-discovery-migration
status: draft
---

## Verification Sources

- Spring service IDs verified from each module `src/main/resources/application.yml` `spring.application.name` value.
- Gateway route map verified from `pos-api-gateway/src/main/resources/application.yml` route predicates and `lb://` targets.

## Service Registry

| Module | spring.application.name | Eureka ID | Gateway Path Prefix | Gateway Target URI | Docker DNS Hostname (direct/startup use) |
| --- | --- | --- | --- | --- | --- |
| pos-api-gateway | pos-api-gateway | api-gateway | N/A | N/A | pos-api-gateway |
| pos-accounting | accounting | accounting | /accounting/** | lb://ACCOUNTING | pos-accounting |
| pos-bulk-loader | pos-bulk-loader | pos-bulk-loader | /bulk-loader/** | lb://POS-BULK-LOADER | pos-bulk-loader |
| pos-catalog | catalog | catalog | /catalog/** | lb://CATALOG | pos-catalog |
| pos-customer | customer | customer | /customer/** | lb://CUSTOMER | pos-customer |
| pos-event-receiver | event-receiver | event-receiver | /event-receiver/** | lb://EVENT-RECEIVER | pos-event-receiver |
| pos-image | image | image | /image/** | lb://IMAGE | pos-image |
| pos-inquiry | inquiry | inquiry | /inquiry/** | lb://INQUIRY | pos-inquiry |
| pos-inventory | inventory | inventory | /inventory/** | lb://INVENTORY | pos-inventory |
| pos-invoice | invoice | invoice | /invoice/** | lb://INVOICE | pos-invoice |
| pos-location | location | location | /location/** | lb://LOCATION | pos-location |
| pos-mcp-server | mcp-server | mcp-server | /mcp-server/** | lb://MCP-SERVER | pos-mcp-server |
| pos-order | order | order | /order/** | lb://ORDER | pos-order |
| pos-people | people | people | /people/** | lb://PEOPLE | pos-people |
| pos-price | price | price | /price/** | lb://PRICE | pos-price |
| pos-security-service | security-service | security-service | /security-service/** | lb://SECURITY-SERVICE | pos-security-service |
| pos-shop-manager | shop-manager | shop-manager | /shop-manager/** | lb://SHOP-MANAGER | pos-shop-manager |
| pos-vehicle-fitment | vehicle-fitment | vehicle-fitment | /vehicle-fitment/** | lb://VEHICLE-FITMENT | pos-vehicle-fitment |
| pos-vehicle-inventory | vehicle-inventory | vehicle-inventory | /vehicle-inventory/** | lb://VEHICLE-INVENTORY | pos-vehicle-inventory |
| pos-workorder | workorder | workorder | /workorder/** | lb://WORKORDER | pos-workorder |
| pos-documents | documents | documents | Not gateway-routed in current route table | N/A | pos-documents |
| pos-tax | pos-tax | pos-tax | Not gateway-routed (ADR-0014 and ADR-0021) | N/A | pos-tax |
| pos-events (library/event registration endpoint) | N/A (shared library) | N/A | Not gateway-routed | N/A | pos-event-receiver (service endpoint used by initializers) |

## Migration Notes

- Gateway discovery locator is intentionally disabled (ADR-0014), so only explicit route entries are externally/gateway reachable.
- Tax remains internal-only and exempt from Eureka/gateway migration scope (ADR-0021).
- Startup infrastructure callers should use Docker DNS hostnames directly (for example pos-security-service and pos-event-receiver) to avoid startup-order coupling to discovery.
- Current gateway path for MCP is `/mcp-server/**` in source configuration.
