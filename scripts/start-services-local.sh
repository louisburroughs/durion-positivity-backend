#!/bin/bash
# Start Durion POS microservices from pre-built JARs with dev profile (H2 databases)

set -e

cd "$(dirname "$0")"

echo "=== Starting Durion POS Microservices (Dev Profile - H2) ==="

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Build all JARs without running tests
echo -e "${YELLOW}Building all service JARs (skipping tests)...${NC}"
./mvnw clean package -Dmaven.test.skip=true -q
echo -e "${GREEN}✓ Build complete${NC}"

# Create logs directory
mkdir -p logs

# Start services in order
echo -e "${YELLOW}Starting Service Discovery (Eureka) on port 8761...${NC}"
java -jar pos-service-discovery/target/pos-service-discovery-*.jar \
  --spring.profiles.active=dev \
  --server.port=8761 > logs/pos-service-discovery.log 2>&1 &
echo $! > logs/pos-service-discovery.pid
echo -e "${BLUE}  PID: $!${NC}"
sleep 15

echo -e "${YELLOW}Starting Security Service...${NC}"
java -jar pos-security-service/target/pos-security-service-*.jar \
  --spring.profiles.active=dev > logs/pos-security-service.log 2>&1 &
echo $! > logs/pos-security-service.pid
echo -e "${BLUE}  PID: $!${NC}"
sleep 10

echo -e "${YELLOW}Starting accounting service...${NC}"
java -jar pos-accounting/target/pos-accounting-*.jar \
  --spring.profiles.active=dev > logs/pos-accounting.log 2>&1 &
echo $! > logs/pos-accounting.pid
echo -e "${BLUE}  PID: $!${NC}"

echo ""
echo -e "${GREEN}=== Services started! ===${NC}"
echo ""
echo "Service URLs:"
echo "  Eureka Dashboard: http://localhost:8761"
echo ""
echo -e "${YELLOW}View logs with: tail -f logs/<service-name>.log${NC}"
echo "Example: tail -f logs/pos-accounting.log"
echo ""
echo "To stop all services, run: ./stop-all-services.sh"
