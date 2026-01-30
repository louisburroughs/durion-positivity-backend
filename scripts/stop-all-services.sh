#!/bin/bash
# Stop all Durion POS microservices

cd "$(dirname "$0")"

echo "=== Stopping all POS services ==="

if [ -d "logs" ]; then
    for pidfile in logs/*.pid; do
        if [ -f "$pidfile" ]; then
            PID=$(cat "$pidfile")
            SERVICE=$(basename "$pidfile" .pid)
            
            if kill -0 "$PID" 2>/dev/null; then
                echo "Stopping $SERVICE (PID: $PID)..."
                kill "$PID"
            else
                echo "$SERVICE is not running"
            fi
            rm "$pidfile"
        fi
    done
else
    echo "No PID files found. Services may not be running."
fi

# Kill any remaining Spring Boot processes from this project
pkill -f "durion-positivity-backend.*spring-boot" || true

echo "All services stopped."
