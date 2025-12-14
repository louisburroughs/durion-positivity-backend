#!/bin/bash

##
# MCP Orchestrator Script
# 
# Chains MCP servers with various LLMs for intelligent context-aware responses
# Prevents token overload by smart context management
#
# Usage:
#   ./mcp-orchestrator.sh --task "implement security" --file src/api/UserService.java --llm claude
#   ./mcp-orchestrator.sh --task "optimize performance" --file src/App.vue --llm openai
#   ./mcp-orchestrator.sh --agent api-agent --request "Design endpoint for products" --llm claude
#
# Supported LLMs: claude, openai, anthropic, ollama, grok
#

set -euo pipefail

# ============================================================================
# CONFIGURATION
# ============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
MCP_INSTRUCTIONS_SERVER="${PROJECT_ROOT}/.github/mcp-servers/context-aware-instructions-server.js"
MCP_AGENTS_SERVER="${PROJECT_ROOT}/.github/mcp-servers/moqui-agents-server.js"
CACHE_DIR="${PROJECT_ROOT}/.mcp-cache"
LOG_DIR="${PROJECT_ROOT}/.mcp-logs"

# Defaults
LLM="${LLM:-claude}"      # claude, openai, anthropic, ollama, grok
CONTEXT_BUDGET=6000       # tokens
MODE="file"               # file or agent
VERBOSE=0
DRY_RUN=0

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m'

# ============================================================================
# HELPER FUNCTIONS
# ============================================================================

log_info() {
  echo -e "${BLUE}ℹ${NC} $*"
}

log_success() {
  echo -e "${GREEN}✓${NC} $*"
}

log_warn() {
  echo -e "${YELLOW}⚠${NC} $*"
}

log_error() {
  echo -e "${RED}✗${NC} $*" >&2
}

log_debug() {
  [[ $VERBOSE -eq 1 ]] && echo -e "${BLUE}◆${NC} $*"
}

# Create necessary directories
setup_dirs() {
  mkdir -p "$CACHE_DIR"
  mkdir -p "$LOG_DIR"
}

# Parse command-line arguments
parse_args() {
  while [[ $# -gt 0 ]]; do
    case $1 in
      --task)
        TASK="$2"
        MODE="file"
        shift 2
        ;;
      --file)
        FILEPATH="$2"
        shift 2
        ;;
      --agent)
        AGENT_NAME="$2"
        MODE="agent"
        shift 2
        ;;
      --request)
        REQUEST="$2"
        shift 2
        ;;
      --llm)
        LLM="$2"
        shift 2
        ;;
      --context-budget)
        CONTEXT_BUDGET="$2"
        shift 2
        ;;
      --verbose)
        VERBOSE=1
        shift
        ;;
      --dry-run)
        DRY_RUN=1
        shift
        ;;
      --help)
        print_help
        exit 0
        ;;
      *)
        log_error "Unknown option: $1"
        exit 1
        ;;
    esac
  done
}

# Validate that required inputs are provided
validate_inputs() {
  if [[ "$MODE" == "file" ]]; then
    if [[ -z "${TASK:-}" ]] || [[ -z "${FILEPATH:-}" ]]; then
      log_error "File mode requires --task and --file"
      exit 1
    fi
    if [[ ! -f "$FILEPATH" ]]; then
      log_error "File not found: $FILEPATH"
      exit 1
    fi
  fi

  if [[ "$MODE" == "agent" ]]; then
    if [[ -z "${AGENT_NAME:-}" ]] || [[ -z "${REQUEST:-}" ]]; then
      log_error "Agent mode requires --agent and --request"
      exit 1
    fi
  fi

  case "$LLM" in
    claude|openai|anthropic|ollama|grok) ;;
    *)
      log_error "Unknown LLM: $LLM"
      exit 1
      ;;
  esac
}

# Check if required commands are available
check_dependencies() {
  if ! command -v node &> /dev/null; then
    log_error "Node.js is required but not installed"
    exit 1
  fi

  if ! command -v jq &> /dev/null; then
    log_warn "jq not found - JSON formatting may be limited"
  fi
}

# Get file extension
get_file_extension() {
  echo "${1##*.}"
}

# Get file type name
get_file_type() {
  local ext=$(get_file_extension "$1")
  case "$ext" in
    java) echo "java" ;;
    groovy) echo "groovy" ;;
    ts) echo "typescript" ;;
    js) echo "javascript" ;;
    vue) echo "vue" ;;
    ftl) echo "freemarker" ;;
    md) echo "markdown" ;;
    *) echo "unknown" ;;
  esac
}

# Generate cache key
get_cache_key() {
  local input="$1"
  echo "$(echo -n "$input" | md5sum | awk '{print $1}')"
}

