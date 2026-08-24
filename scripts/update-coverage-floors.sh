#!/usr/bin/env bash
# Re-derive every module's JaCoCo coverage floors from the last -DskipITs build
# and write them into the module poms.
#
# This is what makes the ratchet a ratchet. Section 6.2 of
# docs/TEST_COVERAGE_IMPROVEMENT_PLAN.md says "raise a module's floor when its
# coverage rises", but nothing automated it, so floors only ever moved when
# someone remembered. A floor left behind is slack: the module can shed every
# point it gained without failing a build.
#
# Floors are raised, never lowered -- a proposal below the standing floor is
# dropped unless --allow-lower is passed, and section 6.2 requires the reason to
# go in the commit message.
#
# Measure first, with the command the gate itself runs. -DskipITs is not
# optional: Failsafe shares the JaCoCo agent and the same jacoco.exec, so an
# IT-inclusive run yields floors the gate can never reproduce (§6.1). The script
# refuses to run if it finds Failsafe reports.
#
#   ./mvnw -pl pos-coverage-aggregate -am verify -DskipITs \
#       -Darchunit.skipTests=true -T 1C
#   scripts/update-coverage-floors.sh          # preview
#   scripts/update-coverage-floors.sh --apply  # write the poms
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

usage() {
  cat <<'USAGE'
Re-derive per-module JaCoCo coverage floors from the last -DskipITs build.

Usage:
  scripts/update-coverage-floors.sh [options] [module...]

Options:
  --apply          Write the new floors into the module poms (default: preview)
  --cushion N      Points below measured coverage (default 3, per §6.2)
  --allow-lower    Permit lowering a floor; say why in the commit message
  --allow-its      Proceed even though Failsafe reports were found. The floors
                   will describe coverage neither binding gate can reproduce.
  -h, --help       Show this help

Examples:
  scripts/update-coverage-floors.sh
  scripts/update-coverage-floors.sh --apply
  scripts/update-coverage-floors.sh --apply pos-supplier
USAGE
}

args=()
mode="--dry-run"

for arg in "$@"; do
  case "$arg" in
    -h|--help) usage; exit 0 ;;
    --apply) mode="--apply" ;;
    --dry-run) mode="--dry-run" ;;
    *) args+=("$arg") ;;
  esac
done

exec python3 scripts/coverage_floors.py "$mode" ${args[@]+"${args[@]}"}
