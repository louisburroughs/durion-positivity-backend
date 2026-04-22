# pos-inquiry

Placeholder service module for the Durion POS inquiry domain. Currently scaffolded with a minimal Spring Boot application and OpenAPI configuration; business logic has not yet been implemented.

## Responsibilities

This module is reserved for cross-domain inquiry and reporting aggregation. The specific responsibilities will be defined when implementation begins.

## Key Classes

- `PosInquiryApplication` — Spring Boot entry point
- `OpenApiConfig` — OpenAPI/Swagger configuration

## Configuration

| Property            | Default  | Description                  |
| ------------------- | -------- | ---------------------------- |
| `EUREKA_SERVER_URL` | required | Eureka service discovery URL |

## Dependencies

No internal `pos-*` module dependencies currently declared.

## Development

```bash
./mvnw -pl pos-inquiry -am spring-boot:run
```
