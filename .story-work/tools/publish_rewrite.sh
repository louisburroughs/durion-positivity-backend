#!/usr/bin/env bash
set -euo pipefail

REPO="${REPO:-louisburroughs/durion-positivity-backend}"

ISSUE=""
AFTER_FILE=""
INCLUDE_RECOMMENDED=0
FAIL_ON_BLOCKING=0
DRY_RUN=0
AUTO_CREATE_CLARIFICATION=1  # default ON; can disable with --no-clarification

usage() {
  cat <<EOF
Usage:
  $0 [-i ISSUE_NUMBER] -f PATH_TO_AFTER_MD [--include-recommended] [--fail-on-blocking] [--dry-run] [--no-clarification]

What it does:
  1) Validates the rewritten markdown (sections + labels block + original story preservation marker)
  2) Auto-detects ISSUE_NUMBER if -i is omitted (from file path like .../work/<num>/after.md or <num>.md)
  3) Applies labels from "## 🏷️ Labels (Proposed)" using apply_labels_from_rewrite.sh
  4) Updates the GitHub issue body with the rewritten markdown
  5) If STOP indicates clarification is required, auto-creates a [CLARIFICATION] issue and links it

Env:
  REPO=OWNER/REPO  (default: louisburroughs/durion-positivity-backend)

Exit codes:
  2 usage / missing args
  3 validation failed
  4 label parsing/apply failed
  5 refused due to blocking (when --fail-on-blocking)
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -i) ISSUE="$2"; shift 2 ;;
    -f) AFTER_FILE="$2"; shift 2 ;;
    --include-recommended) INCLUDE_RECOMMENDED=1; shift ;;
    --fail-on-blocking) FAIL_ON_BLOCKING=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    --no-clarification) AUTO_CREATE_CLARIFICATION=0; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; usage; exit 2 ;;
  esac
done

if [[ -z "$AFTER_FILE" ]]; then
  echo "ERROR: -f is required." >&2
  usage
  exit 2
fi

if [[ ! -f "$AFTER_FILE" ]]; then
  echo "ERROR: File not found: $AFTER_FILE" >&2
  exit 2
fi

# ---------------------------
# Auto-detect issue number if omitted
# ---------------------------
if [[ -z "$ISSUE" ]]; then
  # Try .../work/<num>/after.md
  if [[ "$AFTER_FILE" =~ /work/([0-9]+)/after\.md$ ]]; then
    ISSUE="${BASH_REMATCH[1]}"
  else
    # Try basename like 123.md or after-123.md
    base="$(basename "$AFTER_FILE")"
    if [[ "$base" =~ ^([0-9]+)\.md$ ]]; then
      ISSUE="${BASH_REMATCH[1]}"
    elif [[ "$base" =~ ([0-9]+) ]]; then
      ISSUE="${BASH_REMATCH[1]}"
    fi
  fi
fi

if [[ -z "$ISSUE" ]]; then
  echo "ERROR: Could not auto-detect issue number. Provide -i ISSUE_NUMBER." >&2
  exit 2
fi

# ---------------------------
# 1) Validate rewritten markdown
# ---------------------------
python3 - <<'PY' "$AFTER_FILE"
import re, sys, pathlib

p = pathlib.Path(sys.argv[1])
t = p.read_text(encoding="utf-8", errors="replace")

errors = []

def require(pattern, desc):
  if not re.search(pattern, t, re.MULTILINE):
    errors.append(desc)

require(r"^##\s*🏷️\s*Labels\s*\(Proposed\)\s*$", "Missing: '## 🏷️ Labels (Proposed)'")
require(r"^###\s*Required\s*$", "Missing: '### Required' under Labels (Proposed)")
require(r"^###\s*Recommended\s*$", "Missing: '### Recommended' under Labels (Proposed)")
require(r"^###\s*Blocking\s*/\s*Risk\s*$", "Missing: '### Blocking / Risk' under Labels (Proposed)")
require(r"^\*\*Rewrite Variant:\*\*\s*\S+", "Missing: '**Rewrite Variant:** <value>'")

required_sections = [
  "Story Intent",
  "Actors & Stakeholders",
  "Preconditions",
  "Functional Behavior",
  "Alternate / Error Flows",
  "Business Rules",
  "Data Requirements",
  "Acceptance Criteria",
  "Audit & Observability",
  "Original Story (Unmodified – For Traceability)",
]
for s in required_sections:
  require(rf"^##\s*{re.escape(s)}\s*$", f"Missing section: '## {s}'")

