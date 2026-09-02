#!/usr/bin/env bash
set -euo pipefail

# Guards against drift between the lists a service must appear in to be deployed.
#
# Wiring a service for deployment means adding it to five independent places,
# none of which references any of the others:
#
#   1. root docker-compose.yml           — a service with build.context ./<module>
#   2. .github/workflows/build-push-ecr.yml — ALL_SERVICES_JSON (module names)
#   3. deployment/alpha/docker-compose.prod.yml — the alpha image override
#   4. deployment/alpha/deploy-backend.sh — CORE/PLATFORM/DOMAIN start tiers
#   5. observability/prometheus.yml       — a scrape job
#
# Nothing enforces that they agree, and the same drift has been fixed by hand
# three times (pos-supplier, pos-marketing, and eight services missing scrape
# jobs — ba17a81, 104ccdb). This check makes the next omission a red build
# instead of a service that reaches main half-wired (#1580).
#
# The compose file is the pivot: a backend service is one whose build.context
# points at a ./pos-* module. That derives the module <-> compose-service
# mapping from the repo rather than restating it here, so pos-service-discovery
# resolving to the compose service `eureka-server` needs no special case, and
# pos-frontend (context ../durion-positivity-frontend) drops out on its own.
#
# The universe of services that MUST be wired is anchored on "has a Dockerfile",
# not on "has a compose service". Deploy drift happens at the transition from
# not-deployed to deployed, and anchoring on the compose service can only see
# modules that already completed the transition. Modules that are built but
# deliberately not deployed carry an ALLOWLIST entry below with a status, so
# "this module became real and nobody wired it up" is a reviewed diff rather
# than silence.
#
# Gateway routes are checked too: a route pointing at a service in no deploy
# list can only 503, and it is the earliest available drift signal — a route
# usually lands before the deploy wiring does.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

COMPOSE="docker-compose.yml"
ECR_WORKFLOW=".github/workflows/build-push-ecr.yml"
PROD_OVERRIDE="deployment/alpha/docker-compose.prod.yml"
DEPLOY_SCRIPT="deployment/alpha/deploy-backend.sh"
PROMETHEUS="observability/prometheus.yml"
GATEWAY_CONFIG="pos-api-gateway/src/main/resources/application.yml"

for f in "$COMPOSE" "$ECR_WORKFLOW" "$PROD_OVERRIDE" "$DEPLOY_SCRIPT" "$PROMETHEUS" "$GATEWAY_CONFIG"; do
  if [[ ! -f "$f" ]]; then
    echo "ERROR: expected file not found: ${f}"
    exit 1
  fi
done

# Modules with a Dockerfile that are deliberately not deployed.
#
# `deploy`  waives the five deploy-list entries.
# `route`   additionally waives an active gateway route pointing at the module.
#
# Placeholder status is not a durable property; noticing when it stops being
# true is the whole point of the list. An entry whose module is in fact wired
# up, or no longer has a Dockerfile, is reported as stale below.
declare -A ALLOWLIST=(
  [pos-inquiry]="deploy,route|placeholder — no controllers, entities, or migrations; its lb://INQUIRY route is dead until it deploys"
  [pos-vehicle-fitment]="deploy,route|built (3 controllers, 10 entities, 2 migrations) but not yet deployed; its lb://VEHICLE-FITMENT route is dead until it deploys"
  [pos-vehicle-reference-nhtsa]="deploy|built but not yet deployed"
  [pos-vehicle-reference-carapi]="deploy|built but not yet deployed"
)

# Non-backend compose services tolerated in the alpha override. pos-frontend is
# built from a sibling repository, so it has no module here to reconcile against.
TOLERATED_OVERRIDE_EXTRAS=("pos-frontend")

status=0

report() {
  # report <headline> <newline-separated items> <remediation>
  # Blank lines are dropped first: `comm` on an empty list still sees the single
  # empty line `echo ""` produces, which would otherwise read as a finding.
  local headline="$1" remedy="$3"
  local items
  items="$(echo "$2" | grep -v '^[[:space:]]*$' || true)"
  [[ -z "$items" ]] && return 0
  echo "ERROR: ${headline}"
  echo "$items" | sed 's/^/  - /'
  echo "  ${remedy}"
  echo
  status=1
}

# --- inventories -------------------------------------------------------------

