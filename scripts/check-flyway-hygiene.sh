#!/usr/bin/env bash
set -euo pipefail

shopt -s nullglob

errors=0
modules_with_migrations=0
enforce_ddl_auto_update_check="${ENFORCE_DDL_AUTO_UPDATE_CHECK:-false}"

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

if [[ $modules_with_migrations -eq 0 ]]; then
  echo "No modules with db/migration SQL files were found."
fi

if [[ $errors -gt 0 ]]; then
  echo "Flyway hygiene checks failed with $errors issue(s)."
  exit 1
fi

echo "Flyway hygiene checks passed for $modules_with_migrations module(s) with migrations."
