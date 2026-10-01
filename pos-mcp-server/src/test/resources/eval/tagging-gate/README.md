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
  It was derived mechanically by running `HeuristicQuestionTagger` (ADR-0068 wave 1, PR #2367, on
  main since 2026-10-01) over every text, not by hand. Re-derive it after a label or text edit, or if
  a heuristic rule changes, with `scripts/derive_tagging_hard_negatives.py` (it runs the tagger
  through `scripts/tagging_gate/HeuristicAnswers.java` against a build of the current
  `pos-mcp-server` checkout; it only reports by
  default, `--write` rewrites the fixtures, `--check` exits 1 on any difference). It uses
  `SimpleChatRuleDefaults.defaultCatalog()`: an environment with an edited simple-chat catalog
  derives different hard negatives.
  The two platform-event utterances added per language on 2026-09-30 (`en-0329`/`0330`,
  `fr-CA-0356`/`0357`, `es-0356`/`0357`) carry an empty list, confirmed by the derivation on 2026-10-01.
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
- Bare answers to a question the assistant asked, whether a confirmation ("yes", "go ahead",
  "confirm", "sure"), a selection ("the first one") or a decline or negated selection ("no, not that
  one", "not that one, the other one"): `intent` `UNKNOWN`, `risk` `HIGH`, `follows_previous_turn`
  true, `domain` `master`, `entity` empty. Rule (NLTI agent, 2026-10-01, matching the `risk` Score
  wording in `TaggingQuestions`): a bare answer resolves something the tagger cannot see (ADR-0068 §4,
  the request carries the message text only), so `risk` takes the conservative value, HIGH (§3.5 "risk
  never downgrades"; "when unsure between two levels, choose the higher"), while `intent` stays
  `UNKNOWN` because the message itself names no operation (the Choice criteria reserve `ACTION` for a
  message that does, "cancel it", "delete that one", which is labelled `ACTION` and by its own risk
  criteria). A decline is HIGH too: the polarity of the unseen question is unknown ("no" to "keep the
  old price?" approves a change) and "the other one" picks the target of an unseen action. The pending
  action's real risk still lives in the session; this label only guarantees the turn that may release
  it is never routed as a read.
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
- `risk`, strictly by the Score criteria (HIGH wording decided by the product owner, 2026-09-30:
  "money moves, a posting to accounting, a deletion, something sent outside the shop, granting or
  widening someone's access (an account, role or permission), writing an audit event, or any other
  change that cannot be undone"): `LOW` reading; `MEDIUM` a change that can be corrected later (note,
  appointment, draft, status, price or labor-rate change, adding a customer or ledger account,
  approving an estimate or a PO, creating a claim, a stock transfer or restock); `HIGH` money moves
  (payment, refund, store credit, paying a bill), a posting to accounting (journal entry, write-off,
  revaluation, count adjustments, finishing a bank reconciliation), a deletion, something sent outside
  the shop (email, text, campaign, submitting a PO to the vendor), any change to who can access the
  platform (granting a permission, assigning a role, creating, enabling, disabling or unlocking an
  account, resetting a password), writing an audit event (emitting a manual audit event), or a change
  that cannot be undone (void/revert an invoice, cancel a sales order, close a period, merge
  customers, terminate an employee). Access changes follow the merged Score wording (accounts, roles,
  permissions, passwords): the product owner's decision named granting or widening, and the wording
  in code is the broader, conservative reading the fixtures follow. Bare answers (confirmation,
  selection, decline) are `HIGH` by the Score wording's own sentence ("may approve a change you cannot
  see: rate it HIGH"), see the bare-answer rule above; social chat with nothing pending ("ok, see you
  tomorrow") is `simple_chat` and `LOW`. Do not copy the write-safety fixtures' labels.
- Audit events, product rule for the tool catalogue (not a label): a tool call may trigger an audit
  event as a side effect of the operation it performs, but the assistant never emits an audit event
  itself. "Emit a manual audit event" is labelled as the user asked it (`ACTION`, `HIGH`, `admin`); the
  catalogue must not offer the assistant a tool whose purpose is to write one.
- `domain` is the RAG-scope vocabulary; entities with no scope of their own map by the `domains:`
  sentences in `entities.yaml`, with the four sentences restated by the product owner on 2026-09-30:
  `hr` = "Employees as staff: employment, pay rates, time off, HR functions."; `shopmanager` = "Shop
  operations: appointments, bays, day-to-day technician scheduling and availability, shop settings.";
  `events` = "Platform events published by the pos-event modules: event types, event history and
  notifications."; `admin` = "Administration of the platform: users, roles, permissions, access, and
  the audit log." Mapping: vehicle and campaign → `customer`; invoice, payment, credit memo, vendor
  bill, vendor spend and balances → `accounting` (receivables and payables; there is no invoice scope);
  purchase order, ASN, supplier → `inventory`; location, bays, appointments, store hours and address,
  and any staff scheduling or availability question (who is on shift, when a shift starts or ends, a
  technician's availability on a day, who is assigned to a bay, the technicians' work schedule, the
  store manager's schedule) → `shopmanager`; employment and HR functions (who holds a position, an
  employee record, headcount, part-time status as the thing asked, pay, vacation balance, time off,
  certifications, termination, an overtime audit) → `hr`; user, role, permission, access, and every
  audit-log question (the platform audit log, the assistant's audit events, emitting an audit event,
  who changed a role) → `admin`; sign-in, tokens, password policy → `security`; platform events and
  notifications themselves (event types, event history, whether an alert or reminder went out, failed
  notifications, what fired overnight) → `events`, and nothing else; a business record's activity
  history ("what happened to WO-2024-0012", "the activity log for work order ...") is the record's own
  domain (`workorder`, `order`, `customer`...), not `events`: the user wants the record's story, and
  the record's module owns its history. `master` for a bare follow-up, a compound question spanning
  two areas, and the assistant or platform in general.
- `entity`: lexicon keys named or clearly referred to; the audit log, a period and a metric are not
  entities.
- fr-CA and es keep deliberate anglicisms that real shop talk uses and the English keyword heuristics
  trip on (PO, purchase orders, sales tax, online, web, store, location, part, part time, access, bay;
  "po" as pouces). Each is marked in `notes`. The native reviewer may replace one, but replacing it
  removes a hard negative; add a locale-term twin instead of rewording.
- When a relabel to true (or non-`IDLE`) makes a `hard_negative_for` entry a positive, the entry is
  removed (the heuristic was right); `hard_negative_for` is otherwise never edited by hand.

Decided by the product owner on 2026-09-30 and applied above: granting or widening access is `HIGH`;
writing an audit event is `HIGH`; the platform's login policy is `admin_account_question: true`; staff
scheduling and availability are `shopmanager`, employment, pay and time off are `hr`; `events` is only
the pos-event modules and every audit question is `admin`. The `TaggingQuestions` wording (risk HIGH
criteria and the four domain sentences) must be updated to the same text before a shadow run is scored
against these fixtures; this README is the ground truth until it is.

Decided by the NLTI domain agent on 2026-10-01 and applied above: the risk of a bare answer
(confirmation, selection, decline) is `HIGH`, not `LOW`, so that the fixtures agree with the `risk`
Score wording now on main ("may approve a change you cannot see: rate it HIGH"); `intent` stays
`UNKNOWN`. Seven utterances per language were relabelled (`*-0018`, `0019`, `0020`, `0021`, `0029`,
`0034`, `0035`). `hard_negative_for` is unaffected: it covers the eight Nouls and `workflow_state`
only (`HARD_NEGATIVE_TAGS` in `scripts/derive_tagging_hard_negatives.py`), never `risk` or `intent`.

Open questions, labelled per the wording above until decided: whether a scheduling target date ("book
on Thursday at 9") should imply a date window (labelled false); and the native review of the fr-CA
and es translations (`reviewed: false` until a native reader sets `reviewed: true` and `reviewer`).

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

## Bake-off procedure (ADR-0068 section 6, spec 2.9)

Chooses the tagging model before any tag is promoted. Run it on the host that will serve the model
(the GPU-less alpha cell), one candidate at a time (`tev1:0.8b`, `nimble`, `tev1`), and choose the
smallest model whose p95 fits the 800 ms budget and whose accuracy meets the promotion rule in
en, fr-CA and es.

### Prerequisites

- `pos-mcp-server` on the `alpha` profile (the chat path and the turn-trace recorder exist only
  there) with `mcp.eval.turn-trace.enabled` on (`MCP_EVAL_TURN_TRACE_ENABLED`, default `true` on
  alpha). Traces expire after `MCP_EVAL_TURN_TRACE_RETENTION` (24h): export each batch the day it
  runs.
- In `.env`: `MCP_TAGGING_MODE=shadow`; `OLLAMA_TAGGING_MODEL=<candidate>`. Compose passes that one
  variable to `ollama-init` (the pull) and to `pos-mcp-server` (`MCP_TAGGING_MODEL`); outside Compose
  set `MCP_TAGGING_MODEL` to the same name. `MCP_TAGGING_ENTITY_QUESTIONS=false` for a model whose
  context cannot hold the wide request (the entity Nouls make it ~18 KB, about 4.6k tokens; `tev1`
  reads about 2,000), `true` for one that can. Record which setting each candidate ran with: it
  changes `questionCount` and `optionListHash` on the trace. Leave `MCP_TAGGING_TIMEOUT` at `800ms`.
- One bearer token for an actor holding `mcp:eval_trace:view` and chat access, used for every turn
  and every export: `GET /v1/eval/turn-traces` returns the caller's own traces only. Do not chat as
  that actor from anywhere else during a run.

### Pull or swap a model

```bash
# .env: MCP_TAGGING_MODE=shadow, OLLAMA_TAGGING_MODEL=tev1
docker compose run --rm ollama-init                     # pulls bge-m3 and the candidate
docker exec ollama ollama list                          # the candidate is present
docker exec ollama ollama stop tev1:0.8b                # unload the previous candidate, if any
docker compose up -d --force-recreate pos-mcp-server    # picks up MCP_TAGGING_MODEL
```

`OLLAMA_MAX_LOADED_MODELS=3` keeps the embedding model and a candidate resident together (and a
second candidate during a swap). Warm both models with a few chat turns that are not gate
utterances, then check `docker exec ollama ollama ps` lists `bge-m3` and the candidate.

### Run the gate in batches

`GET /v1/eval/turn-traces` returns at most 200 traces, newest first, with no cursor, and a gate
set has 328 (en) or 355 (fr-CA, es) utterances. Send each language in batches of fewer than 200
turns (150 below), export each batch right after it, and check the export is short of the cap:

```bash
export MCP_CHAT_URL=http://localhost:18086/mcp-server/v1/mcp/chat   # scripts/analytics_gate_run.py default
export MCP_BEARER_TOKEN=...                                          # the actor above
GATE=pos-mcp-server/src/test/resources/eval/tagging-gate
MODEL=tev1 LANG_FILE=en START=0 END=150 BATCH=1
since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
jq -c ".utterances[$START:$END][] | {message: .text}" "$GATE/$LANG_FILE.json" |
  while read -r body; do                         # no conversationId: every turn is a fresh conversation
    curl -sS --fail -o /dev/null -X POST "$MCP_CHAT_URL" -H "Authorization: Bearer $MCP_BEARER_TOKEN" \
      -H "X-API-Version: 1" -H "Content-Type: application/json" -d "$body" || echo "turn failed: $body" >&2
  done
curl -sS -G "${MCP_CHAT_URL%/v1/mcp/chat}/v1/eval/turn-traces" --data-urlencode "since=$since" \
  --data-urlencode "limit=200" -H "Authorization: Bearer $MCP_BEARER_TOKEN" -H "X-API-Version: 1" \
  > "traces-$MODEL-$LANG_FILE-$BATCH.json"
jq length "traces-$MODEL-$LANG_FILE-$BATCH.json"   # 200 means turns were cut off: rerun smaller
```

Repeat with `START=150 END=300 BATCH=2` and `START=300 END=400 BATCH=3` for each language. Overlapping
exports are safe: the report keeps a `turnId` once. Rerunning a batch after a capped export:
delete the truncated export first (the rerun's turns get new `turnId`s; keeping both scores them twice).

### Report

One report per language and candidate, all of that language's batch exports at once:

```bash
python3 scripts/tagging_shadow_report.py --file traces-tev1-en-*.json --expected "$GATE/en.json" --verbose
python3 scripts/tagging_shadow_report.py --file traces-tev1-fr-CA-*.json --expected "$GATE/fr-CA.json"
python3 scripts/tagging_shadow_report.py --file traces-tev1-es-*.json --expected "$GATE/es.json"
```

Every utterance should join a trace (`utterances without a trace=0`); the report skips turns
recorded in mode OFF and turns without a `tags` block.

### Measure

- **Latency:** `p50ms` / `p95ms` in the report are over every provider call, fallbacks included
  (a timeout pays the whole budget, so a model that often times out cannot look fast), warm, with
  the embedding model loaded beside it; this is the §6 latency. `ok p95` (answered turns only),
  `fb p50` / `fb p95` (fallback turns only) and the fallback rate by reason are diagnostics. The NLTI overview dashboard's "Tagging latency" panel
  shows the same calls live.
- **Resident memory:** with both models warm (after a batch, `ollama ps` listing both), read the
  `ollama` container's working set from the NLTI overview dashboard ("ollama container memory",
  cAdvisor `container_memory_working_set_bytes{name="ollama"}`) or `docker stats --no-stream
  ollama`, and the candidate's own `SIZE` from `docker exec ollama ollama ps`. The candidate's
  resident memory is the working set minus the embedding-only baseline (measured once with the
  candidate stopped: `ollama stop <candidate>`); `ollama ps` should agree within a few hundred MB.
- **Accuracy:** per tag and language, the heuristic's accuracy against the model-plus-fallback
  accuracy at each threshold, from the ground-truth section of the report.

### Record

Record each candidate in the ADR-0068 Changelog (durion repo,
`docs/adr/0068-mcp-pre-llm-question-tagging-decision-model.adr.md`): model, host, the
`MCP_TAGGING_ENTITY_QUESTIONS` setting, p50/p95 and fallback rate by reason, resident memory,
per-tag accuracy against the heuristic in en, fr-CA and es (and whether fr-CA and es were
native-reviewed yet), the chosen model and the thresholds the shadow data supports.
