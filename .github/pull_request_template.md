<!-- .github/pull_request_template.md -->
## Capability & Traceability
- Capability: `cap:<cap-id>`
- Parent STORY (durion): louisburroughs/durion#<parent-id>
- Child issue: <owner>/<repo>#<child-id>  <!-- this repo’s issue -->
- Domain: `domain:<domain>`

## Contract References (REQUIRED for backend PRs touching API/event behavior)
- Contract guide entry (durion): `domains/<domain>/.business-rules/BACKEND_CONTRACT_GUIDE.md` → <link-to-anchor>
- Durion contract PR (if applicable): <link-to-durion-pr>

## Scope
- What changed:
- Why:

## Tests
- [ ] Unit tests added/updated
- [ ] Integration tests added/updated
- [ ] Provider behavioral contract tests added/updated (backend)
- [ ] Consumer/UI tests added/updated (frontend)
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