m = re.search(r"^##\s*Original Story \(Unmodified – For Traceability\)\s*$", t, re.MULTILINE)
if m:
  tail = t[m.end():].strip()
  if len(tail) < 20:
    errors.append("Original Story section seems empty (expected verbatim original story).")

mreq = re.search(r"^###\s*Required\s*$([\s\S]*?)(^###\s*Recommended\s*$)", t, re.MULTILINE)
if mreq:
  block = mreq.group(1)
  if "type:story" not in block and "type:clarification" not in block:
    errors.append("Required labels must include 'type:story' (or 'type:clarification').")
else:
  errors.append("Could not parse Required labels block.")

if errors:
  print("VALIDATION FAILED:")
  for e in errors:
    print(f"- {e}")
  sys.exit(3)

print("Validation OK")
PY

# ---------------------------
# Helper: read proposed labels (domain + blocking)
# ---------------------------
LABEL_JSON="$(python3 - "$AFTER_FILE" <<'PY'
import json, re, sys, pathlib

path = pathlib.Path(sys.argv[1])
text = path.read_text(encoding="utf-8", errors="replace")

m = re.search(r"^##\s*🏷️\s*Labels\s*\(Proposed\)\s*$", text, re.MULTILINE)
if not m:
  print(json.dumps({"error":"Missing '## 🏷️ Labels (Proposed)' section"}))
  raise SystemExit(3)

start = m.start()
rest = text[start:]
m2 = re.search(r"^\#\#\s+(?!🏷️\s*Labels\s*\(Proposed\)).+$", rest, re.MULTILINE)
labels_block = rest if not m2 else rest[:m2.start()]

def extract_list(section_title: str):
  s = re.search(rf"^###\s*{re.escape(section_title)}\s*$", labels_block, re.MULTILINE)
  if not s:
    return []
  tail = labels_block[s.end():]
  nxt = re.search(r"^###\s+.+$", tail, re.MULTILINE)
  chunk = tail if not nxt else tail[:nxt.start()]
  labels = []
  for line in chunk.splitlines():
    line = line.strip()
    if line.startswith("- "):
      lab = line[2:].strip()
      if lab:
        labels.append(lab)
  return labels

required = extract_list("Required")
recommended = extract_list("Recommended")
blocking = extract_list("Blocking / Risk")
blocking_norm = [x for x in blocking if x.lower() != "none"]

domain = None
for l in required + recommended + blocking_norm:
  if l.startswith("domain:"):
    domain = l
    break

print(json.dumps({
  "required": required,
  "recommended": recommended,
  "blocking_risk": blocking_norm,
  "domain": domain
}))
PY
)"


if echo "$LABEL_JSON" | python3 -c 'import sys,json; j=json.load(sys.stdin); sys.exit(0 if "error" not in j else 4)'; then
  :
else
  echo "ERROR parsing labels: $LABEL_JSON" >&2
  exit 4
fi

DOMAIN_LABEL="$(echo "$LABEL_JSON" | python3 -c 'import sys,json; j=json.load(sys.stdin); print(j.get("domain") or "")')"
BLOCKING_LABELS="$(echo "$LABEL_JSON" | python3 -c 'import sys,json; j=json.load(sys.stdin); print("\n".join(j.get("blocking_risk") or []))')"

# ---------------------------
# 2) Apply labels from rewrite
# ---------------------------
APPLY_ARGS=( -r "$REPO" -i "$ISSUE" -f "$AFTER_FILE" )
if [[ "$INCLUDE_RECOMMENDED" -eq 1 ]]; then
  APPLY_ARGS+=( --include-recommended )
fi
if [[ "$FAIL_ON_BLOCKING" -eq 1 ]]; then
  APPLY_ARGS+=( --fail-on-blocking )
fi

if [[ "$DRY_RUN" -eq 1 ]]; then
  echo "DRY RUN: would apply labels via:"
  echo "  ./.story-work/tools/apply_labels_from_rewrite.sh ${APPLY_ARGS[*]}"
else
  ./.story-work/tools/apply_labels_from_rewrite.sh "${APPLY_ARGS[@]}"
fi

