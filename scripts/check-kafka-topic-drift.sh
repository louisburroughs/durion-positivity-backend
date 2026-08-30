#!/usr/bin/env bash
set -euo pipefail

# Guards against drift between the topics services use and the topics
# kafka-topic-init provisions.
#
# kafka-topic-init is not a local-dev convenience: deploy-backend.sh runs it on
# alpha in both the full and the config-only deploy mode, so its list is the
# config every topic actually gets. A topic missing from it is created implicitly
# by the broker at Kafka defaults instead — which for a DLQ means 7-day retention
# where 30 was intended, on exactly the topics whose contents are supposed to
# survive long enough for a human to investigate.
#
# The list drifted to 14 entries against ~35 configured topics that way, with 30
# DLQs never provisioned at all (#1578, #1579). It is now generated from the
# @KafkaListener defaults and *-topic properties, and this check fails when the
# committed block no longer matches what the code implies.
#
# Fix a failure with `scripts/generate-kafka-topics.py --apply` and commit
# docker-compose.yml.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

exec python3 scripts/generate-kafka-topics.py --check
