#!/bin/bash
#
# clarification-resolver.sh
# Resolves blocked:domain-conflict issues by querying ChatGPT to answer open questions.
# 
# Usage:
#   OPENAI_API_KEY=sk_... ./clarification-resolver.sh [--dry-run] [--verbose]
#
# Environment:
#   OPENAI_API_KEY       (required) OpenAI API key for ChatGPT access
#   OPENAI_MODEL         (optional) Model to use (default: gpt-5.2)
#

set -euo pipefail

# Bash 3.2+ compatibility
if [[ -z "${BASH_VERSION:-}" ]]; then
  echo "This script requires bash"
  exit 1
fi

# ============================================================================
# Configuration
# ============================================================================

readonly REPO="${REPO:-louisburroughs/durion-positivity-backend}"
readonly LABEL="blocked:domain-conflict"
readonly OPENAI_MODEL="${OPENAI_MODEL:-gpt-5.2}"
readonly OPENAI_API_URL="https://api.openai.com/v1/responses"
readonly SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
readonly LOG_FILE="${SCRIPT_DIR}/clarification-resolver.log"

# Flags
DRY_RUN=false
VERBOSE=false

# ============================================================================
# Colors & Logging
# ============================================================================

color_reset='\033[0m'
color_green='\033[0;32m'
color_yellow='\033[1;33m'
color_red='\033[0;31m'
color_blue='\033[0;34m'

log() {
  local level="$1"
  shift
  local message="$*"
  local timestamp
  timestamp=$(date '+%Y-%m-%d %H:%M:%S')
  
  case "$level" in
    INFO)
      printf '%b\n' "${color_blue}[INFO]${color_reset} ${message}" >&2
      ;;
    SUCCESS)
      printf '%b\n' "${color_green}[SUCCESS]${color_reset} ${message}" >&2
      ;;
    WARN)
      printf '%b\n' "${color_yellow}[WARN]${color_reset} ${message}" >&2
      ;;
    ERROR)
      printf '%b\n' "${color_red}[ERROR]${color_reset} ${message}" >&2
      ;;
  esac
  
  printf '%s\n' "[${timestamp}] [${level}] ${message}" >> "$LOG_FILE"
}

# ============================================================================
# Pre-flight Checks
# ============================================================================

