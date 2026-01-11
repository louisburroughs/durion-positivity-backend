#!/usr/bin/env bash
set -euo pipefail

# Method A: GitHub CLI + jq -> Markdown corpus
# Filters: open issues with labels "type:clarification" AND "blocked:clarification"
# Repo: louisburroughs/durion-positivity-backend

OWNER="louisburroughs"
REPO="durion-positivity-backend"
FULL_REPO="${OWNER}/${REPO}"

OUTDIR="./issue-export"
STAMP="$(date -u +"%Y%m%dT%H%M%SZ")"
JSON_OUT="${OUTDIR}/${REPO}-clarification-open-${STAMP}.json"
MD_OUT="${OUTDIR}/${REPO}-clarification-open-${STAMP}.md"
CORPUS_OUT="${OUTDIR}/${REPO}-clarification-open-CORPUS.md"

mkdir -p "${OUTDIR}"

command -v gh >/dev/null 2>&1 || { echo "ERROR: gh (GitHub CLI) not found in PATH" >&2; exit 1; }
command -v jq >/dev/null 2>&1 || { echo "ERROR: jq not found in PATH" >&2; exit 1; }

# Validate auth (non-fatal; gives a clearer message if not logged in)
if ! gh auth status >/dev/null 2>&1; then
  echo "ERROR: gh is not authenticated. Run: gh auth login" >&2
  exit 1
fi

# Build search query:
# - state:open
# - labels: type:clarification AND blocked:clarification
# Using quoted labels because they include ':'.
QUERY='is:issue is:open label:"type:clarification" label:"blocked:clarification"'

echo "Exporting issues from ${FULL_REPO} with query: ${QUERY}" >&2

# Export issues to JSON
gh issue list -R "${FULL_REPO}" \
  --search "${QUERY}" \
  --limit 5000 \
  --json number,title,state,createdAt,updatedAt,closedAt,author,labels,assignees,milestone,url,body \
  > "${JSON_OUT}"

COUNT="$(jq 'length' "${JSON_OUT}")"
echo "Exported ${COUNT} issues to ${JSON_OUT}" >&2

# Convert JSON to a single Markdown file with YAML frontmatter per issue
jq -r '
  .[]
  | (
      "---\n"
      + "repo: " + "'"${FULL_REPO}"'" + "\n"
      + "issue_number: " + (.number|tostring) + "\n"
      + "title: " + (.title|tojson) + "\n"
      + "state: " + (.state // "") + "\n"
      + "url: " + (.url // "") + "\n"
      + "created_at: " + (.createdAt // "") + "\n"
      + "updated_at: " + (.updatedAt // "") + "\n"
      + "closed_at: " + (.closedAt // "") + "\n"
      + "author: " + ((.author.login // "")|tostring) + "\n"
      + "labels: " + ((.labels // []) | map(.name) | join(", ")) + "\n"
      + "assignees: " + ((.assignees // []) | map(.login) | join(", ")) + "\n"
      + "milestone: " + ((.milestone.title // "")|tostring) + "\n"
      + "---\n\n"
      + "# " + (.title // "") + " (#" + (.number|tostring) + ")\n\n"
      + (.body // "") + "\n"
    )
' "${JSON_OUT}" > "${MD_OUT}"

# Create a stable corpus filename (overwrite each run)
cp -f "${MD_OUT}" "${CORPUS_OUT}"

echo "Markdown export: ${MD_OUT}" >&2
echo "Corpus (stable path): ${CORPUS_OUT}" >&2
echo "Done." >&2
