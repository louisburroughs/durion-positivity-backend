#!/bin/bash
# Observability Stack Verification Script
# This script helps verify that the observability implementation is working correctly

set -e

echo "=========================================="
echo "Durion POS Backend - Observability Verification"
echo "=========================================="
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Check functions
check_service() {
    local service=$1
    local url=$2
    local name=$3
    
    echo -n "Checking $name... "
    if curl -sf "$url" > /dev/null 2>&1; then
        echo -e "${GREEN}✓ OK${NC}"
        return 0
    else
        echo -e "${RED}✗ FAILED${NC}"
        return 1
    fi
}

check_docker_service() {
    local service=$1
    echo -n "Checking Docker service $service... "
    if docker-compose ps | grep -q "$service.*Up"; then
        echo -e "${GREEN}✓ Running${NC}"
        return 0
    else
        echo -e "${RED}✗ Not running${NC}"
        return 1
    fi
}

# Step 1: Check Docker Compose is running
echo "Step 1: Checking Docker Compose services"
echo "------------------------------------------"
check_docker_service "jaeger" || echo "Run: docker-compose up -d jaeger"
check_docker_service "prometheus" || echo "Run: docker-compose up -d prometheus"
check_docker_service "grafana" || echo "Run: docker-compose up -d grafana"
check_docker_service "otel-collector" || echo "Run: docker-compose up -d otel-collector"
echo ""

# Step 2: Check observability services are accessible
echo "Step 2: Checking observability service endpoints"
echo "------------------------------------------------"
check_service "grafana" "http://localhost:3000/api/health" "Grafana Health"
check_service "jaeger" "http://localhost:16686/" "Jaeger UI"
check_service "prometheus" "http://localhost:9090/-/healthy" "Prometheus Health"
check_service "otel-collector" "http://localhost:13133/" "OTEL Collector Health"
echo ""

# Step 3: Check Prometheus targets
echo "Step 3: Checking Prometheus targets"
echo "-----------------------------------"
echo "Querying Prometheus for active targets..."
TARGETS=$(curl -s http://localhost:9090/api/v1/targets | jq -r '.data.activeTargets | length')
if [ "$TARGETS" -gt 0 ]; then
    echo -e "${GREEN}✓ Found $TARGETS active targets${NC}"
else
    echo -e "${YELLOW}⚠ No active targets found. Services may not be running yet.${NC}"
fi
echo ""

# Step 4: Check if any POS services are running
echo "Step 4: Checking POS service availability"
echo "-----------------------------------------"
SERVICES_UP=0
for service in eureka-server pos-api-gateway pos-accounting pos-catalog pos-customer; do
    if docker-compose ps | grep -q "$service.*Up" 2>/dev/null; then
        echo -e "${GREEN}✓ $service is running${NC}"
        ((SERVICES_UP++))
    fi
done

if [ $SERVICES_UP -eq 0 ]; then
    echo -e "${YELLOW}⚠ No POS services are running yet.${NC}"
    echo "To start services: docker-compose up -d"
else
    echo -e "${GREEN}✓ $SERVICES_UP POS services are running${NC}"
fi
echo ""

# Step 5: Check for traces in Jaeger
echo "Step 5: Checking for traces in Jaeger"
echo "-------------------------------------"
SERVICES_WITH_TRACES=$(curl -s http://localhost:16686/api/services | jq -r '.data | length')
if [ "$SERVICES_WITH_TRACES" -gt 0 ]; then
    echo -e "${GREEN}✓ Found $SERVICES_WITH_TRACES services with traces${NC}"
    curl -s http://localhost:16686/api/services | jq -r '.data[]' | while read service; do
        echo "  - $service"
    done
else
    echo -e "${YELLOW}⚠ No traces found yet. Generate some traffic to see traces.${NC}"
fi
echo ""

# Step 6: Configuration file checks
echo "Step 6: Verifying configuration files"
echo "-------------------------------------"
check_file() {
    local file=$1
    if [ -f "$file" ]; then
        echo -e "${GREEN}✓ $file exists${NC}"
    else
        echo -e "${RED}✗ $file missing${NC}"
    fi
}

check_file "application-observability.yml"
check_file "observability/prometheus.yml"
check_file "observability/otel-collector-config.yml"
check_file "observability/grafana/provisioning/datasources/datasources.yml"
check_file "observability/README.md"
echo ""

# Step 7: Check Dockerfile modifications
echo "Step 7: Verifying Dockerfile modifications"
echo "------------------------------------------"
DOCKERFILES_WITH_AGENT=0
for dockerfile in pos-*/Dockerfile; do
    if [ -f "$dockerfile" ] && grep -q "grafana-opentelemetry-java" "$dockerfile"; then
        ((DOCKERFILES_WITH_AGENT++))
    fi
done
echo -e "${GREEN}✓ $DOCKERFILES_WITH_AGENT Dockerfiles include OpenTelemetry agent${NC}"
echo ""

# Summary
echo "=========================================="
echo "Verification Summary"
echo "=========================================="
echo ""
echo "Observability Stack:"
echo "  - Grafana:    http://localhost:3000 (admin/admin)"
echo "  - Jaeger:     http://localhost:16686"
echo "  - Prometheus: http://localhost:9090"
echo "  - OTEL Coll:  http://localhost:13133"
echo ""
echo "Next Steps:"
echo "  1. Start POS services: docker-compose up -d"
echo "  2. Generate traffic to services"
echo "  3. View traces in Jaeger"
echo "  4. View metrics in Prometheus/Grafana"
echo ""
echo "Documentation:"
echo "  - observability/README.md"
echo "  - OBSERVABILITY_IMPLEMENTATION_SUMMARY.md"
echo ""