check_dependencies() {
  local missing=()
  
  if ! command -v gh >/dev/null 2>&1; then
    missing+=("gh (GitHub CLI)")
  fi
  
  if ! command -v curl >/dev/null 2>&1; then
    missing+=("curl")
  fi
  
  if ! command -v jq >/dev/null 2>&1; then
    missing+=("jq")
  fi
  
  if [[ ${#missing[@]} -gt 0 ]]; then
    log ERROR "Missing required dependencies: ${missing[*]}"
    log ERROR "Please install: brew install gh curl jq"
    return 1
  fi
  
  return 0
}

check_github_auth() {
  if ! gh auth status >/dev/null 2>&1; then
    log ERROR "GitHub CLI not authenticated. Run: gh auth login"
    return 1
  fi
  
  log INFO "GitHub authentication verified"
  return 0
}

check_openai_key() {
  if [[ -z "${OPENAI_API_KEY:-}" ]]; then
    log ERROR "OPENAI_API_KEY environment variable not set"
    log ERROR "Export it: export OPENAI_API_KEY=sk_..."
    return 1
  fi
  
  # Basic validation (should start with sk-)
  if [[ ! "$OPENAI_API_KEY" =~ ^sk- ]]; then
    log WARN "OPENAI_API_KEY does not match expected format (sk-*)"
  fi
  
  log INFO "OpenAI API key configured"
  return 0
}

# ============================================================================
# GitHub Queries
# ============================================================================

query_blocked_issues() {
  log INFO "Querying issues with label '${LABEL}'..."
  
  local issues_json
  local temp_gh_output
  temp_gh_output=$(mktemp)
  
  # Get only number and title (not body which can be huge and contain problematic characters)
  if ! gh issue list --label "$LABEL" --state open --json number,title --repo "$REPO" > "$temp_gh_output" 2>/dev/null; then
    log ERROR "Failed to query GitHub issues"
    rm -f "$temp_gh_output"
    return 1
  fi
  
  # Read entire output
  if ! issues_json=$(cat "$temp_gh_output"); then
    log ERROR "Failed to read GitHub API output"
    rm -f "$temp_gh_output"
    return 1
  fi
  
  rm -f "$temp_gh_output"
  
  # Validate the JSON is valid
  if ! echo "$issues_json" | jq empty 2>/dev/null; then
    log ERROR "Invalid JSON response from GitHub API"
    return 1
  fi
  
  local count
  count=$(echo "$issues_json" | jq 'length' 2>/dev/null)
  
  if [[ -z "$count" || "$count" -eq 0 ]]; then
    log INFO "No issues found with label '${LABEL}'"
    return 0
  fi
  
  log INFO "Found ${count} issue(s) with label '${LABEL}'"
  echo "$issues_json"
  return 0
}

# ============================================================================
# ChatGPT Integration
# ============================================================================

extract_open_questions() {
  local body="$1"
  
  # Extract the "Open Questions" section or return entire body if section not found
  local questions
  if questions=$(echo "$body" | sed -n '/^## Open Questions$/,/^##\|^---$/p' | head -n -1); then
    if [[ -n "$questions" ]]; then
      echo "$questions"
      return 0
    fi
  fi
  
  # If no dedicated section, return the full body
  echo "$body"
  return 0
}

query_chatgpt() {
  local issue_number="$1"
  local issue_title="$2"
  local issue_body="$3"
  
  local open_questions
  open_questions=$(extract_open_questions "$issue_body")
  
  # Create temp files for safe handling of multiline content
  local temp_response temp_payload temp_prompt
  temp_response=$(mktemp)
  temp_payload=$(mktemp)
  temp_prompt=$(mktemp)
  
  # Write prompt to temp file to avoid shell expansion issues
  cat > "$temp_prompt" <<'PROMPT_EOF'
You are a GitHub issue reviewer helping to answer open questions in a technical story. The project scope is the durion-positivity-backend project.
PROMPT_EOF
  echo "" >> "$temp_prompt"
  echo "Issue #${issue_number}: ${issue_title}" >> "$temp_prompt"
  echo "" >> "$temp_prompt"
  echo "Open Questions and Context:" >> "$temp_prompt"
  echo "$open_questions" >> "$temp_prompt"
  echo "" >> "$temp_prompt"
  cat >> "$temp_prompt" <<'PROMPT_EOF'
Please provide clear, concise answers to each open question that would help an engineer implement this story. Format your response as a structured decision document that can be posted as a GitHub comment. Be specific and actionable.
PROMPT_EOF
  
  if [[ "$VERBOSE" == true ]]; then
    log INFO "Prompt for issue #${issue_number}:"
    sed 's/^/  /' < "$temp_prompt"
  fi
  
  # Build JSON payload using jq with rawfile to safely read the prompt from file
  jq -n \
    --arg model "$OPENAI_MODEL" \
    --arg system_content "You are a technical GitHub issue reviewer. Answer open questions clearly and concisely, formatted for GitHub markdown comments." \
    --rawfile user_content "$temp_prompt" \
    '{
      model: $model,
      instructions: $system_content,
      input: $user_content,
      temperature: 0.7,
      max_output_tokens: 2000
    }' > "$temp_payload"
  
  # Call ChatGPT API with retry logic for transient failures
  local http_code
  local max_retries=3
  local retry_count=0
  local retry_delay=5
  local ssl_flag=""

  # Optional insecure SSL mode (NOT recommended; set OPENAI_INSECURE_SSL=true to enable)
  if [[ "${OPENAI_INSECURE_SSL:-false}" == "true" ]]; then
    ssl_flag="--insecure"
    log WARN "OPENAI_INSECURE_SSL=true - disabling TLS certificate verification for OpenAI API calls (NOT recommended for production)"
  fi
  
  while [ $retry_count -lt $max_retries ]; do
    http_code=$(curl $ssl_flag -s -w "%{http_code}" -o "$temp_response" \
      -X POST "$OPENAI_API_URL" \
      -H "Authorization: Bearer ${OPENAI_API_KEY}" \
      -H "Content-Type: application/json" \
      -d @"$temp_payload"
    )
    
    # Success
    if [[ "$http_code" == "200" ]]; then
      break
    fi
    
    # Transient errors - retry with exponential backoff
    if [[ "$http_code" == "503" ]] || [[ "$http_code" == "429" ]] || [[ "$http_code" == "500" ]]; then
      ((retry_count++))
      if [ $retry_count -lt $max_retries ]; then
        log WARN "ChatGPT API returned HTTP $http_code for issue #${issue_number}, retrying in ${retry_delay}s (attempt $retry_count/$max_retries)"
        sleep $retry_delay
        retry_delay=$((retry_delay * 2))  # Exponential backoff
        continue
      fi
    fi
    
    # Permanent failure or max retries exceeded
    log ERROR "ChatGPT API request failed (HTTP $http_code) for issue #${issue_number} after $retry_count retries"
    cat "$temp_response" >&2
    rm -f "$temp_response" "$temp_payload" "$temp_prompt"
    return 1
  done
  
  if [[ "$http_code" != "200" ]]; then
    log ERROR "ChatGPT API request failed (HTTP $http_code) for issue #${issue_number}"
    cat "$temp_response" >&2
    rm -f "$temp_response" "$temp_payload" "$temp_prompt"
    return 1
  fi
  
  # Extract response message (Responses API format)
  local response
  if ! response=$(jq -r '[.output[]?.content[]? | select(.type=="output_text") | .text] | join("")' "$temp_response" 2>/dev/null); then
    log ERROR "Failed to parse ChatGPT response for issue #${issue_number}"
    cat "$temp_response" >&2
    rm -f "$temp_response" "$temp_payload" "$temp_prompt"
    return 1
  fi
  
  rm -f "$temp_response" "$temp_payload" "$temp_prompt"
  
  if [[ -z "$response" ]]; then
    log ERROR "ChatGPT returned empty response for issue #${issue_number}"
    return 1
  fi
  
  echo "$response"
  return 0
}

# ============================================================================
# GitHub Comments
# ============================================================================

post_comment_to_issue() {
  local issue_number="$1"
  local comment_body="$2"
  
  if [[ "$DRY_RUN" == true ]]; then
    log INFO "[DRY-RUN] Would post comment to issue #${issue_number}:"
    echo "$comment_body" | sed 's/^/  /'
    return 0
  fi
  
  log INFO "Posting comment to issue #${issue_number}..."
  
  if ! gh issue comment "$issue_number" --body "$comment_body" --repo "$REPO" 2>/dev/null; then
    log ERROR "Failed to post comment to issue #${issue_number}"
    return 1
  fi
  
  log SUCCESS "Comment posted to issue #${issue_number}"
  return 0
}

# ============================================================================
# Main Processing Loop
# ============================================================================

process_issues() {
  local issues_json="$1"
  local total
  local success_count=0
  local error_count=0
  
  total=$(echo "$issues_json" | jq 'length' 2>/dev/null)
  
  # Use a safer loop without piping through jq line-by-line
  local i=0
  while [ $i -lt "$total" ]; do
    local issue_data
    issue_data=$(echo "$issues_json" | jq ".[$i]" 2>/dev/null)
    
    if [[ -z "$issue_data" ]]; then
      log WARN "Could not parse issue at index $i"
      ((i++))
      continue
    fi
    
    local issue_number title
    issue_number=$(echo "$issue_data" | jq -r '.number' 2>/dev/null)
    title=$(echo "$issue_data" | jq -r '.title' 2>/dev/null)
    
    if [[ -z "$issue_number" ]]; then
      log WARN "Could not extract issue number at index $i"
      ((i++))
      continue
    fi
    
    # Fetch the full body using gh (plain text output)
    local body
    body=$(gh issue view "$issue_number" --json body --jq '.body' --repo "$REPO" 2>/dev/null)
    if [[ -z "$body" ]]; then
      log WARN "Could not fetch body for issue #${issue_number}, using empty string"
      body=""
    fi
    
    log INFO "Processing issue #${issue_number}: ${title}"
    
    # Query ChatGPT
    if response=$(query_chatgpt "$issue_number" "$title" "$body"); then
      # Format response with header
      local comment_body="## Answers to Open Questions (resolved by ChatGPT)

${response}

---
*This response was automatically generated by clarification-resolver.sh on $(date '+%Y-%m-%d %H:%M:%S')*"
      
      # Post comment
      if post_comment_to_issue "$issue_number" "$comment_body"; then
        ((success_count++))
      else
        ((error_count++))
      fi
    else
      log ERROR "Failed to get response from ChatGPT for issue #${issue_number}"
      ((error_count++))
    fi
    
    # Rate limiting: wait 3 seconds between API calls (increased for stability)
    sleep 3
    ((i++))
  done
  
  log INFO "Processing complete. Success: ${success_count}, Errors: ${error_count}"
  
  if [[ $error_count -gt 0 ]]; then
    return 1
  fi
  
  return 0
}

# ============================================================================
# Help & Main
# ============================================================================

usage() {
  cat <<EOF
Usage: $0 [OPTIONS]

Options:
  --dry-run       Print what would be done without posting comments
  --verbose       Enable verbose output
  --help          Show this help message

Environment Variables:
  OPENAI_API_KEY  (required) OpenAI API key for ChatGPT
  OPENAI_MODEL    (optional) ChatGPT model (default: gpt-5.2)
  REPO            (optional) GitHub repo (default: louisburroughs/durion-positivity-backend)

Examples:
  # Resolve all blocked:domain-conflict issues
  export OPENAI_API_KEY=sk_...
  ./clarification-resolver.sh

  # Dry run
  export OPENAI_API_KEY=sk_...
  ./clarification-resolver.sh --dry-run

  # Verbose dry run
  export OPENAI_API_KEY=sk_...
  ./clarification-resolver.sh --dry-run --verbose
EOF
}

main() {
  # Parse arguments
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --dry-run)
        DRY_RUN=true
        shift
        ;;
      --verbose)
        VERBOSE=true
        shift
        ;;
      --help|-h)
        usage
        exit 0
        ;;
      *)
        log ERROR "Unknown option: $1"
        usage
        exit 1
        ;;
    esac
  done
  
  # Initialize log file
  touch "$LOG_FILE"
  
  log INFO "Starting clarification-resolver"
  log INFO "Repository: ${REPO}"
  log INFO "Label: ${LABEL}"
  log INFO "Model: ${OPENAI_MODEL}"
  
  if [[ "$DRY_RUN" == true ]]; then
    log WARN "DRY-RUN mode enabled - no comments will be posted"
  fi
  
  # Pre-flight checks
  if ! check_dependencies; then
    exit 1
  fi
  
  if ! check_github_auth; then
    exit 1
  fi
  
  if ! check_openai_key; then
    exit 1
  fi
  
  # Query and process issues
  if issues_json=$(query_blocked_issues); then
    if [[ -n "$issues_json" ]]; then
      if ! process_issues "$issues_json"; then
        log WARN "Some issues failed to process (see above)"
        exit 1
      fi
    fi
  else
    log ERROR "Failed to query GitHub issues"
    exit 1
  fi
  
  log SUCCESS "clarification-resolver completed successfully"
  exit 0
}

# Run main function
main "$@"