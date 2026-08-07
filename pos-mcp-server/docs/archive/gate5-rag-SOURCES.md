# Sources and Verification Notes

Source bundle: `gate5ragauthoringbundle.pdf`, generated from branch `feat/nl-interface-gates`.

## Common grounding sources

- Part 1 — Authoring Prompt: deterministic document ids, filenames, rag scopes, required permission intent, audience, chunk target, hard constraints, deliverables, and definition of done.
- Part 2 — Gate 5 Design: permission-aware visibility filter, required permission metadata, hybrid dense + lexical retrieval, identifier recall rationale, and G5.5 document table.
- Appendix A — `de-bookkeeping-rag.md`: accounting roles, accounting permissions, double-entry bookkeeping, immutability, auditability, idempotency, event-to-accounting examples, and natural-language interpretation guidelines.
- Appendix B — `inv-cntrl-rag.md`: inventory terminology, stock states, movements, receipts, valuation, replenishment, metrics, traceability, event-driven inventory model, and natural-language interpretation guidelines.
- Appendix C — `shop-management-rag.md`: appointments, bays, mobile units, assignments, estimates, workorders, change requests, labor entries, WIP, pick lists, statuses, scheduling, conflict override, and visible shop/workorder permissions.
- Appendix D — `shop-management-guidelines.md`: used for tone and workflow framing where available in the bundle.
- Appendix E — `hr-functions-guide.md`: people/availability/role-related permission references where visible in extracted permission list.
- Appendix F — `security-service-guide.md`: security and permission framing where visible in extracted permission list.

## Per-document source map

- `capability-catalog.md`: Part 1, Part 2/G5.5, Appendices A-C, extracted permission samples.
- `cross-domain-playbooks.md`: Part 1 requirements, Part 2/G5.5, Appendices A-C.
- `glossary-identifiers.md`: Part 1 identifier requirements, Part 2 exact-code recall rationale, Appendices A-C.
- `order-guide.md`: Part 1 required permission samples, Part 2/G5.5, Appendix B for receiving/inventory hand-off, Appendix A for accounting reconciliation.
- `pricing-guide.md`: Part 1 verified pricing permissions, Part 2/G5.5, Appendix C estimate/workorder/invoice flow.
- `tax-guide.md`: Part 1 tax-guide requirement and hard constraint not to invent permissions, Appendix A accounting treatment principles, Appendix C estimate/workorder/invoice flow.
- `customer-vehicle-guide.md`: Part 1 verified CRM/customer/vehicle permissions, Appendix C appointment/estimate/workorder dependencies.
- `reporting-metrics.md`: Part 1 reporting requirement, Appendix B inventory metrics, Appendix C WIP/schedule/status concepts, Appendix A financial reporting principles.
- `admin-governance.md`: Part 2 Gate 5 drift guards and permission metadata, Appendix C approval/change-request/conflict override rules, Appendix A immutability/audit principles.
- `events-observability.md`: Part 2 permission-aware RAG visibility and hybrid retrieval, Appendices A-B event/audit/idempotency principles, Appendix C appointment/workorder event context.
- `role-permission-matrix.md`: Appendix A accounting roles/permissions, Appendix C shop/workorder roles/permissions, extracted permission-code samples in Part 1.

## TODO(verify) summary

- Confirm canonical formats for workorder number, SKU, VIN, invoice number, PO number, account code, and claim code.
- Confirm exact order statuses, PO schema, order approval rules, and order OpenAPI operations.
- Confirm pricing rule precedence, override permissions, and effective-date semantics.
- Confirm real tax read permission or owning service for tax calculation/read visibility. No `tax:*:view` code was present in the bundle.
- Confirm invoice number format and invoice read permission. The bundle explicitly notes no `invoice:read` sample.
- Confirm party type taxonomy, vehicle fields, fleet hierarchy, merge/deduplication rules, and service-history ownership.
- Confirm real reporting read permission. No generic `reporting:*:view` code was present in the bundle.
- Confirm admin/security read permissions for governance and role-permission catalog visibility. The bundle exposed `security:user:create`, but not a read-level security catalog code.
- Confirm exact event names and payload fields for observability.
- Confirm complete role-to-permission mappings from the repository `permissions.yaml` files.
