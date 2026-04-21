#!/bin/bash

# Verification script for Docker Compose secrets externalization
# This script verifies that all hardcoded secrets have been replaced with environment variables

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKER_COMPOSE_FILE="$SCRIPT_DIR/../docker-compose.yml"

echo "=========================================="
echo "Docker Compose Secrets Verification"
echo "=========================================="
echo ""

# Color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

failed=0

# Check 1: No hardcoded PostgreSQL password
echo "✓ Checking for hardcoded PostgreSQL passwords..."
if grep -q "POSTGRES_PASSWORD: positivity" "$DOCKER_COMPOSE_FILE"; then
    echo -e "${RED}✗ FAILED: Found hardcoded POSTGRES_PASSWORD${NC}"
    failed=$((failed + 1))
else
    echo -e "${GREEN}✓ PASSED: No hardcoded POSTGRES_PASSWORD found${NC}"
fi

# Check 2: No hardcoded Grafana password
echo ""
echo "✓ Checking for hardcoded Grafana passwords..."
if grep -q "GF_SECURITY_ADMIN_PASSWORD: admin" "$DOCKER_COMPOSE_FILE"; then
    echo -e "${RED}✗ FAILED: Found hardcoded GF_SECURITY_ADMIN_PASSWORD${NC}"
    failed=$((failed + 1))
else
    echo -e "${GREEN}✓ PASSED: No hardcoded GF_SECURITY_ADMIN_PASSWORD found${NC}"
fi

# Check 3: No hardcoded datasource passwords
echo ""
echo "✓ Checking for hardcoded SPRING_DATASOURCE_PASSWORD..."
if grep -q "SPRING_DATASOURCE_PASSWORD: positivity" "$DOCKER_COMPOSE_FILE"; then
    echo -e "${RED}✗ FAILED: Found hardcoded SPRING_DATASOURCE_PASSWORD${NC}"
    failed=$((failed + 1))
else
    echo -e "${GREEN}✓ PASSED: No hardcoded SPRING_DATASOURCE_PASSWORD found${NC}"
fi

# Check 4: Verify environment variable usage for database password
echo ""
echo "✓ Checking for environment variable usage in PostgreSQL..."
if grep -q 'POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}' "$DOCKER_COMPOSE_FILE"; then
    echo -e "${GREEN}✓ PASSED: PostgreSQL using environment variable${NC}"
else
    echo -e "${RED}✗ FAILED: PostgreSQL not using environment variable${NC}"
    failed=$((failed + 1))
fi

# Check 5: Verify environment variable usage for Grafana password
echo ""
echo "✓ Checking for environment variable usage in Grafana..."
if grep -q 'GF_SECURITY_ADMIN_PASSWORD=${GF_SECURITY_ADMIN_PASSWORD}' "$DOCKER_COMPOSE_FILE"; then
    echo -e "${GREEN}✓ PASSED: Grafana using environment variable${NC}"
else
    echo -e "${RED}✗ FAILED: Grafana not using environment variable${NC}"
    failed=$((failed + 1))
fi

# Check 6: Verify all POS services use environment variables
echo ""
echo "✓ Checking POS services for environment variable usage..."
pos_count=$(grep -c 'SPRING_DATASOURCE_PASSWORD: ${SPRING_DATASOURCE_PASSWORD}' "$DOCKER_COMPOSE_FILE" || true)
if [ "$pos_count" -ge 22 ]; then
    echo -e "${GREEN}✓ PASSED: All POS services (found $pos_count) using environment variables${NC}"
else
    echo -e "${RED}✗ FAILED: Expected 22+ services with environment variables, found $pos_count${NC}"
    failed=$((failed + 1))
fi

# Check 7: Verify .env files have CHANGE_ME placeholders (not real values)
echo ""
echo "✓ Checking .env files for placeholder values..."
if grep -q "CHANGE_ME" "$SCRIPT_DIR/.env" 2>/dev/null; then
    echo -e "${GREEN}✓ PASSED: .env contains CHANGE_ME placeholders${NC}"
else
    echo -e "${YELLOW}⚠ WARNING: .env missing CHANGE_ME placeholders (may be intentional if populated)${NC}"
fi

# Check 8: Verify .env.example is safe to commit
echo ""
echo "✓ Checking .env.example for safe example values..."
if grep -q "your_" "$SCRIPT_DIR/.env.example" 2>/dev/null; then
    echo -e "${GREEN}✓ PASSED: .env.example contains placeholder values (safe to commit)${NC}"
else
    echo -e "${RED}✗ FAILED: .env.example missing placeholder values${NC}"
    failed=$((failed + 1))
fi

# Summary
echo ""
echo "=========================================="
if [ $failed -eq 0 ]; then
    echo -e "${GREEN}All checks passed! ✓${NC}"
    echo "Docker Compose secrets are properly externalized."
    exit 0
else
    echo -e "${RED}Failed checks: $failed${NC}"
    echo "Please review and fix the issues above."
    exit 1
fi
