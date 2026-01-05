#!/usr/bin/env bash
set -euo pipefail
REPO="louisburroughs/durion-positivity-backend"

mkdir -p ./.story-work/inbox

gh issue list -R "$REPO" --label "story-implementation" --limit 200 --json number,title \
| jq -r '.[] | "\(.number)\t\(.title)"' \
| while IFS=$'\t' read -r num title; do
    gh issue view "$num" -R "$REPO" --json number,title,body,labels \
    > "./.story-work/inbox/$num.json"
    echo "Exported #$num"
  done
echo "Export complete. Stories saved in ./.story-work/inbox/"