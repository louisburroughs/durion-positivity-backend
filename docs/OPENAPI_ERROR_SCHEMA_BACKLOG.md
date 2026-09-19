# ADR-0017 §3 error-envelope backlog (`errorSchema`) — CLOSED 2026-09-19

The backlog this file tracked is empty. It listed **642 findings across 17 modules and 363
operations**: every 4xx/5xx response in a committed `openapi.yaml` whose body was a named schema
other than the `ApiError` envelope — usually the endpoint's own success DTO, inferred by springdoc
because the `@ApiResponse` carried no explicit `@Schema`.

Today every committed spec has **0 findings**, and `errorSchema: STRICT` is set for every module
that publishes a spec (`pos-openapi-validation/src/test/resources/openapi/module-inventory.yaml`).
The strict run that used to fail now passes:

```bash
./mvnw -pl pos-openapi-validation -am -DskipTests=false \
  -Dopenapi.validation.mode=strict -Dtest=OpenApiRepositoryValidationTest test
```

`API Artifacts Sync` can therefore run with `validation_mode: strict`.

## What closing it took

Declaring `ApiError` was only half of it. Reviewing the first wave surfaced two mismatches the spec
validator cannot see, and both were fixed in the same issue (#1720):

- **Runtime bodies that were not `ApiError`.** `pos-location` and `pos-tenant` rendered RFC 9457
  `ProblemDetail` from module advices; `pos-shop-manager` answered a scheduling 409 with its own
  `ConflictResponse`; `pos-workorder` returned `Map{code, message}` in two controllers;
  `pos-event-receiver` returned a plain string on one 400. All now answer `ApiError`.
- **Empty error bodies under an `ApiError` declaration.** ~75 operations returned
  `ResponseEntity.notFound()/badRequest()/status(4xx|5xx).build()`. They now throw the module's
  domain exception (or a `ResponseStatusException` the module advice maps), so the body matches the
  contract. The deliberate 501 placeholders in `pos-price` and `pos-inventory` answer
  `NOT_IMPLEMENTED` envelopes rather than nothing.

Where a domain contract documents a non-envelope answer, it was kept and recorded instead:

- `pos-catalog` `getTreadDesignForProduct` 404 has no body — `domains/product/.business-rules/BACKEND_CONTRACT_GUIDE.md`
  calls the miss "an ordinary outcome". Other `@Schema(hidden = true)` 404s in that module follow the
  same shape.
- `pos-customer` `requirementsMet` answers a bodiless 401 from the security entry point.
- `pos-shop-manager`'s conflict payload was preserved by **extending the envelope**: ADR-0017 §3 now
  defines optional `conflicts[]` and `suggestedAlternatives[]`, and §2's closed 409 list gains the
  reservation collision ([durion#497](https://github.com/louisburroughs/durion/pull/497)).

## Keeping it closed

- `errorSchema: STRICT` fails the build for any module whose spec republishes a non-`ApiError` error
  body, so a regression cannot merge silently.
- The gap that remains is **bodiless** errors: a 4xx/5xx response with no content passes the check,
  because a bodiless error can be legitimate. So an error declared `content = @Content` while the
  runtime returns `ApiError` (or the reverse) still slips through. Tracked in
  [#2114](https://github.com/louisburroughs/durion-positivity-backend/issues/2114).
- When adding an endpoint, annotate every error status explicitly:

```java
@ApiResponse(
        responseCode = "404",
        description = "Order not found",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
```

  On an operation whose mapping `produces` a non-JSON type (a PDF, say), set
  `mediaType = "application/json"` on the error `@Content`s **and** make the advice preset that
  content type, or Accept negotiation drops the body.

- Regenerate the module's spec (`scripts/generate-openapi.sh <module>`) and run `API Artifacts Sync`
  after any OpenAPI change.

Reference conversion: `pos-vehicle-inventory`. Envelope schema and codes: `docs/ERROR_ENVELOPE.md`.