# ---------------------------
# 3) Update issue body
# ---------------------------
if [[ "$DRY_RUN" -eq 1 ]]; then
  echo "DRY RUN: would update issue body:"
  echo "  gh issue edit $ISSUE -R $REPO --body-file $AFTER_FILE"
else
  gh issue edit "$ISSUE" -R "$REPO" --body-file "$AFTER_FILE" >/dev/null
  echo "Updated issue body: $REPO#$ISSUE"
fi

# ---------------------------
# 4) Auto-create clarification issue (if STOP: Clarification required...)
# ---------------------------
STOP_CLAR="$(grep -E '^STOP:\s*Clarification required before finalization' "$AFTER_FILE" || true)"
STOP_CONFLICT="$(grep -E '^STOP:\s*Conflicting domain guidance detected' "$AFTER_FILE" || true)"

# Only auto-create for clarification STOP (not domain-conflict STOP)
if [[ "$AUTO_CREATE_CLARIFICATION" -eq 1 && -n "$STOP_CLAR" ]]; then
  if [[ -z "$DOMAIN_LABEL" ]]; then
    echo "WARN: Clarification needed but no domain label was proposed; defaulting to clarification:domain without domain:* label." >&2
  fi

  # Pull origin title/url for template
  ORIGIN_TITLE="$(gh issue view "$ISSUE" -R "$REPO" --json title -q '.title' || echo "")"
  ORIGIN_URL="https://github.com/${REPO}/issues/${ISSUE}"

  # Extract Open Questions block (best-effort)
OPEN_QUESTIONS="$(python3 - "$AFTER_FILE" <<'PY'
import re, sys, pathlib
t = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace")
m = re.search(r"^##\s*Open Questions\s*$", t, re.MULTILINE)
if not m:
  print("")
  raise SystemExit(0)
tail = t[m.end():]
nxt = re.search(r"^##\s+.+$", tail, re.MULTILINE)
chunk = tail if not nxt else tail[:nxt.start()]
lines = [ln.rstrip() for ln in chunk.splitlines()]
lines = [ln for ln in lines if ln.strip()]
print("\n".join(lines).strip())
PY
)"

# Guard: ensure AFTER_FILE is readable (and never executed)
if [[ ! -r "$AFTER_FILE" ]]; then
  echo "ERROR: after.md is not readable: $AFTER_FILE" >&2
  ls -l "$AFTER_FILE" >&2 || true
  exit 2
fi

AFTER_FILE="$(python3 -c 'import os,sys; print(os.path.abspath(sys.argv[1]))' "$AFTER_FILE")"


  # Heuristic for clarification:* label
  CLAR_LABEL="clarification:domain"
  lower_all="$( (echo "$ORIGIN_TITLE"; echo "$OPEN_QUESTIONS") | tr '[:upper:]' '[:lower:]' )"

  if [[ "$DOMAIN_LABEL" == "domain:positivity" ]]; then
    CLAR_LABEL="clarification:integration"
  elif [[ "$DOMAIN_LABEL" == "domain:security" || "$DOMAIN_LABEL" == "domain:audit" ]]; then
    CLAR_LABEL="clarification:security"
  elif echo "$lower_all" | grep -qE '\b(state|workflow|transition|reopen|lifecycle)\b'; then
    CLAR_LABEL="clarification:workflow"
  elif echo "$lower_all" | grep -qE '\b(field|attribute|required data|schema|payload)\b'; then
    CLAR_LABEL="clarification:data"
  elif [[ "$DOMAIN_LABEL" == "domain:accounting" || "$DOMAIN_LABEL" == "domain:pricing" || "$DOMAIN_LABEL" == "domain:billing" ]]; then
    CLAR_LABEL="clarification:policy"
  fi

  # Prevent duplicates: look for an open clarification with "Origin #<ISSUE>"
  # (This relies on our standard title pattern below.)
  EXISTING="$(gh issue list -R "$REPO" --state open --label "type:clarification" --search "in:title Origin #$ISSUE" --json number -q '.[0].number' 2>/dev/null || true)"

  if [[ -n "$EXISTING" ]]; then
    CLAR_URL="https://github.com/${REPO}/issues/${EXISTING}"
    echo "Clarification already exists: $CLAR_URL"
  else
    RUN_ID="$(python3 - <<'PY'
import uuid, time
# UUIDv7 not in stdlib; use uuid4 as a pragmatic fallback for now.
print(str(uuid.uuid4()))
PY
)"
    ISO_DATE="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"

    # Build clarification body (agent template)
    CLAR_BODY="$(cat <<EOF
