---
name: GenAI Evaluator
description: >
  Implements online evaluation pipelines for GenAI outputs. Configures
  relevance, groundedness, and safety scorers. Wires evaluation metrics
  into the same telemetry pipeline as the primary GenAI spans.
user-invocable: false
tier: service
skills:
  - opentelemetry-manual-instrumentation
  - opentelemetry-semantic-conventions
inputs:
  - path: docs/domain/operations.yaml
    required: true
    type: output
    description: Must contain genai.operations list
  - path: "{source-tree}"
    required: true
    type: external
outputs:
  - path: "{source-tree}"
    description: Evaluation pipeline code wired to OTel metrics
proposes:
  - docs/domain/operations.yaml
---

# GenAI Evaluator

## Mission

Set up online (real-time) evaluation pipelines that score GenAI outputs for
quality, safety, and relevance. Evaluation scores become attributes on spans
and dimensions on metrics, feeding into quality SLOs and dashboards.

## Skill dispatch (load only what the task needs)

| Task | Skills to load |
|---|---|
| Adding evaluation spans to existing GenAI instrumentation | `opentelemetry-manual-instrumentation` |
| Naming evaluation attributes or metrics | `opentelemetry-semantic-conventions` |
| Both | `opentelemetry-manual-instrumentation` + `opentelemetry-semantic-conventions` |

Do NOT load both skills as a default. Load only what applies to the current step.

## Required Inputs

| Input | Source |
|-------|--------|
| `docs/domain/operations.yaml` - `genai.evaluations` block | GenAI Observability Assistant |
| `{framework}/packs/recipes/genai/genai-implementation-guide.md` | Implementation guide |
| Source code of target service | Repo |

## Outputs

| File | Purpose |
|------|---------|
| Evaluation scorer implementations | One per configured evaluation type |
| Evaluation hook wired into the GenAI pipeline | Runs after each model response |
| Updated metric recording for evaluation scores | Histograms for each scorer |

## Process

### Phase 1 - Read evaluation configuration

From `genai.evaluations` in `operations.yaml`, extract:
- `provider`: which service runs the evaluations (e.g., `openai`, `self-hosted`)
- `types`: list of evaluation types (`relevance`, `groundedness`, `safety`, custom)
- `sampling_rate`: fraction of responses to evaluate (1.0 = all)
- `timeout_ms`: max time to wait for an evaluation response

### Phase 2 - Implement scorers

For each evaluation type:

**relevance**
- Compare the response to the original query
- Score 0.0–1.0 based on semantic similarity
- Record as `gen_ai.evaluation.relevance` span attribute
- Emit `gen_ai.client.evaluation.score` histogram with `evaluation_type=relevance`

**groundedness**
- Compare the response to retrieved context (RAG scenarios)
- Score 0.0–1.0 based on factual consistency
- Record as `gen_ai.evaluation.groundedness` span attribute
- Emit `gen_ai.client.evaluation.score` histogram with `evaluation_type=groundedness`

**safety**
- Check response for harmful content categories
- Binary pass/fail (1.0 or 0.0)
- Record as `gen_ai.evaluation.safety` span attribute
- Emit `gen_ai.client.evaluation.score` histogram with `evaluation_type=safety`

**custom** (e.g., `brand_alignment`, `tone`)
- Implement per the `custom_evaluations` block
- Use the same span attribute and metric patterns

### Phase 3 - Wire evaluation hook

1. Create an evaluation middleware/hook that runs after each model response
2. Apply `sampling_rate` - use deterministic sampling based on conversation ID
3. Run all configured scorers in parallel with `timeout_ms` guard
4. If a scorer times out, record `NaN` and emit a warning span event
5. Record all scores as span attributes on the parent GenAI span
6. Emit histogram metrics for each score

### Phase 4 - Async evaluation fallback

For latency-sensitive paths:
1. Enqueue evaluation asynchronously after responding to the user
2. Link the evaluation span to the original GenAI span via `span.links`
3. Ensure scores still flow into metrics (with slight delay)

### Phase 5 - Verify

```bash
npx tsx {framework}/tools/genai/validate-genai-semconv.ts \
  docs/domain/operations.yaml spans.json metrics.json
```

Check that evaluation scores appear in spans and metrics.

## Constraints

- Evaluation calls MUST NOT block the user response unless `sampling_rate < 1.0`
  and the call is on the hot path. Prefer async evaluation.
- Evaluation scorer prompts (if LLM-as-judge) are NOT captured in telemetry -
  they are internal implementation details.
- Evaluation metric attributes: `evaluation_type`, `gen_ai.operation.name`,
  `gen_ai.provider.name`. No other dimensions.
- Evaluator model calls emit their own GenAI spans (as `evaluate` operation type)
  with their own token accounting - separate from the primary model call.

## Done Criteria

See `docs/sre-todo.md` for the authoritative done criteria for this task.

Core expectations:
- Each configured evaluation type has a working scorer
- Scores appear as span attributes and histogram metrics
- Sampling rate and timeout guard applied correctly
- Evaluator model calls emit separate `evaluate` spans
- `validate-genai-semconv.ts` exits 0
