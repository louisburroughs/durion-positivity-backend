#!/usr/bin/env bash
# Set the alpha tenant's bank-reconciliation close policy to ADVISORY (owner decision 2026-09-29).
# Keeps the other four settings as they are; writes BANK_REC_POLICY_SET audit rows.
set -euo pipefail

BASE="${BASE:-https://durionpos.org/api}"
read -r -p "Username [admin.alpha]: " USERNAME
USERNAME="${USERNAME:-admin.alpha}"
read -r -s -p "Password: " PASSWORD; echo

TOKEN=$(jq -n --arg u "$USERNAME" --arg p "$PASSWORD" '{username:$u,password:$p}' \
  | curl -sf -X POST "$BASE/security-service/v1/auth/login" -H 'Content-Type: application/json' -d @- \
  | jq -r '.accessToken // .token')
unset PASSWORD
[ -n "$TOKEN" ] && [ "$TOKEN" != "null" ] || { echo "login failed" >&2; exit 1; }

H=(-H "Authorization: Bearer $TOKEN" -H 'X-API-Version: 1' -H 'Content-Type: application/json')
URL="$BASE/accounting/periods/bank-reconciliation-policy"

echo "Current policy:"
CURRENT=$(curl -sf "${H[@]}" "$URL")
echo "$CURRENT" | jq .

BODY=$(echo "$CURRENT" | jq '{
  closePolicy: "ADVISORY",
  closeScope, closeCoverageLagDays, allowSelfApproval, otherApprovalThreshold,
  justification: "Alpha runs bank reconciliation close readiness as advisory only (owner decision 2026-09-29); production default stays REQUIRED_WITH_EXCEPTION."
}')

echo "Updated policy:"
curl -sS -X PUT "${H[@]}" "$URL" -d "$BODY" | jq .
