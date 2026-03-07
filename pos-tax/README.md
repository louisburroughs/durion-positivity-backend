# pos-tax

Tax calculation service with external API passthrough and test mode support.

## ⚠️ Internal Service Only

**pos-tax is an INTERNAL service and MUST NOT be exposed to external traffic.**

### Deployment Modes

1. **Library Mode (Recommended)**
   - pos-tax is included as a Maven dependency in other services (e.g., pos-workorder)
   - No separate deployment required
   - Services directly inject `TaxCalculationService` bean
   - No network traffic, lower latency
   - Default configuration (Eureka registration disabled)

2. **Standalone Mode** (only if needed for centralized deployment)
   - Deploy as separate internal microservice
   - **MUST NOT be added to API Gateway routes**
   - Only accessible to internal microservices on private network
   - Enable Eureka registration with environment variable: `EUREKA_REGISTER_ENABLED=true`
   - Use network policies/security groups to restrict access to internal subnet only

### Security Configuration

- **API Gateway**: pos-tax has NO route configured in pos-api-gateway and MUST NOT be added
- **Eureka Registration**: Disabled by default (`register-with-eureka: false`)
- **Network Access**: If deployed standalone, use firewall/security groups to allow only internal service-to-service communication
- **Service Discovery**: Not discoverable by external clients

## Overview

The pos-tax service provides tax calculation capabilities for the Durion POS system. It supports two operating modes:

1. **Production Mode**: Passes tax calculation requests to an external tax service API
2. **Test Mode**: Uses a simple configurable calculator for development and testing

## Key Features

- **Dual-mode operation**: Seamlessly switch between test and production modes via configuration
- **Jurisdiction breakdown**: Returns tax breakdown by state, county, city, and special districts
- **Tax exemption support**: Handles tax-exempt line items
- **Line-item detail**: Provides per-line-item tax calculations
- **Resilience**: Built-in retry logic with exponential backoff for external service calls
- **Event emission**: Emits audit events for all tax calculations via pos-events
- **OpenAPI documentation**: Full REST API documentation at `/swagger-ui.html`

## Configuration

### Test Mode (Development)

Enable test mode in `application-local.yml`:

```yaml
pos:
  tax:
    test-mode:
      enabled: true
      default-rates:
        STATE: 0.0725      # 7.25% state tax
        COUNTY: 0.01       # 1% county tax
        CITY: 0.0025       # 0.25% city tax
```

### Production Mode (External Service)

Configure external service in `application.yml`:

```yaml
pos:
  tax:
    test-mode:
      enabled: false
    external-service:
      base-url: ${TAX_SERVICE_URL}
      api-key: ${TAX_SERVICE_API_KEY}
      connect-timeout: 5000
      read-timeout: 10000
    retry:
      max-attempts: 3
      initial-backoff: 500
      multiplier: 2.0
```

### Environment Variables

- `TAX_TEST_MODE`: Set to `true` or `false` to control operating mode
- `TAX_SERVICE_URL`: Base URL of the external tax service API
- `TAX_SERVICE_API_KEY`: API key for external service authentication

## API Endpoints

### Calculate Tax

**POST** `/api/v1/tax/calculate`

Calculates tax for the provided line items and location.

**Request:**

```json
{
  "lineItems": [
    {
      "lineItemId": "item-1",
      "description": "Widget",
      "quantity": 2,
      "unitPrice": 50.00
    }
  ],
  "postalCode": "90001",
  "stateCode": "CA",
  "city": "Los Angeles",
  "countryCode": "US"
}
```

**Response:**

```json
{
  "subtotal": 100.00,
  "totalTax": 10.00,
  "total": 110.00,
  "effectiveTaxRate": 10.00,
  "jurisdictions": [
    {
      "jurisdictionCode": "CA-STATE",
      "jurisdictionName": "CA State Tax",
      "jurisdictionType": "STATE",
      "taxRate": 7.25,
      "taxAmount": 7.25
    }
  ],
  "lineItemTaxes": [
    {
      "lineItemId": "item-1",
      "subtotal": 100.00,
      "taxAmount": 10.00,
      "total": 110.00,
      "taxExempt": false
    }
  ],
  "testMode": true,
  "calculatedAt": "2026-02-14T12:00:00Z"
}
```

