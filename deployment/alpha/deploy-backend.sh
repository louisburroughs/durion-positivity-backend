#!/usr/bin/env bash
set -euo pipefail

# Usage:
#   deploy-backend.sh <GITHUB_SHA>     full deploy: retag, pull, force-recreate the stack
#   deploy-backend.sh --config-only    apply the on-box compose files to the running stack
#
# --config-only (#1457) is the delivery path for compose-file changes that need no image
# build: sync-alpha-config pulls the committed files from S3 and runs this mode, and
# `docker compose up -d` WITHOUT --force-recreate then recreates exactly the containers
# whose merged config changed (a plain restart would not pick up new environment values)
# while leaving the rest untouched. Image tags, database reconciliation, and the
# observability secret render are full-deploy concerns and are skipped.
MODE="full"
if [[ "${1:-}" == "--config-only" ]]; then
  MODE="config-only"
  shift
fi

if [[ "${MODE}" == "full" ]]; then
  GITHUB_SHA="${1:?GITHUB_SHA argument required}"
  IMAGE_TAG="sha-${GITHUB_SHA::7}"
fi

ALPHA_ROOT="${ALPHA_ROOT:-/opt/durion/alpha}"
BACKEND_DIR="${BACKEND_DIR:-${ALPHA_ROOT}/backend}"
ENV_FILE="${ENV_FILE:-${ALPHA_ROOT}/.env}"
PROD_OVERRIDE="${PROD_OVERRIDE:-${ALPHA_ROOT}/docker-compose.prod.yml}"
DOCKER_PRUNE_INTERVAL_HOURS="${DOCKER_PRUNE_INTERVAL_HOURS:-24}"
DOCKER_PRUNE_STATE_FILE="${DOCKER_PRUNE_STATE_FILE:-${ALPHA_ROOT}/.last-docker-prune-at}"
# Free space the box must have before it pulls a deploy's images (#1862). A deploy pulls 26
# images at a fresh sha tag, so a box that only reclaims on the way out eventually fills
# mid-pull. Measured on alpha immediately after a clean deploy of sha-ec7c0f6: the whole
# image store, backend plus observability plus Kafka plus postgres, is 14.34GB. The floor is
# set near double that so the transient peak — a compressed blob in /var/lib/docker/tmp
# alongside the layer being extracted, which is what the original failure hit — still fits.
# Set to 0 to disable the pre-pull reclaim.
DOCKER_MIN_FREE_GIB="${DOCKER_MIN_FREE_GIB:-25}"

env_single_quote() {
  printf "'%s'" "${1//\'/\'\"\'\"\'}"
}

# Drift guard (#1457): the box only ever receives compose files through an S3 pull, and a
# deploy that runs against yesterday's file is a silent no-op for config changes. Callers
# that know the committed digests (both deploy workflows compute them from the checkout)
# pass PROD_OVERRIDE_SHA256 / BASE_COMPOSE_SHA256; a mismatch stops the deploy loudly
# instead of applying stale config. Unset digests skip the check, so manual on-box runs
# still work.
verify_checksum() {
  local label="$1" file="$2" expected="$3"
  if [[ -z "${expected}" ]]; then
    return 0
  fi
  local actual
  actual="$(sha256sum "${file}" | awk '{print $1}')"
  if [[ "${actual}" != "${expected}" ]]; then
    echo "ERROR: on-box ${label} (${file}) does not match the committed version." >&2
    echo "  expected sha256: ${expected}" >&2
    echo "  on-box   sha256: ${actual}" >&2
    echo "The file is stale — the S3 pull did not happen or was raced. Re-run the" >&2
    echo "sync-alpha-config workflow (or the build-push-ecr deploy) so the pull and this" >&2
    echo "script run as one unit." >&2
    exit 1
  fi
  echo "${label} matches committed sha256 ${expected}"
}

require_env_entry() {
  local key="$1"
  if ! grep -q "^${key}=" "${ENV_FILE}"; then
    echo "ERROR: ${key} missing from ${ENV_FILE} — the stack has never been fully deployed" >&2
    echo "on this box. Config-only sync can only update a running stack; run the" >&2
    echo "build-push-ecr deploy first." >&2
    exit 1
  fi
}

