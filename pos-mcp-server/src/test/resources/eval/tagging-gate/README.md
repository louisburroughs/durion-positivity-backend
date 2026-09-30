# Tagging gate fixtures (ADR-0068 section 6, spec 2.9)

Ground truth for the question-tagging bake-off: one fixture per language, each utterance carrying
the expected answer to every tag. Operators run the utterances through `mcp.tagging.mode=shadow`
once per candidate model, then score both taggers against `expected_tags` with
`scripts/tagging_shadow_report.py --expected`.

| File | Language | `reviewed` |
| ---- | -------- | ---------- |
| `en.json` | English | `true` |
| `fr-CA.json` | Canadian French (bon de commande, facture, devis, bon de travail, stock, fournisseur) | `false` |
| `es.json` | Spanish (orden de compra, factura, presupuesto, orden de trabajo, inventario, proveedor) | `false` |

**fr-CA and es are translations by the authoring agent, not native-reviewed.** A native reader must
review them (and set `reviewed: true` and `reviewer`) before any promotion decision uses their
numbers. The report prints a warning next to an unreviewed language.

## Schema

```json
{ "schemaVersion": 1, "language": "en", "reviewed": true, "reviewer": null,
  "utterances": [ { "id": "en-0001", "text": "...", "source": "new",
      "expected_tags": {
        "follows_previous_turn": false, "simple_chat": false, "needs_web_search": false,
        "about_inventory": true, "about_orders": false, "implies_date_window": false,
        "admin_account_question": false, "compound_question": false,
        "workflow_state": "IDLE", "intent": "QUERY", "complexity": "SINGLE_LOOKUP",
        "risk": "LOW", "domain": "inventory", "entity": ["stock-item"] },
      "hard_negative_for": ["about_inventory"], "notes": "..." } ] }
```

- `id`: `<language>-NNNN`, unique. `text` is unique within a file and is the join key of the report
  (exact `userMessage`, else trimmed, whitespace-collapsed, casefolded).
- `source`: `new`, or `<file under eval/>#<id>` when the utterance reuses an existing fixture (for
  example `tool-selection/generated.json#ts-inventoryfacadetool-pos-1`), with ` (translated)` appended
  in fr-CA and es. Expected tags are labelled for this gate, not copied from the source fixture.
- `expected_tags`: every tag, always. The eight Nouls are booleans; `workflow_state`, `intent`,
  `complexity`, `risk`, `domain` are one of the option labels asked by `TaggingQuestions`
  (`domain` = the `rag-scope` values of `mcp.rag.preload.docs` plus `master`); `entity` is a
  possibly empty array of lexicon keys from `scope-graph/entities.yaml`.
- `hard_negative_for`: the tags for which the utterance is a negative that the heuristic tagger
  answers wrongly (true where the expected value is false, or non-`IDLE` where `IDLE` is expected).
  It was derived mechanically by running `HeuristicQuestionTagger` (main at 53305a2bd) over every
  text, not by hand. Re-derive it if a heuristic rule changes.
- `notes`: why the label is what it is, where it is not obvious.

## Labelling rules (from the tag questions)

The ground truth for every tag is the question wording in `TaggingQuestions` (CONTEXT, instructions,
criteria), not the heuristic's answer and not another fixture's labels (the write-safety fixtures answer
"does this need a confirmation?", a different question). A label is judged on the message alone: what
the assistant cannot know from the text is labelled as unknown, not guessed. Adjudicated by the NLTI
domain agent, 2026-09-30; keep future edits consistent with these rules.

