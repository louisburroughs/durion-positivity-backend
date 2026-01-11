#!/usr/bin/env bash
set -euo pipefail

# Create/ensure GitHub labels for durion-positivity-backend
# Usage:
#   ./create_labels.sh
#
# Prereqs:
#   - gh auth login
#   - permissions to manage labels on the repo

REPO="${REPO:-louisburroughs/durion-positivity-backend}"

# Idempotent "ensure label exists + matches config"
ensure_label () {
  local name="$1"
  local color="$2"
  local description="$3"

  if gh label view "$name" -R "$REPO" >/dev/null 2>&1; then
    # Update existing label to match canonical config
    gh label edit "$name" \
      -R "$REPO" \
      --color "$color" \
      --description "$description" >/dev/null
    echo "Updated: $name"
  else
    gh label create "$name" \
      -R "$REPO" \
      --color "$color" \
      --force \
      --description "$description" >/dev/null
    echo "Created: $name"
  fi
}

echo "Applying label set to: $REPO"
echo

# ---------------------------
# 1) Domain routing labels (exactly one required on stories)
# ---------------------------
ensure_label "domain:accounting"  "0E8A16" "Routing: Accounting (AR/AP/GL)"
ensure_label "domain:inventory"   "0E8A16" "Routing: Inventory Control"
ensure_label "domain:product"     "0E8A16" "Routing: Product & Catalog"
ensure_label "domain:pricing"     "0E8A16" "Routing: Pricing & Fees"
ensure_label "domain:crm"         "0E8A16" "Routing: CRM"
ensure_label "domain:shopmgmt"    "0E8A16" "Routing: Shop Management"
ensure_label "domain:workexec"    "0E8A16" "Routing: Workorder Execution"
ensure_label "domain:billing"     "0E8A16" "Routing: Invoicing & Payments"
ensure_label "domain:people"      "0E8A16" "Routing: People & Roles (HR-lite)"
ensure_label "domain:location"    "0E8A16" "Routing: Location Management"
ensure_label "domain:positivity"  "0E8A16" "Routing: External Integrations (Positivity)"
ensure_label "domain:security"    "0E8A16" "Routing: Security & Authorization"
ensure_label "domain:audit"       "0E8A16" "Routing: Audit & Observability"

# ---------------------------
# 2) Issue type labels
# ---------------------------
ensure_label "type:story"          "1D76DB" "Issue type: User story (agent-processable)"
ensure_label "type:epic"           "1D76DB" "Issue type: Epic (not rewritten by story agent)"
ensure_label "type:capability"     "1D76DB" "Issue type: Capability (not rewritten by story agent)"
ensure_label "type:clarification"  "1D76DB" "Issue type: Clarification (created when info is missing)"
ensure_label "type:bug"            "1D76DB" "Issue type: Bug"
ensure_label "type:task"           "1D76DB" "Issue type: Task / chore"

# ---------------------------
# 3) Blocking & status labels
# ---------------------------
ensure_label "blocked:clarification"       "B60205" "Blocked: awaiting clarification issue resolution"
ensure_label "blocked:domain-conflict"     "B60205" "Blocked: domain agents disagree; needs human decision"
ensure_label "blocked:business-decision"   "B60205" "Blocked: needs business decision (policy/priority)"
ensure_label "blocked:external-dependency" "B60205" "Blocked: depends on external system/vendor"

ensure_label "status:draft"          "FBCA04" "Status: Draft / being authored"
ensure_label "status:needs-review"   "FBCA04" "Status: Needs human/domain review"
ensure_label "status:ready-for-dev"  "FBCA04" "Status: Ready for development (no open questions)"
ensure_label "status:ready-for-test" "FBCA04" "Status: Ready for test design/automation"

# ---------------------------
# 4) Clarification classification labels
# ---------------------------
ensure_label "clarification:domain"        "D93F0B" "Clarification category: domain semantics/states"
ensure_label "clarification:policy"        "D93F0B" "Clarification category: business policy/decision"
ensure_label "clarification:data"          "D93F0B" "Clarification category: required data/fields"
ensure_label "clarification:workflow"      "D93F0B" "Clarification category: workflow/states/roles"
ensure_label "clarification:integration"   "D93F0B" "Clarification category: external integration contract"
ensure_label "clarification:security"      "D93F0B" "Clarification category: authz/audit/privacy"

# ---------------------------
# 5) Agent interaction labels (optional, but useful)
# ---------------------------
ensure_label "agent:story-authoring"            "5319E7" "Agent routing: Story Authoring Agent"
ensure_label "agent:principal-software-engineer" "5319E7" "Agent routing: Principal Software Engineer Agent"
ensure_label "agent:accounting"                  "5319E7" "Agent routing: Accounting Domain Agent"
ensure_label "agent:inventory"                   "5319E7" "Agent routing: Inventory Domain Agent"
ensure_label "agent:product"                     "5319E7" "Agent routing: Product Domain Agent"
ensure_label "agent:pricing"                     "5319E7" "Agent routing: Pricing Domain Agent"
ensure_label "agent:crm"                         "5319E7" "Agent routing: CRM Domain Agent"
ensure_label "agent:shopmgmt"                    "5319E7" "Agent routing: Shop Management Domain Agent"
ensure_label "agent:workexec"                    "5319E7" "Agent routing: Workorder Execution Domain Agent"
ensure_label "agent:billing"                     "5319E7" "Agent routing: Billing Domain Agent"
ensure_label "agent:people"                      "5319E7" "Agent routing: People/Roles Domain Agent"
ensure_label "agent:location"                    "5319E7" "Agent routing: Location Domain Agent"
ensure_label "agent:positivity"                  "5319E7" "Agent routing: Positivity (Integrations) Domain Agent"
ensure_label "agent:security"                    "5319E7" "Agent routing: Security Domain Agent"
ensure_label "agent:audit"                       "5319E7" "Agent routing: Audit/Observability Domain Agent"

# ---------------------------
# 6) Unsafe-inference risk guard labels
# ---------------------------
ensure_label "risk:financial-inference"   "E99695" "Risk: would require guessing financial/accounting policy"
ensure_label "risk:legal-inference"       "E99695" "Risk: would require guessing legal/contract policy"
ensure_label "risk:security-inference"    "E99695" "Risk: would require guessing security posture"
ensure_label "risk:regulatory-inference"  "E99695" "Risk: would require guessing regulatory compliance"

echo
echo "Done. Labels ensured on $REPO."
echo "Tip: export REPO=OWNER/REPO to target a different repo."