# pos-supplier seals its exchange-audit payloads with SUPPLIER_AUDIT_ENC_KEY (ADR-0050 §7)
# and refuses to start without one outside the dev/test profiles. Deploying it with the key
# absent would fail late, in the tier --wait, after every image had already been pulled — and
# a key that changes between deploys makes every payload written under the old one
# permanently unreadable, reported as an authentication failure (i.e. as possible tampering).
# So resolve it up front: a value passed in by the workflow wins and is persisted, otherwise
# the one already on the box must be there and non-empty.
# Trim leading/trailing whitespace. AuditPayloadCipher calls .trim() before decoding, so a key
# with a stray newline — the normal shape of `openssl rand -base64 32` piped straight into a
# secret — is valid at runtime, and this guard must not reject what the service would accept.
trim_space() {
  local v="$1"
  v="${v#"${v%%[![:space:]]*}"}"
  v="${v%"${v##*[![:space:]]}"}"
  printf '%s' "${v}"
}

require_supplier_audit_key() {
  local existing supplied
  existing="$(grep -E "^SUPPLIER_AUDIT_ENC_KEY=" "${ENV_FILE}" | head -n1 | cut -d= -f2- || true)"
  existing="${existing%\'}"
  existing="${existing#\'}"
  existing="$(trim_space "${existing}")"
  supplied="$(trim_space "${SUPPLIER_AUDIT_ENC_KEY:-}")"

  if [[ -n "${supplied}" ]]; then
    # Padding is optional: Base64.getDecoder() accepts an unpadded 43-char key, so requiring the
    # '=' would reject a key the service decodes fine.
    if ! [[ "${supplied}" =~ ^[A-Za-z0-9+/]{43}=?$ ]]; then
      echo "ERROR: SUPPLIER_AUDIT_ENC_KEY must be 32 bytes of base64 (openssl rand -base64 32)." >&2
      exit 1
    fi
    # Compare unpadded: a 32-byte key takes exactly one '=', so the padded and unpadded
    # spellings are the same key and must not read as a rotation.
    if [[ -n "${existing}" && "${existing%=}" != "${supplied%=}" ]]; then
      echo "ERROR: SUPPLIER_AUDIT_ENC_KEY differs from the key already on this box." >&2
      echo "Rotating it here would orphan every exchange-audit payload sealed with the old" >&2
      echo "key. Rotate deliberately instead: move the current key into" >&2
      echo "SUPPLIER_AUDIT_ENC_PREVIOUS_KEYS as '<keyId>:<base64>' and bump" >&2
      echo "SUPPLIER_AUDIT_ENC_KEY_ID before changing this value." >&2
      exit 1
    fi
    local quoted
    quoted="$(env_single_quote "${supplied}")"
    if grep -q "^SUPPLIER_AUDIT_ENC_KEY=" "${ENV_FILE}"; then
      sed -i "s|^SUPPLIER_AUDIT_ENC_KEY=.*|SUPPLIER_AUDIT_ENC_KEY=${quoted}|" "${ENV_FILE}"
    else
      printf 'SUPPLIER_AUDIT_ENC_KEY=%s\n' "${quoted}" >> "${ENV_FILE}"
    fi
    drop_shell_copy_of_audit_key
    return 0
  fi

  if [[ -z "${existing}" ]]; then
    echo "ERROR: SUPPLIER_AUDIT_ENC_KEY is empty or missing in ${ENV_FILE} and none was" >&2
    echo "supplied by the deploy. pos-supplier will not start without it. Generate one with" >&2
    echo "  openssl rand -base64 32" >&2
    echo "and set it as the SUPPLIER_AUDIT_ENC_KEY repository secret (or add it to the" >&2
    echo "on-box env file) before deploying." >&2
    exit 1
  fi
  drop_shell_copy_of_audit_key
}

# Compose ranks the shell environment ABOVE --env-file, so an exported
# SUPPLIER_AUDIT_ENC_KEY shadows the one this script just made authoritative in the env
# file. The deploy workflow always passes the variable, so when the repository secret is
# unset it arrives set-but-empty and every ${SUPPLIER_AUDIT_ENC_KEY} in the compose files
# resolves to "" — pos-supplier then starts with no key and fails closed, while the env
# file that everyone inspects looks perfectly correct (#1577). Unsetting it once the key is
# persisted leaves exactly one source of truth: the env file.
#
# Only the bare ${SUPPLIER_AUDIT_ENC_KEY} is exposed to this; the sibling KEY_ID and
# PREVIOUS_KEYS use ${VAR:-default}, which falls back when a variable is set-but-empty.
drop_shell_copy_of_audit_key() {
  unset SUPPLIER_AUDIT_ENC_KEY
}

# Reads a value out of the env file, stripping one layer of the single quotes
# env_single_quote may have added.
env_value() {
  local v
  v="$(grep -E "^$1=" "${ENV_FILE}" | head -n1 | cut -d= -f2- || true)"
  v="${v%\'}"
  v="${v#\'}"
  printf '%s' "$(trim_space "${v}")"
}

# Both deploy modes need this. It used to live only in the full-deploy branch, on the
# assumption that config-only never pulls — true only while every service named below
# already had its image on the box from an earlier full deploy. The first sync that named
# a service which had never been deployed (pos-supplier and pos-marketing, #1577) had to
# pull, had no credentials, and fell through to building the image on the host, where the
# failure surfaced as an unrelated-looking "compose build requires buildx 0.17.0 or later".
ecr_login() {
  local registry="$1"
  if [[ -z "${registry}" ]]; then
    echo "ERROR: ECR registry is empty; cannot authenticate to pull images." >&2
    exit 1
  fi
  aws ecr get-login-password --region us-east-1 \
    | docker login --username AWS --password-stdin "${registry}"
}

# A config-only sync can only bring up a service that already has an image at the tag this
# box is pinned to. When a commit adds a service, the sync fires on the push while that
# service's image is published only by the build that runs after it — so the pre-flight
# pull fails for that one service and used to abort the whole sync. #1577 moved the abort
# up front, so nothing is half-applied, but the abort itself stayed, which makes every
# new-service commit a guaranteed red run for a condition the follow-on full deploy
# resolves on its own (pos-reference-mock, #1646).
#
# So classify the misses instead of aborting on all of them. A service whose image is
# missing and which has no container on this box has simply never been deployed here: skip
# it and let the build-push-ecr deploy that publishes its image bring it up. A service
# whose image is missing while it IS on this box is a real break — an image retagged or
# deleted out from under a live container — and still stops the sync before anything is
# touched.

# Echoes the backend services whose image cannot be resolved, one per line. The batch pull
# only reports that something failed, so this re-pulls one at a time to name them; images
# already on the box make each of those a no-op.
missing_backend_images() {
  local svc
  for svc in "${BACKEND_SERVICES[@]}"; do
    if ! docker compose "${COMPOSE_ARGS[@]}" pull --quiet "${svc}" >/dev/null 2>&1; then
      printf '%s\n' "${svc}"
    fi
  done
}

# True when the service has a container on this box, running or stopped.
service_has_container() {
  [[ -n "$(docker compose "${COMPOSE_ARGS[@]}" ps -aq "$1" 2>/dev/null)" ]]
}

# Echoes the arguments that are not in SKIPPED_SERVICES, one per line. Only called with
# SKIPPED_SERVICES non-empty.
without_skipped() {
  local svc skipped
  for svc in "$@"; do
    for skipped in "${SKIPPED_SERVICES[@]}"; do
      if [[ "${svc}" == "${skipped}" ]]; then
        continue 2
      fi
    done
    printf '%s\n' "${svc}"
  done
}

should_run_docker_prune() {
  if ! [[ "${DOCKER_PRUNE_INTERVAL_HOURS}" =~ ^[0-9]+$ ]]; then
    echo "Skipping Docker prune: DOCKER_PRUNE_INTERVAL_HOURS must be an integer, got '${DOCKER_PRUNE_INTERVAL_HOURS}'." >&2
    return 1
  fi

  if [[ "${DOCKER_PRUNE_INTERVAL_HOURS}" -le 0 ]]; then
    return 0
  fi

  if [[ ! -f "${DOCKER_PRUNE_STATE_FILE}" ]]; then
    return 0
  fi

  local now_epoch last_prune_epoch min_interval_seconds
  now_epoch="$(date +%s)"
  last_prune_epoch="$(cat "${DOCKER_PRUNE_STATE_FILE}" 2>/dev/null || echo 0)"
  min_interval_seconds="$((DOCKER_PRUNE_INTERVAL_HOURS * 3600))"

  [[ "$((now_epoch - last_prune_epoch))" -ge "${min_interval_seconds}" ]]
}

run_periodic_docker_prune() {
  if ! should_run_docker_prune; then
    echo "Skipping Docker prune: last cleanup is newer than ${DOCKER_PRUNE_INTERVAL_HOURS}h."
    return 0
  fi

  echo "Docker disk usage before prune:"
  docker system df || true

  if docker system prune -af; then
    # Same guard as reclaim_disk_before_pull's: `set -e` is suspended for an `if` condition
    # but not inside its body, so an unwritable state file would fail an otherwise green
    # deploy on its last line.
    date +%s > "${DOCKER_PRUNE_STATE_FILE}" \
      || echo "Warning: could not stamp ${DOCKER_PRUNE_STATE_FILE}; the next prune will run sooner." >&2
    echo "Docker disk usage after prune:"
    docker system df || true
    return 0
  fi

  echo "Warning: docker system prune failed; deployment succeeded but cleanup did not." >&2
  return 0
}

docker_data_root() {
  local root
  # A client-only `docker info` (daemon down) renders the template to an empty line AND
  # exits non-zero, so `cmd || echo fallback` would yield two lines and a path starting
  # with a newline. Strip first, then fall back.
  root="$(docker info --format '{{.DockerRootDir}}' 2>/dev/null || true)"
  root="${root//[$'\n\r']/}"
  printf '%s' "${root:-/var/lib/docker}"
}

# Free space in whole GiB on the filesystem holding $1. Fails if df cannot read the path.
# -P keeps df to one line per filesystem however long the device name is.
free_gib_for_path() {
  df -P -k "$1" 2>/dev/null | awk 'NR == 2 { printf "%d", $4 / 1048576 }'
}

# Reclaim disk BEFORE pulling, not only after a green deploy.
#
# run_periodic_docker_prune is the last line of this script, so it never runs on a deploy
# that failed. Once the box was full enough that `compose pull` died with "no space left on
# device", every later deploy died at the same pull and the cleanup that would have fixed it
# was permanently out of reach — the box could not recover without a console (#1862).
#
# Every failure here is non-fatal by construction. This runs under `set -euo pipefail`, and
# a deploy aborted by the disk check would be the same wedge wearing a different message, so
# each command that can fail is explicitly neutralised rather than left to `set -e`.
reclaim_disk_before_pull() {
  if ! [[ "${DOCKER_MIN_FREE_GIB}" =~ ^[0-9]+$ ]]; then
    echo "Skipping pre-pull reclaim: DOCKER_MIN_FREE_GIB must be an integer, got '${DOCKER_MIN_FREE_GIB}'." >&2
    return 0
  fi

  if [[ "${DOCKER_MIN_FREE_GIB}" -le 0 ]]; then
    return 0
  fi

  local root free after
  root="$(docker_data_root)"

  # `free_gib_for_path` is a pipeline, so under `pipefail` a failing df makes this bare
  # assignment non-zero and `set -e` would kill the deploy right here — in exactly the
  # low-disk conditions this function exists to survive.
  free="$(free_gib_for_path "${root}")" || free=""

  if [[ -z "${free}" ]]; then
    echo "Skipping pre-pull reclaim: could not read free space for '${root}'." >&2
    return 0
  fi

  if [[ "${free}" -ge "${DOCKER_MIN_FREE_GIB}" ]]; then
    echo "Pre-pull disk check: ${free}GiB free on ${root} (floor ${DOCKER_MIN_FREE_GIB}GiB)."
    return 0
  fi

  echo "Pre-pull disk check: ${free}GiB free on ${root}, under the ${DOCKER_MIN_FREE_GIB}GiB floor. Reclaiming."
  docker system df || true

  # `docker image prune -af`, deliberately NOT `docker system prune -af`, which the periodic
  # prune at the end of a green deploy uses.
  #
  # `system prune` removes stopped containers before it touches images. That is harmless at the
  # end of a green deploy, where every tier is up behind --wait, but this runs at an arbitrary
  # point — including the window after a reboot, before restart policies have fired. Stopped
  # containers are what `service_has_container` reads to tell "this image was retagged out from
  # under a live service, stop the sync" from "this service was never deployed here, skip it"
  # (#1577, #1646). Deleting them turns a genuine ECR break into a silent partial sync.
  #
  # Nothing is given up by narrowing it: on alpha when this wedged, containers accounted for
  # 4.238MB with 0B reclaimable, against 61.42GB reclaimable in images. `image prune -a` still
  # removes every image no container references, which is exactly the stale deploy tags. Images
  # pinned by a container, running or stopped, stay — and so does every named volume, and so
  # every database, since no volume is touched without --volumes.
  if docker image prune -af; then
    # Recording a cleanup that did happen. A failed stamp must not abort the deploy either.
    date +%s > "${DOCKER_PRUNE_STATE_FILE}" \
      || echo "Warning: could not stamp ${DOCKER_PRUNE_STATE_FILE}; the periodic prune will run again sooner." >&2

    after="$(free_gib_for_path "${root}")" || after=""
    if [[ -z "${after}" ]]; then
      # df working before the prune and failing after it says the box got worse, not better.
      echo "Warning: could not re-read free space on ${root} after the reclaim." >&2
    elif [[ "${after}" -lt "${DOCKER_MIN_FREE_GIB}" ]]; then
      echo "Warning: still ${after}GiB free after reclaim, under the ${DOCKER_MIN_FREE_GIB}GiB floor — the pull may fail." >&2
    else
      echo "Reclaimed: ${after}GiB free on ${root}."
    fi
  else
    echo "Warning: pre-pull docker image prune failed; continuing with ${free}GiB free." >&2
  fi

  return 0
}

# postgres/init-databases.sql only runs on fresh volume initialization, so databases
# added after the alpha volume was first created never come into existence. Reconcile:
# parse the CREATE DATABASE lines and create any database that is missing. Names are
# constrained to [A-Za-z0-9_]+ by the sed pattern, so interpolation into SQL is safe.
reconcile_databases() {
  local init_sql="${BACKEND_DIR}/postgres/init-databases.sql"
  if [[ ! -f "${init_sql}" ]]; then
    echo "Warning: ${init_sql} not found; skipping database reconciliation." >&2
    return 0
  fi

  echo "Ensuring postgres is up for database reconciliation"
  docker compose "${COMPOSE_ARGS[@]}" up -d --no-build postgres

  local attempt
  for attempt in $(seq 1 60); do
    if docker compose "${COMPOSE_ARGS[@]}" exec -T postgres \
        sh -c 'pg_isready -q -U "${POSTGRES_USER}"'; then
      break
    fi
    if [[ "${attempt}" -eq 60 ]]; then
      echo "postgres did not become ready within 60s; aborting before database reconciliation." >&2
      return 1
    fi
    sleep 1
  done

  # Read the full list before the loop: `docker compose exec` attaches the container
  # to the loop's stdin and drains it, so a `while read` fed by process substitution
  # silently stops after the first database. The execs also get </dev/null so they
  # can never consume anything meant for the shell.
  local dbs db exists
  mapfile -t dbs < <(sed -nE 's/^CREATE DATABASE ([A-Za-z0-9_]+);$/\1/p' "${init_sql}")
  echo "Reconciling ${#dbs[@]} databases from init-databases.sql"
  for db in "${dbs[@]}"; do
    [[ -n "${db}" ]] || continue
    exists="$(docker compose "${COMPOSE_ARGS[@]}" exec -T postgres \
      sh -c 'psql -U "${POSTGRES_USER}" -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = '"'"''"${db}"''"'"'"' </dev/null)"
    if [[ "${exists}" != "1" ]]; then
      echo "Creating missing database: ${db}"
      docker compose "${COMPOSE_ARGS[@]}" exec -T postgres \
        sh -c 'psql -U "${POSTGRES_USER}" -d postgres -c "CREATE DATABASE '"${db}"';"' </dev/null
    fi
  done
}

# Inject the real Prometheus scrape secret into the deployed prometheus.yml (#863).
# The committed file ships the local-dev default so `docker-compose up` works
# out of the box; here we swap it for the real secret from the alpha env so
# Prometheus authenticates against the pos-* /actuator/prometheus endpoints.
# Prometheus static config cannot read env vars, hence the in-place render. Runs
# in both deploy modes: config-only re-ships the observability tree, so the
# placeholder must be re-rendered every time the files are refreshed.
render_prometheus_secret() {
  local prom_config="${BACKEND_DIR}/observability/prometheus.yml"
  local scrape_pw
  # `|| true` as on the sibling reads at env_value/require_supplier_audit_key: `head -n1`
  # can exit before grep finishes writing, and under `set -o pipefail` that SIGPIPE would
  # abort the deploy over a value this function is prepared to find missing.
  scrape_pw="$(grep -E '^POS_SECURITY_METRICS_SCRAPE_PASSWORD=' "${ENV_FILE}" | head -n1 | cut -d= -f2- || true)"
  if [[ -n "${scrape_pw}" && -f "${prom_config}" ]]; then
    echo "Injecting Prometheus scrape secret into ${prom_config}"
    python3 - "${prom_config}" "${scrape_pw}" <<'PY'
import sys
path, secret = sys.argv[1], sys.argv[2]
# Strip one layer of surrounding quotes from the env value, if present.
if len(secret) >= 2 and secret[0] == secret[-1] and secret[0] in ("'", '"'):
    secret = secret[1:-1]
# The placeholder sits inside a YAML single-quoted scalar (password: '...'),
# so escape any single quote by doubling it — otherwise a secret containing '
# would break the YAML and crash Prometheus.
secret_yaml = secret.replace("'", "''")
with open(path) as fh:
    content = fh.read()
content = content.replace("durion-local-prom-scrape-password", secret_yaml)
with open(path, "w") as fh:
    fh.write(content)
PY
  else
    echo "Warning: POS_SECURITY_METRICS_SCRAPE_PASSWORD not set or ${prom_config} missing; Prometheus scrapes will use the committed default and likely 401." >&2
  fi
}

OBSERVABILITY_SERVICES=(
  jaeger
  prometheus
  otel-collector
  grafana
  loki
  promtail
  docker-socket-proxy
  cadvisor
  postgres-exporter
)

KAFKA_SERVICES=(
  kafka
  kafka-exporter
)

# Backend services start in dependency tiers (each tier gated on --wait) so a
# whole-stack recreate never launches ~20 JVMs at once — the resulting CPU
# starvation is what pushed startups past their healthcheck windows (#29527988342).

# Tier 1: platform core — everything else needs Eureka; security-service needs
# the event receiver at boot.
CORE_SERVICES=(
  eureka-server
  pos-event-receiver
)

# Tier 2: security + routing edge.
PLATFORM_SERVICES=(
  pos-api-gateway
  pos-security-service
)

# Tier 3: domain services, started in batches of DOMAIN_BATCH_SIZE.
# pos-vehicle-inventory is listed first so it lands in the same-or-earlier batch
# as pos-customer, which depends_on it (condition: service_started).
DOMAIN_SERVICES=(
  pos-vehicle-inventory
  pos-accounting
  pos-bulk-loader
  # The labor-guide mock vendor precedes pos-catalog, which is configured to reach it
  # (same-or-earlier-batch convention, like pos-vehicle-inventory before pos-customer).
  pos-reference-mock
  pos-catalog
  pos-customer
  pos-documents
  pos-image
  pos-inventory
  pos-invoice
  pos-location
  pos-marketing
  pos-mcp-server
  pos-order
  pos-people
  pos-people-contact
  pos-price
  pos-shop-manager
  pos-supplier
  pos-tax
  pos-warranty
  pos-workorder
)

DOMAIN_BATCH_SIZE=6
# Upper bound per tier/batch: worst-case healthcheck window plus slack. The
# widest window is pos-mcp-server's: 600s start_period + 20 retries, each up
# to interval (5s) + probe timeout (3s) = 760s.
WAIT_TIMEOUT=840

BACKEND_SERVICES=(
  "${CORE_SERVICES[@]}"
  "${PLATFORM_SERVICES[@]}"
  "${DOMAIN_SERVICES[@]}"
)

verify_checksum "prod override" "${PROD_OVERRIDE}" "${PROD_OVERRIDE_SHA256:-}"
verify_checksum "base compose" "${BACKEND_DIR}/docker-compose.yml" "${BASE_COMPOSE_SHA256:-}"

if [[ "${MODE}" == "config-only" ]]; then
  # The compose interpolation still needs these; a full deploy wrote them to the env
  # file, and config-only never changes them.
  require_env_entry BACKEND_TAG
  require_env_entry ECR_REGISTRY
  require_env_entry SECURITY_SEED_ADMIN_PASSWORD_HASH
  require_supplier_audit_key
  ecr_login "$(env_value ECR_REGISTRY)"
else
  if grep -q '^BACKEND_TAG=' "${ENV_FILE}"; then
    sed -i "s/^BACKEND_TAG=.*/BACKEND_TAG=${IMAGE_TAG}/" "${ENV_FILE}"
  else
    printf '\nBACKEND_TAG=%s\n' "${IMAGE_TAG}" >> "${ENV_FILE}"
  fi

  if [[ -z "${SECURITY_SEED_ADMIN_PASSWORD_HASH:-}" ]]; then
    echo "SECURITY_SEED_ADMIN_PASSWORD_HASH is required."
    exit 1
  fi
  if [[ ! "${SECURITY_SEED_ADMIN_PASSWORD_HASH}" =~ ^\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}$ ]]; then
    echo "SECURITY_SEED_ADMIN_PASSWORD_HASH must be a valid BCrypt hash (expected prefix \$2a\$, \$2b\$, or \$2y\$)." >&2
    exit 1
  fi

  QUOTED_SECURITY_SEED_ADMIN_PASSWORD_HASH="$(env_single_quote "${SECURITY_SEED_ADMIN_PASSWORD_HASH}")"
  if grep -q '^SECURITY_SEED_ADMIN_PASSWORD_HASH=' "${ENV_FILE}"; then
    sed -i "s|^SECURITY_SEED_ADMIN_PASSWORD_HASH=.*|SECURITY_SEED_ADMIN_PASSWORD_HASH=${QUOTED_SECURITY_SEED_ADMIN_PASSWORD_HASH}|" "${ENV_FILE}"
  else
    printf 'SECURITY_SEED_ADMIN_PASSWORD_HASH=%s\n' "${QUOTED_SECURITY_SEED_ADMIN_PASSWORD_HASH}" >> "${ENV_FILE}"
  fi

  ECR_REGISTRY="$(aws sts get-caller-identity --query Account --output text).dkr.ecr.us-east-1.amazonaws.com"
  if grep -q '^ECR_REGISTRY=' "${ENV_FILE}"; then
    sed -i "s|^ECR_REGISTRY=.*|ECR_REGISTRY=${ECR_REGISTRY}|" "${ENV_FILE}"
  else
    printf 'ECR_REGISTRY=%s\n' "${ECR_REGISTRY}" >> "${ENV_FILE}"
  fi

  require_supplier_audit_key

  ecr_login "${ECR_REGISTRY}"