- `follows_previous_turn`: true for any reference whose referent is not in the message: a pronoun
  (them, their, she, a dropped es subject such as "trajo"), a demonstrative with a record noun (this
  order, that customer, these parts, this vehicle, esa llanta), an ordinal or placeholder (the second
  one, the ones, ceux, los de), "the same one", a bare answer or confirmation (yes, go ahead, confirm),
  or a redo/change cue (again, instead, also, now sort by). A demonstrative that names the caller's
  own site ("this location", "cette succursale") is deictic, not conversational: false. A cue word
  used as an ordinary adverb (previous quarter, sign in again, "otra marca") is not a reference: false.
  A pronoun whose antecedent is in the message ("take back the wiper blades and put them back") is
  false. The fixture is ground truth even when no heuristic cue token is present.
- `simple_chat`: greetings, thanks, closings, small talk, and questions about the assistant itself,
  including an offer of help with no task yet ("can you help me with something?"). A bare answer or
  confirmation is not simple chat.
- Bare answers and confirmations ("yes", "go ahead", "confirm", "sure"): `intent` `UNKNOWN` and
  `risk` `LOW`. The message alone does not say whether a read or a change is being confirmed; the risk
  of the pending action lives in the session, and the "risk never downgrades" rule of spec §2.6
  protects it, not this label. `domain` `master`, `entity` empty.
