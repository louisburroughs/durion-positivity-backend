#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/pos-workorder/docker-compose.kafka.yml"

usage() {
  cat <<'USAGE'
Usage:
  ./scripts/workorder-kafka-local.sh up
  ./scripts/workorder-kafka-local.sh down
  ./scripts/workorder-kafka-local.sh restart
  ./scripts/workorder-kafka-local.sh logs [service]
  ./scripts/workorder-kafka-local.sh topics
USAGE
}

if [[ $# -eq 0 ]]; then
  usage
  exit 1
fi

command="$1"

case "$command" in
  up)
    docker compose -f "${COMPOSE_FILE}" up -d
    echo "Kafka local stack is up."
    echo "Kafka bootstrap servers: localhost:9092"
    echo "Kafka UI: http://localhost:8098"
    ;;
  down)
    docker compose -f "${COMPOSE_FILE}" down -v --remove-orphans
    echo "Kafka local stack is down."
    ;;
  restart)
    docker compose -f "${COMPOSE_FILE}" down -v --remove-orphans
    docker compose -f "${COMPOSE_FILE}" up -d
    echo "Kafka local stack restarted."
    ;;
  logs)
    service="${2:-kafka}"
    docker compose -f "${COMPOSE_FILE}" logs -f "${service}"
    ;;
  topics)
    docker compose -f "${COMPOSE_FILE}" exec kafka kafka-topics.sh \
      --bootstrap-server localhost:9092 --list
    ;;
  *)
    usage
    exit 1
    ;;
esac