fi

# Behind every guard above and ahead of every pull below, in both modes. Behind the guards
# because require_env_entry, require_supplier_audit_key and the BCrypt-shape check all exit 1
# on a promise that nothing on the host was touched; reclaiming first would break that promise
# on exactly the runs that keep it most carefully. Ahead of the pulls because --config-only
# pulls too (#1577) and exits before the periodic prune, so it has never had a reclaim of its
# own — every `compose pull` in either mode is downstream of COMPOSE_ARGS, defined just below.
reclaim_disk_before_pull

cd "${BACKEND_DIR}"

COMPOSE_ARGS=(
  -f docker-compose.yml
  -f "${PROD_OVERRIDE}"
  --env-file "${ENV_FILE}"
)

# Config-only sync (#1457): no --force-recreate for the JVM services — compose's
# config-hash diff recreates exactly the containers whose merged config changed and
# leaves the rest running. It does pull, contrary to how this once worked: the images
# are almost always already on the box, but a sync that adds a service to the compose
# lists needs the one image that is not, and silently skipping that pull is what broke
# #1577. The full deploy's tier order is kept so a sweeping change (e.g. to the shared
# logging anchor, which touches every service) still starts JVMs in gated batches
# instead of all at once. Observability containers ARE force-recreated (mirroring the
# full deploy): their configs are bind-mounted files this sync just refreshed, which
# compose's config hash cannot see. kafka-topic-init and database reconciliation also
# run, so topic-definition and init-databases.sql changes — config-only changes too —
# take effect.
if [[ "${MODE}" == "config-only" ]]; then
  echo "Config-only sync: recreating only containers whose compose config changed."

  # Resolve every image up front, before a single container is touched. Without this the
  # run half-applied: it recreated the early tiers, then failed in the batch holding the
  # service whose image was missing and left the later tiers on their old containers
  # (#1577). Pulls are no-ops for images already present, so this costs nothing on a
  # normal sync. What happens when one does not resolve depends on whether the service is
  # already on this box — see missing_backend_images above.
  echo "Verifying every backend image exists at the pinned BACKEND_TAG"
  SKIPPED_SERVICES=()
  if ! docker compose "${COMPOSE_ARGS[@]}" pull --quiet "${BACKEND_SERVICES[@]}"; then
    mapfile -t MISSING_SERVICES < <(missing_backend_images)

    UNDEPLOYABLE_SERVICES=()
    # Guarded on the count rather than expanded with the ${arr[@]+"${arr[@]}"} idiom: mapfile
    # always assigns the array, so the only thing to defend against is expanding it empty
    # under `set -u`, and the count guard says that plainly and matches the two below.
    if [[ ${#MISSING_SERVICES[@]} -gt 0 ]]; then
      for SVC in "${MISSING_SERVICES[@]}"; do
        if service_has_container "${SVC}"; then
          UNDEPLOYABLE_SERVICES+=("${SVC}")
        else
          SKIPPED_SERVICES+=("${SVC}")
        fi
      done
    fi

    if [[ ${#UNDEPLOYABLE_SERVICES[@]} -gt 0 ]]; then
      echo "" >&2
      echo "ERROR: these services are on this box but have no image at BACKEND_TAG=$(env_value BACKEND_TAG):" >&2
      printf '  %s\n' "${UNDEPLOYABLE_SERVICES[@]}" >&2
      echo "Nothing has been changed on this host." >&2
      echo "" >&2
      echo "A service with a container here was deployed at this tag, so its image existed" >&2
      echo "and has since been retagged or deleted in ECR. Run the build-push-ecr workflow on" >&2
      echo "main with deploy_alpha=true, which republishes every service at a new tag and" >&2
      echo "then deploys it." >&2
      exit 1
    fi

    if [[ ${#SKIPPED_SERVICES[@]} -gt 0 ]]; then
      echo "" >&2
      echo "WARNING: skipping services that have never been deployed on this box and have no" >&2
      echo "image at BACKEND_TAG=$(env_value BACKEND_TAG):" >&2
      printf '  %s\n' "${SKIPPED_SERVICES[@]}" >&2
      echo "A service added to the compose files is published only by the build that runs" >&2
      echo "after the merge, so it cannot have an image at the tag this box is pinned to." >&2
      echo "The rest of this sync is applied as usual; the skipped services come up with the" >&2
      echo "build-push-ecr deploy for the same commit, which moves the box to a tag that has" >&2
      echo "them." >&2
      echo "" >&2
    else
      echo "Batch pull failed but every image resolved on retry; continuing."
    fi
  fi

  # Compose reads a bare `up -d` as "every service", so a tier emptied by the skip list
  # must not reach the command line.
  if [[ ${#SKIPPED_SERVICES[@]} -gt 0 ]]; then
    mapfile -t CORE_SERVICES < <(without_skipped "${CORE_SERVICES[@]}")
    mapfile -t PLATFORM_SERVICES < <(without_skipped "${PLATFORM_SERVICES[@]}")
    mapfile -t DOMAIN_SERVICES < <(without_skipped "${DOMAIN_SERVICES[@]}")
  fi

  echo "Applying config to postgres"
  docker compose "${COMPOSE_ARGS[@]}" up -d --no-build --wait --wait-timeout "${WAIT_TIMEOUT}" postgres

  render_prometheus_secret

  echo "Applying config to observability services: ${OBSERVABILITY_SERVICES[*]}"
  docker compose "${COMPOSE_ARGS[@]}" up -d --no-build --force-recreate "${OBSERVABILITY_SERVICES[@]}"

  echo "Applying config to Kafka broker and exporter"
  docker compose "${COMPOSE_ARGS[@]}" up -d --no-build --wait --wait-timeout "${WAIT_TIMEOUT}" kafka

  echo "Provisioning Kafka topics"
  docker compose "${COMPOSE_ARGS[@]}" run --rm --no-deps kafka-topic-init

  docker compose "${COMPOSE_ARGS[@]}" up -d --no-build kafka-exporter

  reconcile_databases

  if [[ ${#CORE_SERVICES[@]} -gt 0 ]]; then
    echo "Applying config to core services: ${CORE_SERVICES[*]}"
    docker compose "${COMPOSE_ARGS[@]}" up -d --no-build --wait --wait-timeout "${WAIT_TIMEOUT}" "${CORE_SERVICES[@]}"
  fi

  if [[ ${#PLATFORM_SERVICES[@]} -gt 0 ]]; then
    echo "Applying config to platform services: ${PLATFORM_SERVICES[*]}"
    docker compose "${COMPOSE_ARGS[@]}" up -d --no-build --wait --wait-timeout "${WAIT_TIMEOUT}" "${PLATFORM_SERVICES[@]}"
  fi

  for ((i = 0; i < ${#DOMAIN_SERVICES[@]}; i += DOMAIN_BATCH_SIZE)); do
    BATCH=("${DOMAIN_SERVICES[@]:i:DOMAIN_BATCH_SIZE}")
    echo "Applying config to domain services (batch $((i / DOMAIN_BATCH_SIZE + 1))): ${BATCH[*]}"
    docker compose "${COMPOSE_ARGS[@]}" up -d --no-build --wait --wait-timeout "${WAIT_TIMEOUT}" "${BATCH[@]}"
  done

  docker compose "${COMPOSE_ARGS[@]}" ps
  exit 0
fi

DESIRED_POSTGRES_IMAGE="$(
  docker compose "${COMPOSE_ARGS[@]}" config \
    | sed -n '/^  postgres:/,/^[^ ]/p' \
    | awk '/image:/ {print $2; exit}'
)"
CURRENT_POSTGRES_CONTAINER_ID="$(docker compose "${COMPOSE_ARGS[@]}" ps -q postgres 2>/dev/null || true)"
CURRENT_POSTGRES_IMAGE=""
if [[ -n "${CURRENT_POSTGRES_CONTAINER_ID}" ]]; then
  CURRENT_POSTGRES_IMAGE="$(docker inspect -f '{{.Config.Image}}' "${CURRENT_POSTGRES_CONTAINER_ID}" 2>/dev/null || true)"
fi
if [[ -n "${DESIRED_POSTGRES_IMAGE}" && "${CURRENT_POSTGRES_IMAGE}" != "${DESIRED_POSTGRES_IMAGE}" ]]; then
  echo "Reconciling postgres image: current='${CURRENT_POSTGRES_IMAGE:-<none>}' desired='${DESIRED_POSTGRES_IMAGE}'"
  docker compose "${COMPOSE_ARGS[@]}" pull postgres
  docker compose "${COMPOSE_ARGS[@]}" up -d --no-build --force-recreate postgres
fi

render_prometheus_secret

echo "Pulling observability services: ${OBSERVABILITY_SERVICES[*]}"
docker compose "${COMPOSE_ARGS[@]}" pull --quiet "${OBSERVABILITY_SERVICES[@]}"

echo "Starting observability services: ${OBSERVABILITY_SERVICES[*]}"
docker compose "${COMPOSE_ARGS[@]}" up -d --no-build --force-recreate "${OBSERVABILITY_SERVICES[@]}"

# Kafka domain-event backbone (ADR-0044, #838). Broker data lives on the
# kafka-data volume, so --force-recreate is safe. --wait blocks on the
# compose healthcheck before topics are provisioned.
echo "Pulling Kafka services: ${KAFKA_SERVICES[*]}"
docker compose "${COMPOSE_ARGS[@]}" pull --quiet "${KAFKA_SERVICES[@]}" kafka-topic-init

echo "Starting Kafka broker"
docker compose "${COMPOSE_ARGS[@]}" up -d --no-build --force-recreate --wait kafka

echo "Provisioning Kafka topics"
docker compose "${COMPOSE_ARGS[@]}" run --rm --no-deps kafka-topic-init

echo "Starting Kafka exporter"
docker compose "${COMPOSE_ARGS[@]}" up -d --no-build --force-recreate kafka-exporter

reconcile_databases

echo "Pulling backend services: ${BACKEND_SERVICES[*]}"
docker compose "${COMPOSE_ARGS[@]}" pull --quiet "${BACKEND_SERVICES[@]}"

# --wait blocks until every named service is healthy and fails the deploy if one
# goes unhealthy — a real gate instead of exiting 0 with half the stack down.
echo "Starting core services: ${CORE_SERVICES[*]}"
docker compose "${COMPOSE_ARGS[@]}" up -d --no-build --force-recreate \
  --wait --wait-timeout "${WAIT_TIMEOUT}" "${CORE_SERVICES[@]}"

echo "Starting platform services: ${PLATFORM_SERVICES[*]}"
docker compose "${COMPOSE_ARGS[@]}" up -d --no-build --force-recreate \
  --wait --wait-timeout "${WAIT_TIMEOUT}" "${PLATFORM_SERVICES[@]}"

for ((i = 0; i < ${#DOMAIN_SERVICES[@]}; i += DOMAIN_BATCH_SIZE)); do
  BATCH=("${DOMAIN_SERVICES[@]:i:DOMAIN_BATCH_SIZE}")
  echo "Starting domain services (batch $((i / DOMAIN_BATCH_SIZE + 1))): ${BATCH[*]}"
  docker compose "${COMPOSE_ARGS[@]}" up -d --no-build --force-recreate \
    --wait --wait-timeout "${WAIT_TIMEOUT}" "${BATCH[@]}"
done

docker compose "${COMPOSE_ARGS[@]}" ps
run_periodic_docker_prune
