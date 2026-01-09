# Durion Processing

## Request Details
- Task: Apply clarification #246 answers to origin story #57 (Product master creation) and update status/labels accordingly.
- Clarification answers summary: SKU global uniqueness confirmed; Manufacturer+MPN unique; other external IDs not primary keys; SKU immutable, manufacturerId/mpn editable; deactivation allowed even with stock/work orders/POs (with flags/notifications); tire size/spec part of description; search fields: description, category name, attributes, name.
- Required actions: Update story content and acceptance criteria with resolved rules; clear open questions; adjust labels/status; close clarification.

## Action Plan
- [x] Update story business rules and data requirements with uniqueness (SKU, manufacturer+MPN), immutability (SKU only), external IDs not primary keys, tire spec location, search fields.
- [x] Add deactivation rules: allowed with stock/open work orders/open POs; include flagging work orders and notifying PO admin.
- [x] Update acceptance criteria to reflect search fields, deactivation rules, and uniqueness/immutability decisions.
- [x] Clear Open Questions section with clarification answers.
- [x] Update origin story labels: remove blocked:clarification; set status:ready-for-dev.
- [x] Comment and close clarification #246 referencing applied updates.

## Summary
- Story #57 updated with confirmed uniqueness/immutability rules, deactivation handling with work order flags and PO admin notifications, search fields, and data notes; open questions cleared and status set to ready-for-dev.
- Clarification #246 documented, commented, and closed after updating the origin story.