### Get Mode

**GET** `/api/v1/tax/mode`

Returns the current operating mode (test or production).

**Response:**

```json
{
  "mode": "test",
  "testMode": true
}
```

## Authorization

REST endpoints in this module use method-level `@PreAuthorize` checks with the
following authorities:

- `tax:calculate` for `POST /v1/tax/calculate`
- `tax:mode:view` for `GET /v1/tax/mode`

## Programmatic Usage

Other services can use the `TaxCalculationService` interface directly:

```java
@Service
public class OrderService {
    
    private final TaxCalculationService taxService;
    
    public OrderService(TaxCalculationService taxService) {
        this.taxService = taxService;
    }
    
    public void calculateOrderTax(Order order) {
        TaxCalculationRequest request = TaxCalculationRequest.builder()
            .lineItems(convertOrderItems(order))
            .postalCode(order.getShipToPostalCode())
            .stateCode(order.getShipToState())
            .city(order.getShipToCity())
            .referenceId(order.getId().toString())
            .build();
        
        TaxCalculationResponse response = taxService.calculateTax(request);
        order.setTaxAmount(response.getTotalTax());
    }
}
```

## Module Structure

- `com.positivity.tax` - Main application package
  - `service/` - Public API (exposed to other modules)
    - `TaxCalculationService` - Main service interface
  - `internal/` - Internal implementation (not accessible from other modules)
    - `config/` - Configuration classes and event types
    - `controller/` - REST API controllers
    - `dto/` - Data transfer objects
    - `service/` - Service implementations
      - `TaxCalculationServiceImpl` - Main service implementation
      - `TestModeTaxCalculator` - Test mode calculator
      - `ExternalTaxServiceClient` - External service client

## Testing

Run tests with:

```bash
./mvnw test -pl pos-tax
```

The test suite includes:

- Service layer unit tests
- Request validation tests
- Tax calculation accuracy tests
- Tax exemption handling tests
- Mode switching tests

All tests run in test mode by default (configured in `application-test.yml`).

## Service Discovery

The service registers with Eureka as `pos-tax` and is available at:

- Local development: <http://localhost:8091>
- Via API Gateway: <http://localhost:8080/pos-tax/api/v1/tax/calculate>

## Dependencies

Key dependencies:

- Spring Boot 4.0.2
- Spring Cloud (Eureka client)
- Resilience4J (retry logic)
- pos-events (audit event emission)
- Springdoc OpenAPI (API documentation)

## Related Modules

- `pos-events` - Event audit logging
- `pos-order` - Order management (primary consumer)
- `pos-workorder` - Work order estimates (primary consumer)
- `pos-invoice` - Invoice generation (primary consumer)

## Development Notes

### Adding New Tax Jurisdictions

To add support for new jurisdictions in test mode, update `application-local.yml`:

```yaml
pos:
  tax:
    test-mode:
      default-rates:
        STATE: 0.0725
        COUNTY: 0.01
        CITY: 0.0025
        DISTRICT: 0.005  # Add new jurisdiction type
```

### Changing Tax Rates

Tax rates are configured per environment:

- Local development: `application-local.yml`
- Testing: `application-test.yml`
- Production: Environment variables via Kubernetes ConfigMap

### External Service Integration

When integrating with a real external tax service:

1. Update `TaxConfiguration` to match the external API requirements
2. Modify DTOs in `internal/dto/` if needed for API compatibility
3. Update `ExternalTaxServiceClient` with correct endpoint paths
4. Add integration tests with the external service (stubbed or sandbox)

## Observability

The service emits:

- **Events**: `TAX_CALCULATE` via pos-events for audit trails
- **Metrics**: Standard Spring Boot Actuator metrics at `/actuator/prometheus`
- **Health**: Health endpoint at `/actuator/health`
- **Logs**: Structured logging with tax calculation details

## Security Notes

- API key for external service must be stored in secrets (never in code)
- Test mode should be disabled in production environments
- Tax calculation requests are logged for audit purposes (ensure compliance with PII policies)