# Clarification Request

> **Purpose:** Resolve missing information needed to finalize the originating user story for development and testing.  
> **Do not implement here.** Provide decisions/answers; the Story Authoring Agent will update the story.

## Origin
- **Origin Repository:** ${REPO}
- **Origin Story Issue:** ${ORIGIN_URL}
- **Origin Story Title:** ${ORIGIN_TITLE}
- **Requested By Agent:** agent:story-authoring (runId: ${RUN_ID})
- **Date:** ${ISO_DATE}

## Domain Routing
- **Domain:** ${DOMAIN_LABEL:-"(missing)"}
- **Clarification Type:** ${CLAR_LABEL}

## Summary
The story rewrite identified missing or ambiguous information that cannot be safely inferred. Answers are required to finalize acceptance criteria and prevent incorrect implementation.

## Questions (Answer Required)
${OPEN_QUESTIONS:-"(No Open Questions section was found in after.md; please review the story body for missing specifics.)"}

## Why This Matters
Proceeding without these answers would require guessing business rules or system authority boundaries.

## Impact If Unanswered
- Development would require unsafe assumptions
- Test cases cannot be derived reliably
- Auditability and integrations may be incorrect

## Relevant Context (From Story)
- Origin Story: ${ORIGIN_URL}
- Please review the **Open Questions** section in the origin story body.

## Resolution Acceptance Criteria
A response is considered complete when it:
- Answers each numbered question explicitly
- States which system/domain is authoritative where applicable
- Provides any required state transitions, permissions, or data fields

---
### Agent Instructions (Do Not Edit)
- When this issue is answered, the Story Authoring Agent MUST:
  - Update the origin story with the decision(s)
  - Remove \`blocked:clarification\` from the origin story
  - Set \`status:needs-review\` (or \`status:ready-for-dev\` if no open questions remain)
EOF
)"

    CLAR_TITLE="[CLARIFICATION] Origin #${ISSUE}: ${ORIGIN_TITLE}"
    echo "Creating clarification issue: $CLAR_TITLE"

    if [[ "$DRY_RUN" -eq 1 ]]; then
      echo "DRY RUN: would create clarification with labels:"
      echo "  type:clarification"
      echo "  blocked:clarification"
      [[ -n "$DOMAIN_LABEL" ]] && echo "  $DOMAIN_LABEL"
      echo "  $CLAR_LABEL"
      echo "  agent:story-authoring"
    else
      # Create issue
      CREATE_ARGS=(--repo "$REPO" --title "$CLAR_TITLE" --body "$CLAR_BODY"
                  --label "type:clarification" --label "blocked:clarification"
                  --label "$CLAR_LABEL" --label "agent:story-authoring")

      if [[ -n "$DOMAIN_LABEL" ]]; then
        CREATE_ARGS+=( --label "$DOMAIN_LABEL" )
      fi

      CLAR_URL="$(gh issue create "${CREATE_ARGS[@]}")"
      echo "Created clarification: $CLAR_URL"
    fi
  fi

  # Link + block origin story (if not already)
  if [[ "$DRY_RUN" -eq 1 ]]; then
    echo "DRY RUN: would ensure origin story has blocked:clarification and comment link."
  else
    # Ensure origin is blocked and has a comment linking the clarification
    gh issue edit "$ISSUE" -R "$REPO" --add-label "blocked:clarification" >/dev/null || true
    if [[ -n "${CLAR_URL:-}" ]]; then
      gh issue comment "$ISSUE" -R "$REPO" --body "Auto-created clarification issue: ${CLAR_URL}" >/dev/null
    fi
  fi
fi

# If it’s a domain conflict STOP, just comment (no auto-create here)
if [[ -n "$STOP_CONFLICT" ]]; then
  MSG="Rewrite published with STOP condition:\n\n\`\`\`\n$STOP_CONFLICT\n\`\`\`\n\nPlease resolve the **⚠️ Domain Conflict Summary** and **Open Questions** sections."
  if [[ "$DRY_RUN" -eq 1 ]]; then
    echo "DRY RUN: would comment domain conflict notice."
  else
    gh issue comment "$ISSUE" -R "$REPO" --body "$MSG" >/dev/null
  fi
fi

echo "Publish complete: $REPO#$ISSUE"
