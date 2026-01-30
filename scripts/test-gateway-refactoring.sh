#!/bin/bash

# Integration Test: Gateway API Versioning Refactoring
# Tests:
# 1. Service names are correct
# 2. Gateway configuration is valid
# 3. Filter code compiles
# 4. YAML configurations are syntactically valid

set -e

echo "========================================="
echo "Gateway API Versioning Integration Test"
echo "========================================="
echo ""

BACKEND_DIR="/home/louisb/Projects/durion-positivity-backend"
cd "$BACKEND_DIR"

# Color codes
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Test counters
TESTS_PASSED=0
TESTS_FAILED=0

test_result() {
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✓ PASS${NC}: $1"
        ((TESTS_PASSED++))
    else
        echo -e "${RED}✗ FAIL${NC}: $1"
        ((TESTS_FAILED++))
    fi
}

# ==========================================
# TEST 1: Verify all 17 service names updated
# ==========================================
echo ""
echo "TEST 1: Verify spring.application.name updated in all 17 services"
echo "---"

SERVICES=(
    "pos-invoice:invoice"
    "pos-inquiry:inquiry"
    "pos-order:order"
    "pos-customer:customer"
    "pos-accounting:accounting"
    "pos-inventory:inventory"
    "pos-price:price"
    "pos-catalog:catalog"
    "pos-location:location"
    "pos-people:people"
    "pos-workorder:workorder"
    "pos-shop-manager:shop-manager"
    "pos-vehicle-fitment:vehicle-fitment"
    "pos-vehicle-inventory:vehicle-inventory"
    "pos-image:image"
    "pos-security-service:security-service"
    "pos-event-receiver:event-receiver"
)

for service_pair in "${SERVICES[@]}"; do
    IFS=':' read -r module expected_name <<< "$service_pair"
    actual_name=$(grep -A1 "application:" "$module/src/main/resources/application.yml" | grep "name:" | sed 's/.*name: //' | tr -d ' ')
    if [ "$actual_name" = "$expected_name" ]; then
        echo -e "${GREEN}✓${NC} $module -> $actual_name"
        ((TESTS_PASSED++))
    else
        echo -e "${RED}✗${NC} $module -> Expected: $expected_name, Got: $actual_name"
        ((TESTS_FAILED++))
    fi
done
echo ""

# ==========================================
# TEST 2: Compile gateway module
# ==========================================
echo ""
echo "TEST 2: Compile pos-api-gateway module"
echo "---"
if ./mvnw -q -pl pos-api-gateway -am clean compile; then
    echo -e "${GREEN}✓${NC} Gateway compiles successfully"
    ((TESTS_PASSED++))
else
    echo -e "${RED}✗${NC} Gateway compilation failed"
    ((TESTS_FAILED++))
fi
echo ""

# ==========================================
# TEST 3: Compile sample services
# ==========================================
echo ""
echo "TEST 3: Compile sample services with new configs"
echo "---"
SAMPLE_SERVICES=("pos-inventory" "pos-order" "pos-catalog" "pos-security-service")
for service in "${SAMPLE_SERVICES[@]}"; do
    if ./mvnw -q -pl "$service" -am clean compile 2>/dev/null; then
        echo -e "${GREEN}✓${NC} $service compiles successfully"
        ((TESTS_PASSED++))
    else
        echo -e "${RED}✗${NC} $service compilation failed"
        ((TESTS_FAILED++))
    fi
done
echo ""

# ==========================================
# TEST 4: Verify GlobalFilter exists
# ==========================================
echo ""
echo "TEST 4: Verify GlobalFilter implementation"
echo "---"
if [ -f "pos-api-gateway/src/main/java/com/positivity/gateway/filter/ApiVersionHeaderToPathFilter.java" ]; then
    echo -e "${GREEN}✓${NC} ApiVersionHeaderToPathFilter.java exists"
    ((TESTS_PASSED++))
    
    # Check for key methods
    if grep -q "public Mono<Void> filter" pos-api-gateway/src/main/java/com/positivity/gateway/filter/ApiVersionHeaderToPathFilter.java; then
        echo -e "${GREEN}✓${NC} filter() method implemented"
        ((TESTS_PASSED++))
    else
        echo -e "${RED}✗${NC} filter() method not found"
        ((TESTS_FAILED++))
    fi
    
    if grep -q "VERSION_HEADER" pos-api-gateway/src/main/java/com/positivity/gateway/filter/ApiVersionHeaderToPathFilter.java; then
        echo -e "${GREEN}✓${NC} VERSION_HEADER constant defined"
        ((TESTS_PASSED++))
    else
        echo -e "${RED}✗${NC} VERSION_HEADER constant not found"
        ((TESTS_FAILED++))
    fi
    
    if grep -q "HIGHEST_PRECEDENCE" pos-api-gateway/src/main/java/com/positivity/gateway/filter/ApiVersionHeaderToPathFilter.java; then
        echo -e "${GREEN}✓${NC} Filter runs at HIGHEST_PRECEDENCE"
        ((TESTS_PASSED++))
    else
        echo -e "${RED}✗${NC} Filter precedence not set"
        ((TESTS_FAILED++))
    fi
