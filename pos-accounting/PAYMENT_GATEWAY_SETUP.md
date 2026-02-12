# Payment Gateway Integration Setup

This document explains how to set up and configure the payment gateway integration for AP (Accounts Payable) payments.

## Overview

The AP Payment Service (`APPaymentServiceImpl`) now integrates with actual payment gateways via the `PaymentGatewayProvider` abstraction layer. This allows flexible gateway implementations while maintaining a clean separation of concerns.

**Current Implementation**: [Stripe](https://stripe.com)

## Architecture

### Components

1. **PaymentGatewayProvider** (`internal/payment/PaymentGatewayProvider.java`)
   - Interface defining the contract for payment gateway providers
   - Supports idempotency through request keys
   - Provides async payment status queries
   - Records: `GatewayPaymentRequest` and `GatewayPaymentResponse`

2. **StripePaymentGateway** (`internal/payment/StripePaymentGateway.java`)
   - Stripe implementation of `PaymentGatewayProvider`
   - Uses Stripe's Charges API for payment processing
   - Handles idempotency and transaction tracking
   - Supports Stripe Connect for marketplace scenarios

3. **APPaymentServiceImpl** (`service/APPaymentServiceImpl.java`)
   - Orchestrates the full AP payment workflow
   - Calls the gateway via `PaymentGatewayProvider.executePayment()`
   - Maps gateway responses to payment status states
   - Persists gateway transaction IDs and responses for audit trails

## Configuration

### Environment Variables

Configure your payment gateway with environment variables:

```bash
# REQUIRED: Stripe API key (secret key from the Stripe dashboard)
export STRIPE_API_KEY="sk_live_..." # or sk_test_... for sandbox

# OPTIONAL: Stripe Connect account ID (for marketplace/multi-account scenarios)
export STRIPE_CONNECT_ACCOUNT="acct_..."

# OPTIONAL: Idempotency key window in hours (default: 24)
# Stripe retains idempotency responses for this duration
export STRIPE_IDEMPOTENCY_WINDOW_HOURS="24"

# OPTIONAL: Which payment gateway provider to use (default: stripe)
export PAYMENT_GATEWAY_PROVIDER="stripe"
```

### Application Configuration

Alternatively, set these in `application.yml`:

```yaml
payment:
  gateway:
    provider: stripe  # or another implementation

stripe:
  api-key: ${STRIPE_API_KEY}
  connect-account: ${STRIPE_CONNECT_ACCOUNT:}
  idempotency-window-hours: 24
```

Or in `application-{profile}.yml` for environment-specific config:

```yaml
# application-prod.yml
payment:
  gateway:
    provider: stripe

stripe:
  api-key: ${STRIPE_API_KEY}  # Inject from secrets management
  connect-account: ${STRIPE_CONNECT_ACCOUNT}
```

## Usage

The payment execution is transparent to callers:

```java
@Autowired
private APPaymentService apPaymentService;

// Execute payment (gateway call handles internally)
APPaymentResponse response = apPaymentService.executePayment(
    new ExecuteAPPaymentRequest(
        paymentRef = "PAY-2026-001",
        vendorId = vendor.getId(),
        grossAmount = BigDecimal.valueOf(10000.00),
        currency = "USD",
        paymentMethod = PaymentMethod.ACH,
        paymentSource = "tok_visa", // Stripe token from frontend tokenization
        memo = "Invoice #INV-2026-001"
    ),
    currentUser = "user@example.com"
);

// Response contains gateway transaction ID for reconciliation
System.out.println("Gateway Transaction ID: " + response.getGatewayTransactionId());
```

## Idempotency & Retry Safety

The implementation is fully idempotent:

1. **Idempotency Keys**: Uses `paymentRef` as the idempotency key
2. **Duplicate Detection**: If the same `paymentRef` is submitted twice:
   - The payment repository detects the duplicate
   - The existing payment is returned (no double-charge)
3. **Gateway Level**: Stripe maintains idempotency for 24 hours (configurable)
   - If the same request is retried, Stripe returns the original response
   - No duplicate charges occur

### Example: Safe Retry

```java
// First attempt fails mid-response
APPaymentResponse response = apPaymentService.executePayment(request, user);
// Network error before response received...

// Safe retry with same request
APPaymentResponse response2 = apPaymentService.executePayment(request, user);
// Returns the same payment (no duplicate charge)
```

## Payment Status Flow

Payments progress through the following statuses:

```
INITIATED
    ↓
GATEWAY_PENDING → (call to PaymentGatewayProvider)
    ↓
GATEWAY_SUCCEEDED (successful charge) OR GATEWAY_FAILED (decline/error)
    ↓ (if succeeded)
GL_POST_PENDING → (asynchronous GL posting)
    ↓
GL_POSTED (or GL_POST_FAILED)
```

### Gateway Response Mapping

| Gateway Status | Flow | AP Payment Status |
|---|---|---|
| `SUCCEEDED` | Proceed to allocations | `GATEWAY_SUCCEEDED` |
| `AUTHORIZED` | Proceed to allocations | `GATEWAY_SUCCEEDED` |
| `PENDING` | Await settlement | `GATEWAY_PENDING` |
| `DECLINED` | Fail payment | `GATEWAY_FAILED` |
| `FAILED` | Fail payment | `GATEWAY_FAILED` |

## Extending with New Providers

To support a different payment gateway (e.g., Square, Adyen):

1. **Create a new implementation**:

```java
@Component
@ConditionalOnProperty(name = "payment.gateway.provider", havingValue = "square")
public class SquarePaymentGateway implements PaymentGatewayProvider {
    
    @Override
    public GatewayPaymentResponse executePayment(GatewayPaymentRequest request) {
        // Square-specific implementation
        ...
    }
    
    @Override
    public Optional<GatewayPaymentResponse> getPaymentStatus(String transactionId) {
        // Status query via Square API
        ...
    }
    
    @Override
    public String getProviderName() {
        return "Square";
    }
}
```

1. **Add dependencies** to `pom.xml`:

```xml
<dependency>
    <groupId>com.squareup</groupId>
    <artifactId>square</artifactId>
    <version>40.0.0</version>
</dependency>
```

1. **Configure in application.yml**:

```yaml
payment:
  gateway:
    provider: square

square:
  api-key: ${SQUARE_API_KEY}
  environment: sandbox  # or production
```

1. **Switch provider** via environment:

```bash
export PAYMENT_GATEWAY_PROVIDER=square
```

## Security Considerations

### API Keys

- **Never commit API keys** to version control
- Use environment variables or a secrets management system (e.g., HashiCorp Vault, AWS Secrets Manager)
- Rotate keys periodically per your security policy
- Use restricted API keys with minimal permissions (e.g., Stripe restricted tokens)

### PCI Compliance

- **Never collect raw card data** in your application
- Use Stripe's tokenization flow:
  - Frontend collects card → Creates a token (e.g., `tok_visa`)
  - Send token to backend as `paymentSource`
  - Backend passes `paymentSource` to Stripe API via `GatewayPaymentRequest`
- All card data remains within the Stripe/gateway network

### Logging

The integration logs:

- Payment initiation (no sensitive data)
- Gateway transaction IDs
- Status changes
- Failures and retry attempts

**Sensitive data is never logged**:

- API keys
- Card numbers
- Cardholder data
- Authentication codes

## Testing

### Local Development

Use Stripe's **test mode** API keys:

```bash
# Stripe test secret key
export STRIPE_API_KEY="sk_test_..." 

# Use test card numbers:
# Success: 4242 4242 4242 4242
# Decline: 4000 0000 0000 0002
# Insufficient Funds: 4000 0000 0000 9995
```

### Unit Testing

Mock the `PaymentGatewayProvider`:

```java
@ExtendWith(MockitoExtension.class)
class APPaymentServiceTest {
    
    @Mock
    private PaymentGatewayProvider paymentGateway;
    
    @InjectMocks
    private APPaymentServiceImpl service;
    
    @Test
    void testPaymentWithGatewaySuccess() {
        var gatewayResponse = new PaymentGatewayProvider.GatewayPaymentResponse(
            "ch_test_123",
            GatewayPaymentStatus.SUCCEEDED,
            "auth_code_123",
            null,
            "{}"
        );
        
        when(paymentGateway.executePayment(any())).thenReturn(gatewayResponse);
        
        var response = service.executePayment(request, user);
        
        assertEquals(APPaymentStatus.GATEWAY_SUCCEEDED, response.getStatus());
        assertEquals("ch_test_123", response.getGatewayTransactionId());
    }
}
```

### Integration Testing

Use Stripe's test mode with real Stripe SDK calls:

```java
@IntegrationTest
@ActiveProfiles("test")
class StripePaymentGatewayIT {
    
    @Autowired
    private StripePaymentGateway gateway;
    
    @Test
    void testStripeChargeWithTestCard() {
        var request = new PaymentGatewayProvider.GatewayPaymentRequest(
            "test-idempotency-key-" + UUID.randomUUID(),
            BigDecimal.valueOf(10.00),
            "USD",
            PaymentMethod.CREDIT_CARD,
            "vendor_123",
            "tok_visa", // Stripe test token
            "Test payment"
        );
        
        var response = gateway.executePayment(request);
        
        assertEquals(GatewayPaymentStatus.SUCCEEDED, response.status());
        assertTrue(response.transactionId().startsWith("ch_"));
    }
}
```

## Troubleshooting

### `IllegalStateException: Stripe API key not configured`

**Cause**: Missing `STRIPE_API_KEY` environment variable or `stripe.api-key` property

**Solution**:

```bash
# Set the environment variable
export STRIPE_API_KEY="sk_test_..."

# Or configure in application.yml
stripe:
  api-key: "sk_test_..."
```

### `PaymentGatewayException: Stripe payment failed`

Check the error message for the underlying issue:

- Insufficient funds
- Card declined
- Invalid card details
- Rate limiting

### `Idempotency conflict detected`

The same `paymentRef` was submitted within the idempotency window. This is **safe** and expected behavior:

- Application will return the existing payment
- No duplicate charge occurs
- Normal retry scenarios are protected

## Monitoring & Observability

The integration includes:

- Info-level logs for successful payments
- Error-level logs for failures
- Gateway transaction IDs in response (for reconciliation with Stripe dashboard)
- Metrics via Micrometer (if configured):
  - `payments.gateway.success` counter
  - `payments.gateway.failure` counter
  - `payments.gateway.latency` histogram

To enable detailed logging:

```yaml
logging:
  level:
    com.positivity.accounting.internal.payment: DEBUG
    com.stripe: DEBUG  # Stripe SDK logs
```

## Support & Resources

- [Stripe API Documentation](https://stripe.com/docs/api)
- [Stripe Java SDK](https://github.com/stripe/stripe-java)
- [Stripe Testing Guide](https://stripe.com/docs/testing)
- [Stripe Charges API Reference](https://stripe.com/docs/api/charges)