# Top-level service keys inside a compose file's `services:` block. Commented-out
# blocks (`  # pos-inquiry:`) do not match, which is what we want: a disabled
# service is not deployed.
compose_service_keys() {
  awk '
    /^[A-Za-z_][A-Za-z0-9_-]*:/ { in_services = ($0 ~ /^services:[[:space:]]*$/); next }
    in_services && /^  [A-Za-z0-9_.-]+:[[:space:]]*$/ {
      sub(/:[[:space:]]*$/, ""); sub(/^  /, ""); print
    }
  ' "$1" | sort -u
}

# "<compose-service> <module>" for every compose service built from a ./pos-*
# directory. This is the authoritative "is deployed" set.
compose_backend_pairs=$(
  awk '
    /^[A-Za-z_][A-Za-z0-9_-]*:/ { in_services = ($0 ~ /^services:[[:space:]]*$/); next }
    !in_services { next }
    /^  [A-Za-z0-9_.-]+:[[:space:]]*$/ { svc = $0; sub(/:[[:space:]]*$/, "", svc); sub(/^  /, "", svc); next }
    # A trailing "# comment" after the path is common here, so match on the
    # token rather than to end of line.
    /^[[:space:]]+context:[[:space:]]*\.\// {
      ctx = $2; sub(/^\.\//, "", ctx)
      if (svc != "" && ctx ~ /^pos-[A-Za-z0-9-]+$/) { print svc, ctx; svc = "" }
    }
  ' "$COMPOSE" | sort -u
)

compose_backend_services=$(echo "$compose_backend_pairs" | awk '{print $1}' | sort -u)
compose_backend_modules=$(echo "$compose_backend_pairs" | awk '{print $2}' | sort -u)

dockerfile_modules=$(
  find . -maxdepth 2 -mindepth 2 -name Dockerfile -not -path './target/*' \
    | sed 's|^\./||; s|/Dockerfile$||' \
    | grep '^pos-' \
    | sort -u
)

ecr_services=$(
  sed -n "s/^[[:space:]]*ALL_SERVICES_JSON='\(.*\)'[[:space:]]*$/\1/p" "$ECR_WORKFLOW" \
    | jq -r '.[]' \
    | sort -u
)

if [[ -z "$ecr_services" ]]; then
  echo "ERROR: could not parse ALL_SERVICES_JSON from ${ECR_WORKFLOW}"
  exit 1
fi

override_services=$(compose_service_keys "$PROD_OVERRIDE")

# CORE + PLATFORM + DOMAIN. The remaining arrays in the script (observability,
# kafka) are infrastructure and are deliberately not part of this union.
deploy_tier_services=$(
  for arr in CORE_SERVICES PLATFORM_SERVICES DOMAIN_SERVICES; do
    sed -n "/^${arr}=(/,/^)/p" "$DEPLOY_SCRIPT" \
      | sed '1d;$d; s/#.*//' \
      | tr -d ' \t' \
      | grep -v '^$' || true
  done | sort -u
)

if [[ -z "$deploy_tier_services" ]]; then
  echo "ERROR: could not parse CORE/PLATFORM/DOMAIN_SERVICES from ${DEPLOY_SCRIPT}"
  exit 1
fi

# Scrape job names. `eureka-server` is a job name that is not pos-* but is a
# backend service; matching on compose service names rather than a pos- prefix
# handles it without a special case.
prometheus_jobs=$(
  sed -n "s/^[[:space:]]*-\{0,1\}[[:space:]]*job_name:[[:space:]]*['\"]\{0,1\}\([A-Za-z0-9_.-]*\)['\"]\{0,1\}[[:space:]]*$/\1/p" "$PROMETHEUS" \
    | sort -u
)

if [[ -z "$prometheus_jobs" ]]; then
  echo "ERROR: could not parse job_name entries from ${PROMETHEUS}"
  exit 1
fi

