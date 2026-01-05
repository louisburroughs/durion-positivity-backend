#!/usr/bin/env bash
set -euo pipefail

REPO=""
ISSUE=""
FILE=""
INCLUDE_RECOMMENDED=0
FAIL_ON_BLOCKING=0

usage() {
  cat <<EOF
Usage:
  $0 -r OWNER/REPO -i ISSUE_NUMBER -f REWRITTEN_MD [--include-recommended] [--fail-on-blocking]

Behavior:
  - Applies labels from the "## 🏷️ Labels (Proposed)" block in the rewritten markdown.
  - By default applies:
      * Required labels
      * Blocking/Risk labels (unless "none")
  - With --include-recommended, also applies Recommended labels.
  - With --fail-on-blocking, aborts if Blocking/Risk labels exist (and are not "none").
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -r) REPO="$2"; shift 2 ;;
    -i) ISSUE="$2"; shift 2 ;;
    -f) FILE="$2"; shift 2 ;;
    --include-recommended) INCLUDE_RECOMMENDED=1; shift ;;
    --fail-on-blocking) FAIL_ON_BLOCKING=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; usage; exit 2 ;;
  esac
done

if [[ -z "$REPO" || -z "$ISSUE" || -z "$FILE" ]]; then
  echo "ERROR: -r, -i, and -f are required." >&2
  usage
  exit 2
fi

if [[ ! -f "$FILE" ]]; then
  echo "ERROR: File not found: $FILE" >&2
  exit 2
fi

if [[ ! -r "$FILE" ]]; then
  echo "ERROR: File not readable: $FILE" >&2
  ls -l "$FILE" >&2 || true
  exit 2
fi

# Parse labels using python (safe argv passing)
LABEL_JSON="$(python3 - "$FILE" <<'PY'
import json, re, sys, pathlib

path = pathlib.Path(sys.argv[1])
text = path.read_text(encoding="utf-8", errors="replace")

# Find the proposed labels section
m = re.search(r"^##\s*🏷️\s*Labels\s*\(Proposed\)\s*$", text, re.MULTILINE)
if not m:
  print(json.dumps({"error":"Missing '## 🏷️ Labels (Proposed)' section"}))
  raise SystemExit(3)

# Slice from labels header to next H2 or end
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

# Normalize "none"
blocking_norm = [x for x in blocking if x.lower() != "none"]

print(json.dumps({
  "required": required,
  "recommended": recommended,
  "blocking_risk": blocking_norm,
}))
PY
)"

# Fail fast if parse error
if echo "$LABEL_JSON" | python3 -c 'import sys,json; j=json.load(sys.stdin); sys.exit(0 if "error" not in j else 4)'; then
  :
else
  echo "ERROR parsing labels: $LABEL_JSON" >&2
  exit 4
fi

REQUIRED_LABELS="$(echo "$LABEL_JSON" | python3 -c 'import sys,json; j=json.load(sys.stdin); print("\n".join(j["required"]))')"
RECOMMENDED_LABELS="$(echo "$LABEL_JSON" | python3 -c 'import sys,json; j=json.load(sys.stdin); print("\n".join(j["recommended"]))')"
BLOCKING_LABELS="$(echo "$LABEL_JSON" | python3 -c 'import sys,json; j=json.load(sys.stdin); print("\n".join(j["blocking_risk"]))')"

if [[ "$FAIL_ON_BLOCKING" -eq 1 && -n "$BLOCKING_LABELS" ]]; then
  echo "ERROR: Blocking/Risk labels present; refusing to auto-apply:" >&2
  echo "$BLOCKING_LABELS" | sed 's/^/  - /' >&2
  exit 5
fi

# Build final label set to apply
LABELS_TO_APPLY=()

while IFS= read -r l; do [[ -n "$l" ]] && LABELS_TO_APPLY+=("$l"); done <<< "$REQUIRED_LABELS"

if [[ "$INCLUDE_RECOMMENDED" -eq 1 ]]; then
  while IFS= read -r l; do [[ -n "$l" ]] && LABELS_TO_APPLY+=("$l"); done <<< "$RECOMMENDED_LABELS"
fi

while IFS= read -r l; do [[ -n "$l" ]] && LABELS_TO_APPLY+=("$l"); done <<< "$BLOCKING_LABELS"

# De-dupe labels
mapfile -t LABELS_TO_APPLY < <(python3 - <<'PY' "${LABELS_TO_APPLY[@]}"
import sys
labels = sys.argv[1:]
seen = set()
out = []
for l in labels:
  if l not in seen:
    seen.add(l)
    out.append(l)
print("\n".join(out))
PY
)

# Helper: list current labels
CURRENT_LABELS="$(gh issue view "$ISSUE" -R "$REPO" --json labels -q '.labels[].name' || true)"

# If we're applying a status:* label, remove existing status:* first
if printf "%s\n" "${LABELS_TO_APPLY[@]}" | grep -q '^status:'; then
  while IFS= read -r l; do
    [[ "$l" == status:* ]] && gh issue edit "$ISSUE" -R "$REPO" --remove-label "$l" >/dev/null 2>&1 || true
  done <<< "$CURRENT_LABELS"
fi

# If we're applying a domain:* label and NOT blocked:domain-conflict, remove existing domain:* first
APPLY_DOMAIN="$(printf "%s\n" "${LABELS_TO_APPLY[@]}" | grep '^domain:' | head -n 1 || true)"
HAS_DOMAIN_CONFLICT="$(printf "%s\n" "${LABELS_TO_APPLY[@]}" | grep -x 'blocked:domain-conflict' || true)"

if [[ -n "$APPLY_DOMAIN" && -z "$HAS_DOMAIN_CONFLICT" ]]; then
  while IFS= read -r l; do
    [[ "$l" == domain:* ]] && gh issue edit "$ISSUE" -R "$REPO" --remove-label "$l" >/dev/null 2>&1 || true
  done <<< "$CURRENT_LABELS"
fi

echo "Applying labels to $REPO#$ISSUE:"
printf "%s\n" "${LABELS_TO_APPLY[@]}" | sed 's/^/  + /'

# Apply all labels
for l in "${LABELS_TO_APPLY[@]}"; do
  [[ -z "$l" ]] && continue
  gh issue edit "$ISSUE" -R "$REPO" --add-label "$l" >/dev/null
done

echo "Done."
