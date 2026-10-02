#!/usr/bin/env bash
# Drive a gate fixture through the MCP chat endpoint in batches and export the eval turn traces
# of each batch. Needs bash, curl and jq only. See --help.
set -euo pipefail

TRACE_CAP=200
DEFAULT_MSG_JQ='(.utterances // .fixtures)[] | (.text // .query)'
DEFAULT_ID_JQ='(.utterances // .fixtures)[] | (.id // .fixture_id)'

usage() {
  cat <<'USAGE'
Usage: scripts/gate_chat_run.sh --fixture <file> [--fixture <file> ...] [options]

Sends every message of each fixture to the chat endpoint (a fresh conversation per message), in
batches, and exports GET /v1/eval/turn-traces after each batch.

Options:
  --fixture <file>        Fixture JSON; repeatable; required.
  --messages-jq '<jq>'    jq program emitting one message string per turn.
                          Default: (.utterances // .fixtures)[] | (.text // .query)
                          i.e. tagging gate (.utterances[].text) and the RAG fixtures under
                          eval/rag-lexical and eval/rag-retrieval (.fixtures[].query).
  --ids-jq '<jq>'         jq program emitting one id per turn, for the failure log.
                          Default: (.utterances // .fixtures)[] | (.id // .fixture_id)
                          With a custom --messages-jq and no --ids-jq the id is the turn index.
  --batch-size <n>        Turns per batch (default 150). Must be 1..199: the trace export is capped
                          at 200 with no cursor, and a capped export has lost turns.
  --sleep <seconds>       Pause between turns (default 1).
  --label <name>          Name of the model or mode under test; used in file names (default: run).
  --out-dir <dir>         Output directory (default ./gate-runs/<label>-<UTC timestamp>).
  --chat-url <url>        Default http://localhost:8080/mcp-server/v1/mcp/chat
  --login-url <url>       Default http://localhost:8080/security-service/v1/auth/login
  --traces-url <url>      Default: chat URL with /v1/mcp/chat replaced by /v1/eval/turn-traces
  --user <name>           Login user (default admin.alpha).
  --dry-run               Print the batch plan and exit; no network, no output directory.
  -h, --help              This text.

Password: environment variable GATE_PASSWORD (never an argument, never logged).

Output (in --out-dir): traces-<label>-<fixture basename>-<batch>.json, run.log, manifest.json.
Exit status is non-zero if any export hit the 200 cap (that export is deleted; rerun that batch
range with a smaller --batch-size) or any turn failed.

Alpha preconditions:
  - The actor needs chat access AND the permission mcp:eval_trace:view (the export is 403 without).
  - The per-actor rate limit POS_NLTI_RATE_LIMIT_PER_SESSION must admit one turn per --sleep
    interval; raise it or raise --sleep, or turns fail with HTTP 429 (logged in run.log).
  - Turn traces expire after 24 h: run the report within a day of the run.
  - Do not chat as that actor anywhere else during the run: its turns land in the same export
    window and are scored (or counted against the cap) as gate turns.
  - Tokens live 1 h; the script mints a fresh one before every batch and every export.
USAGE
}

die() { echo "gate_chat_run: $*" >&2; exit 2; }

FIXTURES=()
MSG_JQ=""
ID_JQ=""
BATCH_SIZE=150
SLEEP=1
LABEL="run"
OUT_DIR=""
CHAT_URL="http://localhost:8080/mcp-server/v1/mcp/chat"
LOGIN_URL="http://localhost:8080/security-service/v1/auth/login"
TRACES_URL=""
GATE_USER="admin.alpha"
DRY_RUN=0

need_val() { [ $# -ge 2 ] || die "$1 needs a value"; }
while [ $# -gt 0 ]; do
  case "$1" in
    --fixture) need_val "$@"; FIXTURES+=("$2"); shift 2 ;;
    --messages-jq) need_val "$@"; MSG_JQ="$2"; shift 2 ;;
    --ids-jq) need_val "$@"; ID_JQ="$2"; shift 2 ;;
    --batch-size) need_val "$@"; BATCH_SIZE="$2"; shift 2 ;;
    --sleep) need_val "$@"; SLEEP="$2"; shift 2 ;;
    --label) need_val "$@"; LABEL="$2"; shift 2 ;;
    --out-dir) need_val "$@"; OUT_DIR="$2"; shift 2 ;;
    --chat-url) need_val "$@"; CHAT_URL="$2"; shift 2 ;;
    --login-url) need_val "$@"; LOGIN_URL="$2"; shift 2 ;;
    --traces-url) need_val "$@"; TRACES_URL="$2"; shift 2 ;;
    --user) need_val "$@"; GATE_USER="$2"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown argument: $1 (see --help)" ;;
  esac
