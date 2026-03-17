# POS Invoice Service

## Payment API Idempotency

The payment initiation endpoint enforces strict idempotency semantics.

### Endpoint

```http
POST /v1/invoices/{invoiceId}/payments
```

### Behavior

- Reusing the same `idempotencyKey` with the same payload returns the original payment intent result.
- Reusing the same `idempotencyKey` with a different payload returns HTTP `409 Conflict` with code `PAYMENT_IDEMPOTENCY_CONFLICT`.

A payload is considered different when any of these fields do not match the original request:

- `invoiceId`
- `paymentFlow`
- `amount`
- `paymentToken`
