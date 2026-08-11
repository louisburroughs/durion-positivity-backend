#!/usr/bin/env bash
set -euo pipefail

# Guards against coverage drift in pos-coverage-aggregate.
#
# JaCoCo's report-aggregate goal only reports on modules declared as dependencies
# of the aggregator, and CI runs SonarCloud as `-pl pos-coverage-aggregate -am`, so
# a reactor module that is never declared here is absent from both the aggregate
# coverage XML and the Sonar analysis reactor — silently, with a green build.
# Seven modules had drifted out this way before this check existed.
#
# Every reactor module must therefore appear as a dependency of the aggregator,
# except those listed in EXEMPT_MODULES below.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

AGGREGATE_POM="pos-coverage-aggregate/pom.xml"

# pos-dependencies is a BOM and pos-coverage-aggregate is the aggregator itself;
# neither has production code to cover.
EXEMPT_MODULES=(
  pos-dependencies
  pos-coverage-aggregate
)

exempt_pattern="$(printf '%s\n' "${EXEMPT_MODULES[@]}" | paste -sd '|' -)"

reactor_modules=$(
  grep -o '<module>[^<]*</module>' pom.xml \
    | sed 's|</\?module>||g' \
    | grep -Ev "^(${exempt_pattern})$" \
    | sort -u
)

declared_modules=$(
  grep -o '<artifactId>pos-[^<]*</artifactId>' "$AGGREGATE_POM" \
    | sed 's|</\?artifactId>||g' \
    | grep -Ev "^(${exempt_pattern})$" \
    | sort -u
)

missing=$(comm -23 <(echo "$reactor_modules") <(echo "$declared_modules"))
stale=$(comm -13 <(echo "$reactor_modules") <(echo "$declared_modules"))

status=0

if [[ -n "$missing" ]]; then
  echo "ERROR: reactor modules missing from ${AGGREGATE_POM} (excluded from aggregate coverage and Sonar analysis):"
  echo "$missing" | sed 's/^/  - /'
  echo
  echo "Add each as a <dependency> on com.positivity with <version>\${project.version}</version>."
  status=1
fi

if [[ -n "$stale" ]]; then
  echo "ERROR: dependencies in ${AGGREGATE_POM} that are not reactor modules of pom.xml:"
  echo "$stale" | sed 's/^/  - /'
  echo
  echo "Remove each stale dependency, or add the module back to the root <modules> list."
  status=1
fi

if [[ "$status" -ne 0 ]]; then
  exit "$status"
fi

echo "PASS: ${AGGREGATE_POM} declares every reactor module ($(echo "$reactor_modules" | wc -l) modules)."
