# pos-reference-mock

Fake **external labor-guide vendor** serving the Durion-normalized provider contract from
checked-in, deterministic JSON fixtures. Built for Phase 1 of the service-time sourcing plan
([`pos-catalog/docs/service-time-sourcing-plan.md`](../pos-catalog/docs/service-time-sourcing-plan.md) §10,
issue #1569): the whole labor-time pipeline (SPI, ingestion, storage, transport, estimate
defaulting, variance) is exercised end-to-end before any licensing spend, and the module stays
alive forever as the SPI contract-test double.

## Deliberately outside the platform mesh

No Eureka registration, no gateway route, no JWT/security, no database, no Kafka, no
`pos-events`. Reached only via adapter base-url config (the pos-supplier sandbox-override
pattern, e.g. `pos.catalog.labor-guide.providers[mockguide].base-url`). Fixed port **8095**.
Its generated OpenAPI spec (`/v3/api-docs`, Swagger UI at `/swagger-ui.html`) is the
**normative** description of the provider contract; Phase-2 vendor adapters translate vendor
reality onto it.

## Endpoints (v1)

| Endpoint | Purpose |
|---|---|
| `GET /mock/labor-guide/v1/operations` | Operations applicable to a vehicle (`year`, `make`, `model`, `submodel`, `engineCode` wildcard when absent; `search` on name) |
| `GET /mock/labor-guide/v1/labor-times` | Most specific time for (`providerOperationCode`, vehicle); 404 no body on miss |
| `GET /mock/labor-guide/v1/feed/manifest` | STORE-mode import manifest (fixed id per revision, counts, SHA-256 checksum) |
| `GET /mock/labor-guide/v1/feed/chunks/{seq}` | 1-based 50-line feed chunks, validated against `manifestId` |

Every endpoint accepts chaos knobs `?delayMs=` (capped at 10000 ms) and `?failRate=` (0.0–1.0,
probability of a bodiless 503) for degradation testing.

## Fixtures

`src/main/resources/fixtures/laborguide/labor-guide-fixture.json` — revision `2026-09-01`,
~300 rows over a 20-vehicle matrix using vendor codes `MG-<DURION-CODE>` for the 50 seeded
service operation codes. Mandatory cases per plan §7 Phase 1 item 1:

- overlap group `WHEEL-OFF` shared by `MG-BRAKE-PAD-FRONT` / `MG-BRAKE-PAD-REAR` (and rotors);
- `MG-BRAKE-ROTOR-FRONT-PAIR` includes `BRAKE-PAD-FRONT` (Durion codes in `includedOperations`);
- `MG-DIAG-SCAN` diagnostic block time (1.0 h, all-wildcard vehicle key);
- `RETAIL_FLAT_RATE` and `OEM_WARRANTY` rows for `MG-BRAKE-PAD-FRONT` on the same vehicle
  (Honda Civic 2019) so time-type precedence is testable;
- `MG-FOG-LAMP-ALIGN` has no Durion counterpart, exercising the ingest unmapped-operation queue.

## Run

```bash
cd pos-reference-mock && ../mvnw spring-boot:run   # http://localhost:8095
../mvnw -pl pos-reference-mock -am test
```

In Docker Compose the service is `pos-reference-mock` on `pos-network`
(`http://pos-reference-mock:8095`), published on the host as `localhost:8100` — 8095 is
pos-people-contact's host port. Excluded from coverage-floor thresholds (plan §10) but part
of the reactor build and of `pos-coverage-aggregate` so it cannot rot.
