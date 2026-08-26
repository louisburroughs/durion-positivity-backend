#!/usr/bin/env bash
set -euo pipefail

shopt -s nullglob

errors=0
modules_with_migrations=0
enforce_ddl_auto_update_check="${ENFORCE_DDL_AUTO_UPDATE_CHECK:-false}"

# Seed-file baseline (docs/DATA_SEED_STRATEGY.md §2/§5): every R__seed_*.sql
# must be classified in scripts/flyway-seed-baseline.txt. New seed files fail
# until deliberately added there; deleted seed files must drop their line.
seed_baseline_file="$(dirname "$0")/flyway-seed-baseline.txt"
declare -A seed_baseline_tier=()
declare -A seed_baseline_matched=()
tier2_remaining=0
if [[ -f "$seed_baseline_file" ]]; then
  while read -r entry tier _; do
    [[ -z "$entry" || "$entry" == \#* ]] && continue
    seed_baseline_tier[$entry]="${tier:-tier1}"
  done < "$seed_baseline_file"
else
  echo "ERROR: seed baseline file not found: $seed_baseline_file"
  errors=$((errors + 1))
fi

has_pattern() {
  local pattern="$1"
  local file="$2"

  if command -v rg >/dev/null 2>&1; then
    rg -q "$pattern" "$file"
  else
    grep -Eq "$pattern" "$file"
  fi
}

for module_dir in pos-*; do
  [[ -d "$module_dir" ]] || continue

  migration_dir="$module_dir/src/main/resources/db/migration"
  migration_files=("$migration_dir"/*.sql)
  if [[ ${#migration_files[@]} -eq 0 ]]; then
    continue
  fi

  modules_with_migrations=$((modules_with_migrations + 1))
  module_name="$(basename "$module_dir")"

  if ! has_pattern '<artifactId>(spring-boot-starter-flyway|flyway-core)</artifactId>' "$module_dir/pom.xml"; then
    echo "ERROR: $module_name has db/migration files but missing Flyway dependency (expected spring-boot-starter-flyway or flyway-core)"
    errors=$((errors + 1))
  fi

  if ! has_pattern '<artifactId>flyway-database-postgresql</artifactId>' "$module_dir/pom.xml"; then
    echo "ERROR: $module_name has db/migration files but missing flyway-database-postgresql dependency"
    errors=$((errors + 1))
  fi

  declare -A version_seen=()
  for file in "${migration_files[@]}"; do
    base="$(basename "$file")"

    if [[ ! "$base" =~ ^(V[0-9]+(_[0-9]+)*__[A-Za-z0-9_]+|R__[A-Za-z0-9_]+)\.sql$ ]]; then
      echo "ERROR: $module_name migration filename invalid: $base"
      errors=$((errors + 1))
    fi

    if [[ "$base" == R__seed_* ]]; then
      seed_key="$module_name/$base"
      if [[ -n "${seed_baseline_tier[$seed_key]:-}" ]]; then
        seed_baseline_matched[$seed_key]=1
        if [[ "${seed_baseline_tier[$seed_key]}" == "tier2" ]]; then
          tier2_remaining=$((tier2_remaining + 1))
        fi
      else
        echo "ERROR: $module_name has unclassified seed migration $base — new Flyway seed files are only allowed for service-private, environment-invariant data (docs/DATA_SEED_STRATEGY.md §2). If it qualifies, add '$seed_key tier1' to scripts/flyway-seed-baseline.txt; otherwise load the data through the owning service's API instead."
        errors=$((errors + 1))
      fi
    fi

    if [[ "$base" =~ ^V([0-9]+(_[0-9]+)*)__ ]]; then
      version="${BASH_REMATCH[1]}"
      if [[ -n "${version_seen[$version]:-}" ]]; then
        echo "ERROR: $module_name has duplicate migration version V$version: ${version_seen[$version]} and $base"
        errors=$((errors + 1))
      else
        version_seen[$version]="$base"
      fi
    fi
  done
  unset version_seen

  for app_file in "$module_dir"/src/main/resources/application*.yml; do
    [[ -f "$app_file" ]] || continue
    app_name="$(basename "$app_file")"

    case "$app_name" in
      *test*|*local*)
        continue
        ;;
    esac

    if has_pattern 'ddl-auto:[[:space:]]*update' "$app_file"; then
      if [[ "$enforce_ddl_auto_update_check" == "true" ]]; then
        echo "ERROR: $module_name uses ddl-auto=update in non-test runtime config ($app_name)"
        errors=$((errors + 1))
      else
        echo "WARN: $module_name uses ddl-auto=update in non-test runtime config ($app_name) (temporarily allowed; set ENFORCE_DDL_AUTO_UPDATE_CHECK=true to fail)"
      fi
    fi
  done

done

for seed_key in "${!seed_baseline_tier[@]}"; do
  if [[ -z "${seed_baseline_matched[$seed_key]:-}" ]]; then
    echo "ERROR: stale seed baseline entry '$seed_key' — the file no longer exists; remove its line from scripts/flyway-seed-baseline.txt"
    errors=$((errors + 1))
  fi
done

if [[ $tier2_remaining -gt 0 ]]; then
  echo "NOTE: $tier2_remaining tier2 seed file(s) remain pending conversion to the API-driven seed pipeline (docs/DATA_SEED_STRATEGY.md §5)."
fi

if [[ $modules_with_migrations -eq 0 ]]; then
  echo "No modules with db/migration SQL files were found."
fi

if [[ $errors -gt 0 ]]; then
  echo "Flyway hygiene checks failed with $errors issue(s)."
  exit 1
fi

echo "Flyway hygiene checks passed for $modules_with_migrations module(s) with migrations."