done

[ ${#FIXTURES[@]} -gt 0 ] || die "at least one --fixture is required (see --help)"
[[ "$BATCH_SIZE" =~ ^[0-9]+$ ]] || die "--batch-size must be an integer"
{ [ "$BATCH_SIZE" -ge 1 ] && [ "$BATCH_SIZE" -lt "$TRACE_CAP" ]; } \
  || die "--batch-size must be 1..$((TRACE_CAP - 1)): the trace export is capped at $TRACE_CAP"
[[ "$SLEEP" =~ ^[0-9]+([.][0-9]+)?$ ]] || die "--sleep must be a number of seconds"
[[ "$LABEL" =~ ^[A-Za-z0-9._-]+$ ]] || die "--label may contain only letters, digits, . _ -"
command -v jq >/dev/null || die "jq is required"
[ -n "$TRACES_URL" ] || TRACES_URL="${CHAT_URL%/v1/mcp/chat}/v1/eval/turn-traces"
if [ -n "$MSG_JQ" ] && [ -z "$ID_JQ" ]; then ID_JQ='range(0; ([ '"$MSG_JQ"' ] | length))'; fi
[ -n "$MSG_JQ" ] || MSG_JQ="$DEFAULT_MSG_JQ"
[ -n "$ID_JQ" ] || ID_JQ="$DEFAULT_ID_JQ"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# Per fixture: $TMP/<n>.msgs.json and <n>.ids.json (arrays); count from the messages.
COUNTS=()
for i in "${!FIXTURES[@]}"; do
  f="${FIXTURES[$i]}"
  [ -r "$f" ] || die "cannot read fixture: $f"
  jq -c "[ $MSG_JQ ]" "$f" > "$TMP/$i.msgs.json" || die "--messages-jq failed on $f"
  jq -c "[ $ID_JQ ]" "$f" > "$TMP/$i.ids.json" || die "--ids-jq failed on $f"
  n=$(jq length "$TMP/$i.msgs.json")
  [ "$n" -gt 0 ] || die "no messages in $f (check --messages-jq)"
  [ "$n" = "$(jq length "$TMP/$i.ids.json")" ] || die "$f: ids and messages differ in count"
  jq -e 'all(type == "string")' "$TMP/$i.msgs.json" >/dev/null || die "$f: messages must be strings"
  COUNTS+=("$n")
done

# Plan lines: <fixture index> <batch> <start> <end>
PLAN=()
for i in "${!FIXTURES[@]}"; do
  n=${COUNTS[$i]}; b=1; s=0
  while [ "$s" -lt "$n" ]; do
    e=$((s + BATCH_SIZE)); [ "$e" -le "$n" ] || e=$n
    PLAN+=("$i $b $s $e"); b=$((b + 1)); s=$e
  done
done

STAMP=$(date -u +%Y%m%dT%H%M%SZ)
[ -n "$OUT_DIR" ] || OUT_DIR="./gate-runs/${LABEL}-${STAMP}"

if [ "$DRY_RUN" = 1 ]; then
  echo "label=$LABEL out_dir=$OUT_DIR batch_size=$BATCH_SIZE sleep=$SLEEP"
  for p in "${PLAN[@]}"; do
    read -r i b s e <<< "$p"
    echo "PLAN fixture=${FIXTURES[$i]} batch=$b start=$s end=$e count=$((e - s)) total=${COUNTS[$i]}"
  done
  exit 0
fi

# ---- everything below touches the network ----
command -v curl >/dev/null || die "curl is required"
[ -n "${GATE_PASSWORD:-}" ] || die "set GATE_PASSWORD in the environment"
mkdir -p "$OUT_DIR"
LOG="$OUT_DIR/run.log"
: > "$LOG"
log() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $*" | tee -a "$LOG" >&2; }

TOK=""
mint() {
  TOK=$(jq -n --arg u "$GATE_USER" --arg p "$GATE_PASSWORD" '{username: $u, password: $p}' |
    curl -sS --fail -X POST "$LOGIN_URL" -H "Content-Type: application/json" \
      -H "X-API-Version: 1" --data @- | jq -er .accessToken) || die "login failed for $GATE_USER"
}

START_TIME=$(date -u +%Y-%m-%dT%H:%M:%SZ)
BATCHES='[]'
STATUS=0
write_manifest() {
  jq -n --arg label "$LABEL" --argjson fixtures "$(printf '%s\n' "${FIXTURES[@]}" | jq -R . | jq -s .)" \
    --argjson batchSize "$BATCH_SIZE" --arg start "$START_TIME" \
    --arg end "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --argjson batches "$BATCHES" \
    '{label: $label, fixtures: $fixtures, batchSize: $batchSize, startedAt: $start,
      endedAt: $end, batches: $batches}' > "$OUT_DIR/manifest.json"
}
trap 'write_manifest || true; rm -rf "$TMP"' EXIT

for p in "${PLAN[@]}"; do
  read -r i b s e <<< "$p"
  f="${FIXTURES[$i]}"; base=$(basename "$f" .json)
  out="$OUT_DIR/traces-${LABEL}-${base}-${b}.json"
  mint
  since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  bstart=$since; failures=0
  log "batch $base/$b turns $s..$((e - 1)) since=$since"
  while IFS= read -r row; do
    id=$(jq -r .id <<< "$row")
    code=$(jq -c '{message: .message}' <<< "$row" |
      curl -sS -o /dev/null -w '%{http_code}' -X POST "$CHAT_URL" -H "Authorization: Bearer $TOK" \
        -H "X-API-Version: 1" -H "Content-Type: application/json" --data @- || echo 000)
    if [ "$code" != "200" ]; then
      failures=$((failures + 1)); log "turn failed: HTTP $code id=$id fixture=$base batch=$b"
    fi
    sleep "$SLEEP"
  done < <(jq -c --argjson s "$s" --argjson e "$e" --slurpfile ids "$TMP/$i.ids.json" \
    --slurpfile msgs "$TMP/$i.msgs.json" -n \
    '[range($s; $e)] | .[] | {id: $ids[0][.], message: $msgs[0][.]}')
  mint
  curl -sS --fail -G "$TRACES_URL" --data-urlencode "since=$since" --data-urlencode "limit=$TRACE_CAP" \
    -H "Authorization: Bearer $TOK" -H "X-API-Version: 1" > "$out" \
    || { rm -f "$out"; die "trace export failed for $base batch $b (needs mcp:eval_trace:view)"; }
  traces=$(jq length "$out")
  capped=false
  if [ "$traces" -ge "$TRACE_CAP" ]; then
    capped=true; rm -f "$out"; STATUS=1
    log "CAP HIT: $base batch $b exported $traces traces; export deleted. Rerun this range with a smaller --batch-size."
  else
    log "done $base batch $b turns=$((e - s)) failures=$failures traces=$traces"
  fi
  BATCHES=$(jq -c --arg fx "$f" --argjson b "$b" --argjson s "$s" --argjson e "$e" \
    --arg since "$bstart" --argjson fail "$failures" --argjson tr "$traces" --argjson cap "$capped" \
    --arg file "$([ "$capped" = true ] && echo "" || basename "$out")" \
    '. + [{fixture: $fx, batch: $b, start: $s, end: $e, since: $since, failures: $fail,
           traces: $tr, capHit: $cap, exportFile: (if $file == "" then null else $file end)}]' <<< "$BATCHES")
  [ "$failures" -eq 0 ] || STATUS=1
done
log "finished status=$STATUS manifest=$OUT_DIR/manifest.json"
exit "$STATUS"
