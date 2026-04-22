# pos-api-gateway

Spring Cloud Gateway (WebFlux) acting as the single external entry point for the Durion POS platform. Validates JWT bearer tokens, strips or propagates identity headers, routes requests to downstream microservices discovered through Eureka, and aggregates Swagger UI documentation from all services.

## Responsibilities

- Authenticate and authorise all inbound requests via local JWT validation (JJWT)
- Decode the `perm_bits` BitSet claim and map bit indexes to canonical `PERM_*` authority strings
- Route traffic to registered `pos-*` microservices using Eureka load-balanced routes (`lb://`)
- Strip inbound identity-spoofing headers before forwarding to downstream services
- Rewrite API version header (`X-API-Version: 1`) into URL path prefix (`/v1/`)
- Serve a unified Swagger UI aggregating OpenAPI specs from every registered service

## Key Classes

- `SecurityGatewayConfig` — reactive Spring Security filter chain; JWT validation and header injection
- `GatewayPermissionCatalog` — maps bit indexes to canonical authority strings; enforces `perm_ver`
- `GatewayAuthProperties` — binds `auth.*` and `pos.gateway.security.*` configuration properties
- `ApiVersionHeaderToPathFilter` — rewrites `X-API-Version` header value to URL path segment
- `OpenApiConfig` — aggregates per-service OpenAPI docs into the unified Swagger UI

## Routes (selected)

Routes strip the leading path prefix before forwarding to the upstream service.

| Prefix | Upstream Service |
|---|---|
| `/accounting/**` | `lb://ACCOUNTING` |
| `/catalog/**` | `lb://CATALOG` |
| `/customer/**` | `lb://CUSTOMER` |
| `/inventory/**` | `lb://INVENTORY` |
| `/invoice/**` | `lb://INVOICE` |
| `/order/**` | `lb://ORDER` |
| `/workorder/**` | `lb://WORKORDER` |
| `/security-service/**` | `lb://SECURITY-SERVICE` |
| `/shop-manager/**` | `lb://SHOP-MANAGER` |

Discovery locator is disabled; only explicitly configured routes are exposed. `pos-tax` and `pos-events` are intentionally not routed externally.

## Configuration

| Property | Default | Description |
|---|---|---|
| `pos.gateway.security.strict-jwt-header-validation` | `true` | Reject unsafe JWT header patterns before introspection |
| `pos.gateway.security.allowed-jwt-algorithms` | `HS256` | Permitted JWT `alg` values |
| `auth.token-identity-required` | `false` | Reject tokens missing `perm_bits` claim |
| `auth.strip-inbound-identity-headers` | `true` | Strip `X-User`, `X-User-Id`, `X-Authorities` headers |
| `auth.auth-path-root` | `/security-service/v1/auth` | Public auth path that bypasses JWT checks |

## Dependencies

No internal `pos-*` module dependencies. Requires Spring Cloud Gateway, Netflix Eureka client, and JJWT.

## Development

```bash
./mvnw -pl pos-api-gateway -am spring-boot:run
```

Swagger UI: `http://localhost:8080/swagger-ui.html`