- `about_inventory`: stock on hand or availability, whether we carry a part, where it is stored,
  receiving (including ASN and goods-receipt documents), transfers, adjustments and counts. A fitment
  or catalog lookup with no stock question ("which pads fit a 2018 Silverado", "look up product X in
  the catalog"), a return policy, a payment question that happens to name a SKU, a notification about
  stock, a store's address or hours, and staff availability are false.
- `about_orders`: true only when the message concerns an order document itself: a sales order, cart,
  or purchase order that is named or clearly referred to (its lines, status, receipt, approval or
  history). Receiving an ASN against a named PO is both `about_inventory` and `about_orders`. Returns,
  refunds and invoices with no order reference, revenue or sales totals, "in order to", work orders,
  and permission questions that mention POs ("which roles can approve purchase orders") are false.
- `implies_date_window`: true for a named period (last month, Q3, 2025, since January, as of
  September 30) and for a named day when the data covers that day (yesterday's orders, tomorrow's
  bookings, who is on shift today, bay 3 tomorrow afternoon, a time-of-day slot counts), and for a
  metric that only makes sense over a period (revenue, totals, top or largest customers, spend,
  growth, trend, average, share of sales, "ordered twice"). False for: a point-in-time value qualified
  by today/now/currently/right now (exchange rate today, price of crude right now, which discounts are
  active), "when was the last X" (asks a date, not a window), a scheduling target for an action (book
  on Thursday at 9, move to next Monday), a vehicle model year (2018 Silverado), and today/tomorrow as
  a conversational adverb (see you tomorrow, that's all for today).
- `admin_account_question`: administering the platform: its user accounts and logins, roles,
  permissions, who can access what, registrations, the platform's login/security policy (password
  policy, two-factor requirement) and the audit log, including the assistant's own audit events and
  emitting an audit event. False for: a customer's, supplier's, bank, GL or store-credit account; an
  inventory, safety, quality, tax-compliance or overtime audit; a business approval rule ("do we need
  the owner's permission for a discount over 20%"); a business record's activity history (the activity
  log of a work order); "role" as a job function; customer registrations; and a staff member's own
  session troubleshooting (refresh my token, how do I sign in again, why was I signed out).
- `compound_question`: two or more separate questions needing different information. One request with
  several conditions ("in stock and can we get it by Friday"), a list of related items ("returns and
  refunds", "invoices and partial payments"), a chained request whose second part depends on the first
  ("cheapest vendor, and is it the one we used last time"), and context sentences ("the customer is
  waiting") are false.
- `workflow_state`: not `IDLE` only when the user is carrying out the workflow now. Questions about
  POs, ASNs, shipments, counts and returns are `IDLE`. Issuing a refund or store credit for a return,
  or refunding an order, is `PROCESSING_RETURN`; a credit memo on an invoice, a stock transfer, a
  scrap or revaluation adjustment, and a bank reconciliation are `IDLE`.
- `intent`: `QUERY` reads (including "sort them", "also show"); `ACTION` creates, changes, approves,
  posts, sends, schedules, deletes, drafts, imports; `UNKNOWN` chat, fragments and bare answers.
- `complexity`: `MULTI_DOMAIN` when data from more than one area is joined, or several steps combine
  (rank then filter, compare periods, compute a share of a ranking). One ranking or one list is
  `SINGLE_LOOKUP`.
- `risk`, strictly by the Score criteria: `LOW` reading; `MEDIUM` a change that can be corrected later
  (note, appointment, draft, status, price or labor-rate change, adding a customer or ledger account,
  approving an estimate or a PO, creating a claim, a stock transfer or restock, and reversible account
  administration: disabling an account, assigning a role, resetting a password, granting a permission);
  `HIGH` money moves (payment, refund, store credit, paying a bill), a posting to accounting (journal
  entry, write-off, revaluation, count adjustments, finishing a bank reconciliation), a deletion,
  something sent outside the shop (email, text, campaign, submitting a PO to the vendor), or a change
  that cannot be undone (void/revert an invoice, cancel a sales order, close a period, merge customers,
  terminate an employee, an appended audit event). Do not copy the write-safety fixtures' labels.
- `domain` is the RAG-scope vocabulary; entities with no scope of their own map by the `domains:`
  sentences in `entities.yaml`: vehicle and campaign → `customer`; invoice, payment, credit memo,
  vendor bill, vendor spend and balances → `accounting` (receivables and payables; there is no invoice
  scope); purchase order, ASN, supplier → `inventory`; location, bays, appointments, store hours and
  address → `shopmanager`; user, role, permission, the platform audit log → `admin`; sign-in, tokens,
  password policy → `security`; system events, notifications, per-entity activity history and the
  assistant's audit events → `events` (grounded by `events-observability.md`); staff shifts, vacation,
  certifications → `hr`. `master` for a bare follow-up, a compound question spanning two areas, and
  the assistant or platform in general.
- `entity`: lexicon keys named or clearly referred to; the audit log, a period and a metric are not
  entities.
- fr-CA and es keep deliberate anglicisms that real shop talk uses and the English keyword heuristics
  trip on (PO, purchase orders, sales tax, online, web, store, location, part, part time, access, bay;
  "po" as pouces). Each is marked in `notes`. The native reviewer may replace one, but replacing it
  removes a hard negative; add a locale-term twin instead of rewording.
- When a relabel to true (or non-`IDLE`) makes a `hard_negative_for` entry a positive, the entry is
  removed (the heuristic was right); `hard_negative_for` is otherwise never edited by hand.

Open questions for the product owner, labelled per the wording above until decided: whether
reversible account administration (role grant, permission grant, password reset) should be `HIGH`
(would need the Score criteria changed); whether an appended audit event is `HIGH` because it cannot
be undone; whether a scheduling target date should imply a date window; whether the platform's login
policy is administration; the `hr` / `shopmanager` overlap on technician schedules and bay
assignments; and `admin` versus `events` for the platform audit log.

## Minimums (enforced by `scripts/test_tagging_gate_fixtures.py`, per language)

Each Noul: 15 positives, 15 negatives, 5 hard negatives. Choice/Score: 5 per option; `workflow_state`
25 (and 10 `IDLE` utterances with PO, ASN or return vocabulary), `intent` 15, `complexity` 10,
`risk` 15, `domain` 75. Entities: 3 each and 10 two-entity questions within one domain.

## Running

```bash
python3 -m unittest scripts.test_tagging_gate_fixtures
python3 scripts/tagging_shadow_report.py --file traces.json \
  --expected pos-mcp-server/src/test/resources/eval/tagging-gate/en.json \
  --expected pos-mcp-server/src/test/resources/eval/tagging-gate/fr-CA.json \
  --expected pos-mcp-server/src/test/resources/eval/tagging-gate/es.json --verbose
```
