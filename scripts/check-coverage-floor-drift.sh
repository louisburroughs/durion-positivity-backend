#!/usr/bin/env bash
# Guards the coverage ratchet against its own floors going stale.
#
# Root pom.xml gates each module on <jacoco.line.min> / <jacoco.branch.min>, set
# a few points below measured coverage (docs/TEST_COVERAGE_IMPROVEMENT_PLAN.md
# §6.2). Nothing kept those floors in step with the code, and a floor that has
# fallen behind fails nothing while the module gives back everything it gained.
# At the time this check was written the reactor's floors permitted roughly
# 3,400 covered lines and 970 covered branches to disappear -- about four points
# of overall coverage -- without one build turning red.
#
# Fails on:
#   BREACH     measured coverage is under the floor (jacoco:check fails too;
#              this names the module and counter)
#   THIN       the cushion is under --min-cushion. §6.2: "a cushion any thinner
#              than about two points is not a gate, it is a coin toss"
#   STALE      the cushion is over --max-cushion, i.e. the floor was never
#              raised after coverage rose
#   UNGUARDED  a module big enough to need a floor has none
#
# Modules with no jacoco.csv are reported and skipped, not failed: only a build
# that ran tests produces one. Run it after the same -DskipITs build the gate
# measures -- the script refuses to score Failsafe-contaminated coverage (§6.1).
#
#   ./mvnw -pl pos-coverage-aggregate -am verify -DskipITs \
#       -Darchunit.skipTests=true -T 1C
#   scripts/check-coverage-floor-drift.sh
#
# Fix drift with scripts/update-coverage-floors.sh --apply.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

case "${1-}" in
  -h|--help)
    cat <<'USAGE'
Verify every module's coverage floor still sits a sane distance under its
measured coverage.

Usage:
  scripts/check-coverage-floor-drift.sh [options] [module...]

Options:
  --min-cushion N  Floors closer than this to measured coverage are THIN
                   (default 2)
  --max-cushion N  Floors further than this below it are STALE (default 6)
  --min-lines N    Modules smaller than this need no floor (default 50)
  --allow-its      Score the report even though Failsafe reports were found
  -h, --help       Show this help
USAGE
    exit 0
    ;;
esac

exec python3 scripts/coverage_floors.py --check "$@"
