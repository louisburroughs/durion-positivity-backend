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

- `about_orders`: a sales order, cart or purchase order. Revenue totals, sales tax and work orders
  are not.
- `admin_account_question`: platform users, logins, roles, permissions, access reviews, the audit
  log. A customer's, supplier's, bank or GL account is not; nor is an inventory or safety audit.
- `workflow_state`: not `IDLE` only when the user is carrying out the workflow now. Questions about
  POs, shipments, counts and returns are `IDLE`.
- A bare answer or confirmation is `follows_previous_turn` and not `simple_chat`; `intent` `UNKNOWN`.
- `risk`: `LOW` reading; `MEDIUM` a correctable change (note, appointment, draft, status); `HIGH`
  money movement, a posting, a deletion, something sent outside the shop.
- `domain` is `master` when no single area fits (for example a bare pronoun follow-up).

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
