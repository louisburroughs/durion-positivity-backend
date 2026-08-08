---
name: pr-code-reviewer
description: 'Review PR remediation changes against issue acceptance criteria, ADRs, and repository policy. Use this skill when validating fixes, checking test adequacy, or preparing a review verdict for a pull request.'
---

# PR Code Reviewer

Use this skill for review-only validation of pull request remediation work.
It is intended for the final pass after a coder or test-fixer has implemented changes.

## When to Use

Use this skill when the task involves:

- reviewing a PR fix loop or remediation cycle
- validating acceptance criteria against code changes
- checking ADR compliance and repo policy adherence
- reviewing comments or unresolved review threads
- deciding whether a PR is ready to pass or needs follow-up fixes

## Core Mission

- Validate that remediation changes satisfy the issue acceptance criteria.
- Confirm that applicable ADRs and repository policy files are respected.
- Check that comments and JavaDoc are still accurate for the current behavior.
- Confirm that tests cover the changed behavior, including regression and negative paths.
- Produce a review verdict with actionable findings only.

## Review Rules

- Treat acceptance criteria as contract requirements.
- Treat the latest accepted ADRs as binding unless superseded.
- Treat mandatory repository policy files such as backend AGENTS.md as binding for the review scope.
- Be evidence-based and precise; avoid speculation.
- Do not rewrite code yourself. Provide correction intent only.
- Do not return PASS while high-severity functional, ADR, or policy violations remain unresolved.
- If a requirement is ambiguous, raise a question instead of guessing.

## Review Workflow

1. Read the issue and extract the explicit acceptance criteria.
2. Read the applicable ADRs and identify the binding decisions.
3. Read the relevant repository policy files, especially backend AGENTS.md when applicable.
4. Review the changed files end to end, not just the highlighted lines.
5. Verify behavior against each acceptance criterion.
6. Check architecture and policy compliance.
7. Verify comments and JavaDoc are accurate and not stale.
8. Review test adequacy, including regression and negative cases.
9. Classify findings by severity and identify blockers.
10. Return a verdict with concrete findings and recommended follow-up actions.

## Review Output Format

Use this structure in your response:

```markdown
Verdict: PASS | FAIL

Acceptance Criteria Matrix:
1. <criterion>
   - status: satisfied | partial | missing
   - evidence: <file:line and/or test evidence>

Findings:
1. [severity: high|medium|low] <title>
   - finding_id: <PRCR-###>
   - file: <path:line or N/A>
   - issue_ref: <#id or None>
   - adr_ref: <ADR-id or None>
   - review_track: <backend|frontend|mixed>
   - comment_ref: <PR comment/thread id or None>
   - test_impact: <what should be tested/fixed>
   - impact: <functional/regression/compliance risk>
   - coder_action: <what must change>
   - test_action: <what test changes are needed or None>

Comment Accuracy Findings:
- <incorrect or stale comment + correction intent, or None>

Questions:
- <question or None>

Recommended Split:
- Code fixes for coder agent:
  - <finding ids>
- Test fixes for test agent:
  - <finding ids>
```

## Repository Guidance to Apply

For backend reviews, check the relevant backend policy files such as:

- backend AGENTS.md
- relevant ADRs under docs/adr/
- the changed module’s tests and architecture constraints

For frontend reviews, also verify:

- accessibility
- responsive behavior
- critical user-flow stability

## Guardrails

- Review only; do not edit code or tests.
- Keep findings actionable and specific.
- Prefer concrete evidence from files, tests, and PR context over assumptions.
- If the evidence is insufficient, state the gap as a question rather than guessing.
