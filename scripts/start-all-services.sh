#!/bin/bash
# Start all Durion POS microservices in proper order with dev profile (H2 databases)

set -e

cd "$(dirname "$0")"

echo "=== Starting Durion POS Microservices (Dev Profile - H2) ==="

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Note: Assumes modules are already compiled
# To rebuild: ./mvnw clean compile -DskipTests

# Create logs directory
mkdir -p logs

# Start services in order
echo -e "${YELLOW}Starting Service Discovery (Eureka)...${NC}"
./mvnw spring-boot:run -pl pos-service-discovery -Dspring-boot.run.arguments="--spring.profiles.active=dev" > logs/pos-service-discovery.log 2>&1 &
PID=$!
echo $PID > logs/pos-service-discovery.pid
echo -e "${BLUE}  PID: $PID${NC}"
sleep 20

echo -e "${YELLOW}Starting Security Service...${NC}"
./mvnw spring-boot:run -pl pos-security-service -Dspring-boot.run.arguments="--spring.profiles.active=dev" > logs/pos-security-service.log 2>&1 &
PID=$!
echo $PID > logs/pos-security-service.pid
echo -e "${BLUE}  PID: $PID${NC}"
sleep 10

echo -e "${YELLOW}Starting API Gateway...${NC}"
./mvnw spring-boot:run -pl pos-api-gateway -Dspring-boot.run.arguments="--spring.profiles.active=dev" > logs/pos-api-gateway.log 2>&1 &
PID=$!
echo $PID > logs/pos-api-gateway.pid
echo -e "${BLUE}  PID: $PID${NC}"
sleep 10

echo -e "${YELLOW}Starting Core Services...${NC}"

# Start all business services
services=(
    "pos-accounting"
    "pos-catalog"
    "pos-customer"
    "pos-event-receiver"
    "pos-image"
    "pos-inquiry"
    "pos-inventory"
    "pos-invoice"
    "pos-location"
    "pos-order"
    "pos-people"
    "pos-price"
    "pos-shop-manager"
    "pos-vehicle-inventory"
    "pos-vehicle-fitment"
    "pos-vehicle-reference-nhtsa"
    "pos-vehicle-reference-carapi"
    "pos-workorder"
)

for service in "${services[@]}"; do
    echo -e "  ${GREEN}→${NC} Starting $service..."
    ./mvnw spring-boot:run -pl "$service" -Dspring-boot.run.arguments="--spring.profiles.active=dev" > "logs/$service.log" 2>&1 &
    PID=$!
    echo $PID > "logs/$service.pid"
    sleep 3
done

echo ""
echo -e "${GREEN}=== All services started! ===${NC}"
echo ""
echo "Service URLs:"
echo "  Eureka Dashboard: http://localhost:8761"
echo "  API Gateway:      http://localhost:8080 (check logs for actual port)"
echo "  Security Service: http://localhost:8081 (check logs for actual port)"
echo ""
echo -e "${YELLOW}Note: Dynamic port assignment enabled - check logs for actual ports${NC}"
echo ""
echo "Logs are in: ./logs/"
echo ""
echo "To stop all services, run: ./stop-all-services.sh"
echo "To view a service log: tail -f logs/<service-name>.log"