# Eureka virtual host name -> module, from each module's spring.application.name.
# lb://NAME resolves through Eureka to the service registered under NAME, so this
# is the mapping the gateway actually uses.
app_name_pairs=$(
  for dir in pos-*/; do
    module="${dir%/}"
    config="${dir}src/main/resources/application.yml"
    [[ -f "$config" ]] || continue
    name=$(awk '
      /^spring:[[:space:]]*$/ { in_spring = 1; next }
      /^[A-Za-z]/ { in_spring = 0; in_app = 0 }
      in_spring && /^  application:[[:space:]]*$/ { in_app = 1; next }
      in_spring && /^  [A-Za-z]/ { in_app = 0 }
      in_spring && in_app && /^    name:/ { print $2; exit }
    ' "$config")
    # `[[ ... ]] && echo` would leave a non-zero status on the loop when the last
    # module has no name (pos-events has none), which pipefail turns into a
    # spurious failure of the whole script.
    if [[ -n "$name" ]]; then
      echo "$(echo "$name" | tr '[:lower:]' '[:upper:]') ${module}"
    fi
  done | sort -u
)

gateway_route_targets=$(
  sed -n 's|^[[:space:]]*uri:[[:space:]]*lb://\([A-Za-z0-9_.-]*\)[[:space:]]*$|\1|p' "$GATEWAY_CONFIG" \
    | sort -u
)

# --- allowlist hygiene -------------------------------------------------------

allowlist_modules=$(printf '%s\n' "${!ALLOWLIST[@]}" | sort -u)

waives() {
  # waives <module> <deploy|route>
  local entry="${ALLOWLIST[$1]:-}"
  [[ -z "$entry" ]] && return 1
  [[ ",${entry%%|*}," == *",$2,"* ]]
}

stale_missing_module=$(comm -23 <(echo "$allowlist_modules") <(echo "$dockerfile_modules") || true)
report "allowlisted module has no Dockerfile — it is outside this check's universe" \
  "$stale_missing_module" \
  "Drop the ALLOWLIST entry in $0; a module without a Dockerfile cannot be image-built."

stale_now_deployed=$(comm -12 <(echo "$allowlist_modules") <(echo "$compose_backend_modules") || true)
report "allowlisted module is deployed after all — the entry is stale" \
  "$stale_now_deployed" \
  "Drop the ALLOWLIST entry in $0 so this module is checked like every other service."

# --- the five deploy lists ---------------------------------------------------

required_modules=$(
  echo "$dockerfile_modules" | while read -r module; do
    [[ -n "$module" ]] || continue
    if waives "$module" deploy; then continue; fi
    echo "$module"
  done | sort -u
)

missing_compose=$(comm -23 <(echo "$required_modules") <(echo "$compose_backend_modules") || true)
report "module has a Dockerfile but no compose service" \
  "$missing_compose" \
  "Add a service to ${COMPOSE} with build.context ./<module>, or add an ALLOWLIST entry in $0 saying why it is not deployed."

# The other direction. Anchoring the universe on the Dockerfile means a module
# that loses one drops out of every check above rather than failing them, so it
# needs its own: a deployed service whose Dockerfile is gone builds nothing.
missing_dockerfile=$(comm -13 <(echo "$dockerfile_modules") <(echo "$compose_backend_modules") || true)
report "module is wired for deployment but has no Dockerfile" \
  "$missing_dockerfile" \
  "Restore <module>/Dockerfile, or remove the module from all five deploy lists. The ECR build resolves the image from <module>/Dockerfile and fails without it."

check_list() {
  # check_list <label> <expected-newline-list> <actual-newline-list> <remedy-missing> <remedy-stale>
  local label="$1" expected="$2" actual="$3" remedy_missing="$4" remedy_stale="$5"
  report "${label}: missing entries" "$(comm -23 <(echo "$expected") <(echo "$actual") || true)" "$remedy_missing"
  report "${label}: entries for services that are not deployed" "$(comm -13 <(echo "$expected") <(echo "$actual") || true)" "$remedy_stale"
}

# ECR is keyed by module name; the other three by compose service name.
check_list "ALL_SERVICES_JSON (${ECR_WORKFLOW})" \
  "$compose_backend_modules" "$ecr_services" \
  "Add the module to ALL_SERVICES_JSON; without it the full-rebuild branch never builds its image." \
  "Remove the entry, or restore the module's compose service."

# The override and the scrape config also carry infrastructure; restrict the
# reverse direction to names that look like backend services.
override_backend=$(echo "$override_services" | grep -E '^(pos-.*|eureka-server)$' || true)
if [[ ${#TOLERATED_OVERRIDE_EXTRAS[@]} -gt 0 ]]; then
  override_backend=$(
    echo "$override_backend" \
      | grep -vxF -f <(printf '%s\n' "${TOLERATED_OVERRIDE_EXTRAS[@]}") || true
  )
fi
check_list "alpha image override (${PROD_OVERRIDE})" \
  "$compose_backend_services" "$override_backend" \
  "Add an image override so the alpha deploy pulls this service from ECR instead of building it." \
  "Remove the override, or restore the service in ${COMPOSE}."

check_list "alpha start order (${DEPLOY_SCRIPT})" \
  "$compose_backend_services" "$deploy_tier_services" \
  "Add the service to CORE_SERVICES, PLATFORM_SERVICES, or DOMAIN_SERVICES; a service in no tier is never started." \
  "Remove the entry, or restore the service in ${COMPOSE}."

prometheus_backend=$(echo "$prometheus_jobs" | grep -E '^(pos-.*|eureka-server)$' || true)
check_list "Prometheus scrape jobs (${PROMETHEUS})" \
  "$compose_backend_services" "$prometheus_backend" \
  "Add a scrape job; without it the service emits metrics nothing collects." \
  "Remove the job, or restore the service in ${COMPOSE}."

# --- gateway routes ----------------------------------------------------------

dead_routes=""
unmapped_routes=""
while read -r target; do
  [[ -n "$target" ]] || continue
  module=$(echo "$app_name_pairs" | awk -v n="$target" '$1 == n {print $2}' | head -1)
  if [[ -z "$module" ]]; then
    unmapped_routes+="lb://${target}"$'\n'
    continue
  fi
  if echo "$compose_backend_modules" | grep -qxF "$module"; then continue; fi
  if waives "$module" route; then continue; fi
  dead_routes+="lb://${target} -> ${module}"$'\n'
done <<< "$gateway_route_targets"

report "gateway route points at a service that is in no deploy list" \
  "$dead_routes" \
  "Wire the service up for deployment, drop the route from ${GATEWAY_CONFIG} (and its springdoc group), or add a \`route\` waiver to the ALLOWLIST in $0. A route to an undeployed service can only 503."

report "gateway route target matches no module's spring.application.name" \
  "$unmapped_routes" \
  "lb://NAME resolves through Eureka to the service registered under NAME. Fix the route, or the module's spring.application.name."

# --- host port collisions -----------------------------------------------------

# Two compose services publishing the same host port is not a parse error and not a
# healthcheck failure: compose accepts it, and the second container to start dies at
# `docker start` with "port is already allocated". On alpha that aborts the deploy with the
# whole start batch left in Created — which is how pos-reference-mock, published on
# pos-people-contact's 8095, took six services down (#1646). Nothing else in this repo looks
# at the published-port space, so check it here.
duplicate_host_ports="$(
  awk '
    /^  [a-zA-Z0-9_-]+:$/ { svc = $1; sub(/:$/, "", svc) }
    /^[[:space:]]*-[[:space:]]*"?[0-9.:]+:[0-9]+"?([[:space:]]|\/|$)/ {
      line = $0
      gsub(/[",]/, "", line)
      sub(/^[[:space:]]*-[[:space:]]*/, "", line)
      sub(/\/.*$/, "", line)          # drop a /tcp or /udp suffix
      n = split(line, parts, ":")
      if (n < 2) next                 # a bare container port publishes nothing
      print parts[n - 1], svc         # host port is the field before the container port
    }
  ' "$COMPOSE" \
    | sort \
    | awk '{ ports[$1] = ports[$1] " " $2; count[$1]++ }
           END { for (p in ports) if (count[p] > 1) print "host port " p " ->" ports[p] }' \
    | sort
)"

report "two compose services publish the same host port" \
  "$duplicate_host_ports" \
  "Give one of them a free host port. Compose will not complain, but the second container to start fails with \"port is already allocated\" and takes its whole alpha start batch with it."

# --- result ------------------------------------------------------------------

if [[ $status -eq 0 ]]; then
  deployed_count=$(echo "$compose_backend_modules" | grep -c . || true)
  allowlisted_count=$(echo "$allowlist_modules" | grep -c . || true)
  echo "PASS: ${deployed_count} deployed services are registered in all five deploy lists" \
       "(${allowlisted_count} built-but-undeployed modules allowlisted)"
fi

exit "$status"
