#!/usr/bin/env bash
set -euo pipefail

REPO="${REPO:-louisburroughs/durion-positivity-backend}"
ISSUE="${1:-}"

if [[ -z "$ISSUE" ]]; then
  echo "Usage: $0 <issue-number>" >&2
  exit 2
fi

ROOT=".story-work/work/${ISSUE}"
mkdir -p "$ROOT"

# Pull issue JSON
gh issue view "$ISSUE" -R "$REPO" --json number,title,body,labels > "$ROOT/issue.json"

TITLE="$(jq -r '.title' "$ROOT/issue.json")"
BODY="$(jq -r '.body'  "$ROOT/issue.json")"
LABELS="$(jq -r '.labels[].name' "$ROOT/issue.json" 2>/dev/null || true)"

# Write BEFORE
cat > "$ROOT/before.md" <<EOF
# Issue #$ISSUE — $TITLE

## Current Labels
$(echo "$LABELS" | sed 's/^/- /' || true)

## Current Body
$BODY
EOF

# Write AFTER stub (so Copilot writes in the right format)
cat > "$ROOT/after.md" <<'EOF'
## 🏷️ Labels (Proposed)

### Required
- type:story
- domain:<infer>
- status:draft

### Recommended
- agent:<domain>
- agent:story-authoring

### Blocking / Risk
- none

**Rewrite Variant:** <infer>

---

## Story Intent

## Actors & Stakeholders

## Preconditions

## Functional Behavior

## Alternate / Error Flows

## Business Rules

## Data Requirements

## Acceptance Criteria

## Audit & Observability

## Open Questions

## Original Story (Unmodified – For Traceability)
EOF

echo "$ROOT"