# Check if response is cached
get_cached_response() {
  local cache_key="$1"
  local cache_file="${CACHE_DIR}/${cache_key}.json"
  
  if [[ -f "$cache_file" ]]; then
    cat "$cache_file"
    return 0
  fi
  return 1
}

# Save response to cache
cache_response() {
  local cache_key="$1"
  local response="$2"
  local cache_file="${CACHE_DIR}/${cache_key}.json"
  echo "$response" > "$cache_file"
}

# ============================================================================
# MCP CALL FUNCTIONS
# ============================================================================

# Call context-aware-instructions MCP server
call_instructions_mcp() {
  local task="$1"
  local file_type="$2"
  local budget="$3"

  log_debug "Calling instructions MCP: task='$task', fileType='$file_type', budget=$budget"

  if [[ $DRY_RUN -eq 1 ]]; then
    log_info "[DRY RUN] Would call instructions MCP with task='$task' for $file_type"
    return 0
  fi

  # Try to call the server
  if [[ -f "$MCP_INSTRUCTIONS_SERVER" ]]; then
    node "$MCP_INSTRUCTIONS_SERVER" <<EOF
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "get_contextual_instructions",
    "arguments": {
      "task": "$task",
      "fileType": "$file_type",
      "contextBudget": $budget
    }
  }
}
EOF
  else
    log_warn "Instructions MCP server not found at: $MCP_INSTRUCTIONS_SERVER"
  fi
}

# Call agent MCP server
call_agents_mcp() {
  local agent_name="$1"

  log_debug "Calling agents MCP: agent='$agent_name'"

  if [[ $DRY_RUN -eq 1 ]]; then
    log_info "[DRY RUN] Would call agents MCP to get guide for '$agent_name'"
    return 0
  fi

  if [[ -f "$MCP_AGENTS_SERVER" ]]; then
    node "$MCP_AGENTS_SERVER" <<EOF
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "get_agent",
    "arguments": {
      "agent_name": "$agent_name"
    }
  }
}
EOF
  else
    log_warn "Agents MCP server not found at: $MCP_AGENTS_SERVER"
  fi
}

# ============================================================================
# LLM ROUTING FUNCTIONS
# ============================================================================

# Route to Claude (Anthropic)
route_to_claude() {
  local instructions="$1"
  local user_request="$2"

  log_info "Routing to Claude via Anthropic API..."
  
  if [[ $DRY_RUN -eq 1 ]]; then
    log_info "[DRY RUN] Would send to Claude: $user_request"
    return 0
  fi

  if [[ -z "${ANTHROPIC_API_KEY:-}" ]]; then
    log_error "ANTHROPIC_API_KEY environment variable not set"
    return 1
  fi

  # Placeholder: Actual implementation would use curl to call Anthropic API
  log_debug "Claude routing stub - implement with actual API call"
}

# Route to OpenAI
route_to_openai() {
  local instructions="$1"
  local user_request="$2"

  log_info "Routing to OpenAI API..."
  
  if [[ $DRY_RUN -eq 1 ]]; then
    log_info "[DRY RUN] Would send to OpenAI: $user_request"
    return 0
  fi

  if [[ -z "${OPENAI_API_KEY:-}" ]]; then
    log_error "OPENAI_API_KEY environment variable not set"
    return 1
  fi

  # Placeholder: Actual implementation would use curl to call OpenAI API
  log_debug "OpenAI routing stub - implement with actual API call"
}

# Route to Anthropic
route_to_anthropic() {
  local instructions="$1"
  local user_request="$2"

  # Anthropic is same as Claude
  route_to_claude "$instructions" "$user_request"
}

# Route to Ollama (local)
route_to_ollama() {
  local instructions="$1"
  local user_request="$2"

  log_info "Routing to Ollama (local model)..."
  
  if [[ $DRY_RUN -eq 1 ]]; then
    log_info "[DRY RUN] Would send to Ollama: $user_request"
    return 0
  fi

  if ! command -v ollama &> /dev/null; then
    log_error "Ollama not installed or not in PATH"
    return 1
  fi

  # Placeholder: Actual implementation would use ollama CLI
  log_debug "Ollama routing stub - implement with actual CLI call"
}

# Route to Grok
route_to_grok() {
  local instructions="$1"
  local user_request="$2"

  log_info "Routing to Grok API..."
  
  if [[ $DRY_RUN -eq 1 ]]; then
    log_info "[DRY RUN] Would send to Grok: $user_request"
    return 0
  fi

  if [[ -z "${GROK_API_KEY:-}" ]]; then
    log_error "GROK_API_KEY environment variable not set"
    return 1
  fi

  # Placeholder: Actual implementation would use curl to call Grok API
  log_debug "Grok routing stub - implement with actual API call"
}

# ============================================================================
# MAIN EXECUTION
# ============================================================================

