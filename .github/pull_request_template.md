<!-- .github/pull_request_template.md -->
## Capability & Traceability
- Capability: `cap:<cap-id>`
- Parent STORY (durion): louisburroughs/durion#<parent-id>
- Child issue: louisburroughs/durion-positivity-backend#<child-id>
- Domain: `domain:<domain>`

## Contract References (REQUIRED for backend PRs touching API/event behavior)
<!--
  The "Contract Sync Enforcement" check FAILS unless the text BACKEND_CONTRACT_GUIDE.md
  appears somewhere in this PR body whenever contract-relevant files change
  (controllers, DTOs, *Request/*Response, events, openapi.yaml, validation/error models).
  Keep the reference below and link it to the relevant domain anchor.
-->
- Contract guide entry (durion): `domains/<domain>/.business-rules/BACKEND_CONTRACT_GUIDE.md` → <link-to-anchor>
- Durion contract PR (if applicable): <link-to-durion-pr>

## Contract Chain (when a Controller changed)
- [ ] OpenAPI annotations updated on the controller
- [ ] `openapi.yaml` (+ `pos-api-gateway/docs/openapi-aggregate.yaml`) regenerated
- [ ] Angular SDK regenerated (`durion-positivity-sdk-angular`)

## Scope
- What changed:
- Why:

## Tests
- [ ] Unit tests added/updated
- [ ] Integration tests added/updated
- [ ] Provider behavioral contract tests added/updated
- How to run:

## Risk & Rollback
- Risk level: Low / Medium / High
- Rollback plan:

## Checklist
- [ ] Branch name matches `cap/<cap-id>`
- [ ] PR title starts with `[CAP:<cap-id>]`
- [ ] Links to parent + child issues are present
- [ ] Contract guide updated (when contract semantics changed)
- [ ] Required CI checks passing
