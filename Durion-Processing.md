# Durion Processing

## Request Details
- Task: Apply clarification #244 answers to origin story #53 and update status/labels accordingly.
- Origin story: #53 StorePrice: Sync Locations from durion-hr for Pricing Scope.
- Clarification answers: Missing from durion-hr feed -> set local location to INACTIVE indefinitely; price overrides for INACTIVE locations must be disabled.
- Required actions: Update story content (business rules, acceptance criteria, open questions), remove blocked:clarification, set status:needs-review or ready-for-dev per resolution, close clarification.

## Action Plan
- [x] Update story text: integrate deletion handling (inactive indefinitely when missing in feed), and disable existing price overrides when location becomes INACTIVE.
- [x] Adjust acceptance criteria to cover inactive-from-missing-feed and override disabling.
- [x] Clear Open Questions section by incorporating answers.
- [x] Update labels on origin story: remove blocked:clarification; set status:needs-review (or ready-for-dev if all resolved).
- [x] Comment/close clarification #244 referencing updates and outcomes.