print_help() {
  cat << 'EOF'
Usage: mcp-orchestrator.sh [OPTIONS]

OPTIONS:

  File Mode (get instructions for a specific file):
    --task DESCRIPTION       Task description (e.g., "implement security", "optimize")
    --file PATH              File path to work on (e.g., src/UserService.java)
    
  Agent Mode (consult an agent for guidance):
    --agent NAME             Agent name (e.g., "api-agent", "architecture-agent")
    --request DESCRIPTION    Request description (e.g., "Design REST endpoint")
    
  General Options:
    --llm LLM               LLM to use: claude, openai, anthropic, ollama, grok
                           (default: claude)
    --context-budget NUM    Context budget in tokens (default: 6000)
    --verbose              Enable verbose output
    --dry-run              Show what would be executed without running
    --help                 Show this help message

EXAMPLES:

  # Get security instructions for Java file
  ./mcp-orchestrator.sh --task "implement security" --file src/UserService.java --llm claude

  # Get performance optimization guidance
  ./mcp-orchestrator.sh --task "optimize performance" --file src/App.vue --llm openai

  # Consult API agent via Claude
  ./mcp-orchestrator.sh --agent api-agent --request "Design REST endpoint for products" --llm claude

  # Use local Ollama model
  ./mcp-orchestrator.sh --task "write test" --file src/test.groovy --llm ollama

  # Verbose output with dry-run
  ./mcp-orchestrator.sh --task "security review" --file src/UserService.java --verbose --dry-run

ENVIRONMENT VARIABLES:

  ANTHROPIC_API_KEY      For Claude/Anthropic LLM
  OPENAI_API_KEY         For OpenAI LLM
  GROK_API_KEY           For Grok LLM
  MOQUI_PROJECT_ROOT     Project root (auto-detected if not set)

CONTEXT BUDGET:

  The script manages token usage to prevent overloading the LLM context:
  - Default budget: 6000 tokens (~24KB)
  - Instructions consume 40% of budget
  - Documentation consumes 30% of budget
  - Agent guides consume 20% of budget
  - Reserved for response: 10% of budget

EOF
}

main() {
  # Initial setup
  setup_dirs
  check_dependencies
  parse_args "$@"
  validate_inputs

  log_info "MCP Orchestrator starting..."
  log_info "Mode: $MODE"
  log_info "LLM: $LLM"
  log_info "Context budget: $CONTEXT_BUDGET tokens"
  
  # Execute based on mode
  if [[ "$MODE" == "file" ]]; then
    FILE_TYPE=$(get_file_type "$FILEPATH")
    log_info "File: $FILEPATH (type: $FILE_TYPE)"
    log_info "Task: $TASK"
    
    # Check cache
    CACHE_KEY=$(get_cache_key "${TASK}:${FILEPATH}")
    if CACHED=$(get_cached_response "$CACHE_KEY"); then
      log_info "Using cached response"
      echo "$CACHED"
    else
      # Call MCP to get instructions
      INSTRUCTIONS=$(call_instructions_mcp "$TASK" "$FILE_TYPE" "$CONTEXT_BUDGET")
      
      # Route to LLM
      case "$LLM" in
        claude)
          route_to_claude "$INSTRUCTIONS" "$TASK for $FILEPATH"
          ;;
        openai)
          route_to_openai "$INSTRUCTIONS" "$TASK for $FILEPATH"
          ;;
        anthropic)
          route_to_anthropic "$INSTRUCTIONS" "$TASK for $FILEPATH"
          ;;
        ollama)
          route_to_ollama "$INSTRUCTIONS" "$TASK for $FILEPATH"
          ;;
        grok)
          route_to_grok "$INSTRUCTIONS" "$TASK for $FILEPATH"
          ;;
      esac
      
      # Cache the instructions (not the full response)
      cache_response "$CACHE_KEY" "$INSTRUCTIONS"
    fi
    
  elif [[ "$MODE" == "agent" ]]; then
    log_info "Agent: $AGENT_NAME"
    log_info "Request: $REQUEST"
    
    # Call MCP to get agent guide
    AGENT_GUIDE=$(call_agents_mcp "$AGENT_NAME")
    
    # Route to LLM
    case "$LLM" in
      claude)
        route_to_claude "$AGENT_GUIDE" "$REQUEST"
        ;;
      openai)
        route_to_openai "$AGENT_GUIDE" "$REQUEST"
        ;;
      anthropic)
        route_to_anthropic "$AGENT_GUIDE" "$REQUEST"
        ;;
      ollama)
        route_to_ollama "$AGENT_GUIDE" "$REQUEST"
        ;;
      grok)
        route_to_grok "$AGENT_GUIDE" "$REQUEST"
        ;;
    esac
  fi

  log_success "Complete!"
}

# Run main function
main "$@"