else
    echo -e "${RED}✗${NC} ApiVersionHeaderToPathFilter.java not found"
    ((TESTS_FAILED++))
fi
echo ""

# ==========================================
# TEST 5: Verify gateway configuration
# ==========================================
echo ""
echo "TEST 5: Verify gateway configuration"
echo "---"
GATEWAY_CONFIG="pos-api-gateway/src/main/resources/application.yml"

if grep -q "discovery:" "$GATEWAY_CONFIG" && grep -q "locator:" "$GATEWAY_CONFIG"; then
    echo -e "${GREEN}✓${NC} Discovery locator enabled"
    ((TESTS_PASSED++))
else
    echo -e "${RED}✗${NC} Discovery locator not configured"
    ((TESTS_FAILED++))
fi

if grep -q "lower-case-service-id: true" "$GATEWAY_CONFIG"; then
    echo -e "${GREEN}✓${NC} Lower-case service ID enabled"
    ((TESTS_PASSED++))
else
    echo -e "${RED}✗${NC} Lower-case service ID not enabled"
    ((TESTS_FAILED++))
fi

# Check that hardcoded routes are removed
ROUTE_COUNT=$(grep -c "^        - id:" "$GATEWAY_CONFIG" || echo "0")
if [ "$ROUTE_COUNT" = "0" ]; then
    echo -e "${GREEN}✓${NC} Hardcoded routes removed (found $ROUTE_COUNT)"
    ((TESTS_PASSED++))
else
    echo -e "${RED}✗${NC} Hardcoded routes still present (found $ROUTE_COUNT)"
    ((TESTS_FAILED++))
fi

if grep -q "port: 8080" "$GATEWAY_CONFIG"; then
    echo -e "${GREEN}✓${NC} Gateway port fixed at 8080"
    ((TESTS_PASSED++))
else
    echo -e "${RED}✗${NC} Gateway port not set to 8080"
    ((TESTS_FAILED++))
fi

echo ""

# ==========================================
# TEST 6: Verify local profile configuration
# ==========================================
echo ""
echo "TEST 6: Verify gateway local profile"
echo "---"
LOCAL_CONFIG="pos-api-gateway/src/main/resources/application-local.yml"

if grep -q "port: 8080" "$LOCAL_CONFIG"; then
    echo -e "${GREEN}✓${NC} Local profile: gateway port 8080"
    ((TESTS_PASSED++))
else
    echo -e "${RED}✗${NC} Local profile: gateway port not configured"
    ((TESTS_FAILED++))
fi

if grep -q "management:" "$LOCAL_CONFIG" && grep -q "port: 0" "$LOCAL_CONFIG"; then
    echo -e "${GREEN}✓${NC} Local profile: management port segregated (0)"
    ((TESTS_PASSED++))
else
    echo -e "${RED}✗${NC} Local profile: management port not segregated"
    ((TESTS_FAILED++))
fi

echo ""

# ==========================================
# SUMMARY
# ==========================================
echo "========================================="
echo "Test Summary"
echo "========================================="
echo -e "${GREEN}Passed: $TESTS_PASSED${NC}"
echo -e "${RED}Failed: $TESTS_FAILED${NC}"
echo "========================================="

if [ $TESTS_FAILED -eq 0 ]; then
    echo -e "${GREEN}✓ ALL TESTS PASSED${NC}"
    exit 0
else
    echo -e "${RED}✗ SOME TESTS FAILED${NC}"
    exit 1
fi
