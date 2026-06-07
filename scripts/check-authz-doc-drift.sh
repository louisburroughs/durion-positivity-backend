#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DURION_DIR="$(cd "${ROOT_DIR}/.." && pwd)/durion"

if [[ ! -d "${DURION_DIR}" ]]; then
  echo "FAIL: expected sibling durion repo at ${DURION_DIR}" >&2
  exit 1
fi

failures=0

if [[ -n "${SEARCH_TOOL:-}" ]]; then
  if [[ "${SEARCH_TOOL}" != "rg" && "${SEARCH_TOOL}" != "grep" ]]; then
    echo "FAIL: SEARCH_TOOL must be 'rg' or 'grep' (was '${SEARCH_TOOL}')." >&2
    exit 1
  fi
elif command -v rg >/dev/null 2>&1; then
  SEARCH_TOOL="rg"
else
  SEARCH_TOOL="grep"
fi

search_quiet() {
  local pattern="$1"
  shift
  if [[ "${SEARCH_TOOL}" == "rg" ]]; then
    rg -q --fixed-strings -- "${pattern}" "$@"
  else
    grep -Fq -- "${pattern}" "$@"
  fi
}

search_with_lines() {
  local pattern="$1"
  shift
  if [[ "${SEARCH_TOOL}" == "rg" ]]; then
    rg -n --fixed-strings -- "${pattern}" "$@"
  else
    grep -Fn -- "${pattern}" "$@"
  fi
}

check() {
  local description="$1"
  shift
  if "$@"; then
    echo "PASS: ${description}"
  else
    echo "FAIL: ${description}" >&2
    failures=$((failures + 1))
  fi
}

require_match() {
  local description="$1"
  local pattern="$2"
  shift 2
  check "${description}" search_quiet "${pattern}" "$@"
}

reject_match() {
  local description="$1"
  local pattern="$2"
  shift 2
  if search_with_lines "${pattern}" "$@" >/dev/null; then
    echo "FAIL: ${description}" >&2
    search_with_lines "${pattern}" "$@" >&2 || true
    failures=$((failures + 1))
  else
    echo "PASS: ${description}"
  fi
}

security_catalog_version="$(sed -n 's/.*CATALOG_VERSION = \([0-9][0-9]*\).*/\1/p' \
  "${ROOT_DIR}/pos-security-service/src/main/java/com/positivity/securityservice/internal/enums/PermissionCode.java" | head -n1)"
gateway_catalog_version="$(sed -n 's/.*CATALOG_VERSION = \([0-9][0-9]*\).*/\1/p' \
  "${ROOT_DIR}/pos-api-gateway/src/main/java/com/positivity/gateway/config/GatewayPermissionCatalog.java" | head -n1)"

if [[ -n "${security_catalog_version}" && -n "${gateway_catalog_version}" && "${security_catalog_version}" == "${gateway_catalog_version}" ]]; then
  echo "PASS: permission catalog versions match (${security_catalog_version})"
else
  echo "FAIL: permission catalog versions differ (security=${security_catalog_version:-missing}, gateway=${gateway_catalog_version:-missing})" >&2
  failures=$((failures + 1))
fi

ACTIVE_DOCS=(
  "${DURION_DIR}/docs/architecture/AUTHORIZATION_MODEL.md"
  "${DURION_DIR}/docs/architecture/API_SECURITY_ARCHITECTURE.md"
  "${DURION_DIR}/docs/adr/0040-roles-jwt-permission-governance-policy.adr.md"
  "${ROOT_DIR}/pos-security-service/docs/AUTH_TOKEN_USAGE_GUIDE.md"
  "${ROOT_DIR}/pos-security-service/docs/security-service-guide.md"
)

require_match "token guide documents POST /v1/auth/login" "POST /security-service/v1/auth/login" "${ROOT_DIR}/pos-security-service/docs/AUTH_TOKEN_USAGE_GUIDE.md"
require_match "token guide documents POST /v1/auth/token-pair" "POST /security-service/v1/auth/token-pair" "${ROOT_DIR}/pos-security-service/docs/AUTH_TOKEN_USAGE_GUIDE.md"
require_match "token guide marks token-pair as privileged internal issuance" "This is a privileged internal issuance endpoint." "${ROOT_DIR}/pos-security-service/docs/AUTH_TOKEN_USAGE_GUIDE.md"
require_match "token-pair curl example includes authorization header" "-H \"Authorization: Bearer <internal-issuer-token>\"" "${ROOT_DIR}/pos-security-service/docs/AUTH_TOKEN_USAGE_GUIDE.md"
require_match "token guide documents GET /v1/auth/validate" "GET /security-service/v1/auth/validate" "${ROOT_DIR}/pos-security-service/docs/AUTH_TOKEN_USAGE_GUIDE.md"
require_match "token guide documents DELETE /v1/auth/revoke" "DELETE /security-service/v1/auth/revoke" "${ROOT_DIR}/pos-security-service/docs/AUTH_TOKEN_USAGE_GUIDE.md"
require_match "token guide documents accessToken response field" "\"accessToken\"" "${ROOT_DIR}/pos-security-service/docs/AUTH_TOKEN_USAGE_GUIDE.md"
require_match "token guide documents refreshToken response field" "\"refreshToken\"" "${ROOT_DIR}/pos-security-service/docs/AUTH_TOKEN_USAGE_GUIDE.md"
require_match "security guide keeps token-pair in internal issuance section" "### Internal token issuance" "${ROOT_DIR}/pos-security-service/docs/security-service-guide.md"
require_match "security guide documents token-pair internal permission" '- `security:token:issue_internal`' "${ROOT_DIR}/pos-security-service/docs/security-service-guide.md"

reject_match "active docs do not claim access tokens carry authorities arrays" "access tokens carry authorities" "${ACTIVE_DOCS[@]}"
reject_match "active docs do not instruct POST validate" "POST /security-service/v1/auth/validate" "${ACTIVE_DOCS[@]}"
reject_match "active docs do not instruct POST revoke" "POST /security-service/v1/auth/revoke" "${ACTIVE_DOCS[@]}"
reject_match "active docs do not use /api/permissions/register as current API" "/api/permissions/register" "${ACTIVE_DOCS[@]}"
reject_match "active docs do not use Required role(s): language" "Required role(s):" "${ACTIVE_DOCS[@]}"

for doc in \
  "${DURION_DIR}/docs/architecture/AUTHORIZATION_MODEL.md" \
  "${ROOT_DIR}/pos-security-service/docs/AUTH_TOKEN_USAGE_GUIDE.md"
do
  if search_quiet "perm_bits" "${doc}" && search_quiet "roles" "${doc}"; then
    echo "PASS: $(basename "${doc}") mentions perm_bits and roles"
  else
    echo "FAIL: $(basename "${doc}") must mention perm_bits and roles together" >&2
    failures=$((failures + 1))
  fi
done

if (( failures > 0 )); then
  echo "Authorization documentation drift check failed with ${failures} issue(s)." >&2
  exit 1
fi

echo "Authorization documentation drift check passed."
