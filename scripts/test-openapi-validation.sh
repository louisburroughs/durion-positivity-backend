#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

./mvnw -q -pl pos-openapi-validation -DskipTests=false \
    -Dtest=OpenApiModuleValidatorTest,OpenApiAggregateValidatorTest,OpenApiRepositoryValidationTest test
