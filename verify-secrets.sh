#!/bin/bash
# Secrets Verification Script
# Run this to verify all hardcoded secrets have been removed

echo "=== Scanning for Hardcoded Secrets in application.yml files ==="
echo ""

# Check for hardcoded database passwords
HARDCODED_DB=$(grep -r "password: pos_password" pos-*/src/main/resources/application.yml 2>/dev/null | wc -l)
echo "Hardcoded database passwords (pos_password): $HARDCODED_DB"
if [ "$HARDCODED_DB" -eq 0 ]; then
    echo "  ✓ PASS: No hardcoded database passwords found"
else
    echo "  ✗ FAIL: Found hardcoded database passwords"
    grep -r "password: pos_password" pos-*/src/main/resources/application.yml 2>/dev/null
fi
echo ""

# Check for environment variable usage
ENV_VAR_USAGE=$(grep -r "SPRING_DATASOURCE_PASSWORD" pos-*/src/main/resources/application.yml 2>/dev/null | wc -l)
echo "Services using environment variables: $ENV_VAR_USAGE"
if [ "$ENV_VAR_USAGE" -ge 15 ]; then
    echo "  ✓ PASS: Most services use environment variables"
else
    echo "  ⚠ WARNING: Only $ENV_VAR_USAGE services use environment variables"
fi
echo ""

# Check for hardcoded keystore passwords
HARDCODED_KEYSTORE=$(grep -r "key-store-password: changeit" pos-*/src/main/resources/application.yml 2>/dev/null | wc -l)
echo "Hardcoded keystore passwords (changeit): $HARDCODED_KEYSTORE"
if [ "$HARDCODED_KEYSTORE" -eq 0 ]; then
    echo "  ✓ PASS: No hardcoded keystore passwords found"
else
    echo "  ✗ FAIL: Found hardcoded keystore passwords"
    grep -r "key-store-password: changeit" pos-*/src/main/resources/application.yml 2>/dev/null
fi
echo ""

# Check for hardcoded admin passwords
HARDCODED_ADMIN=$(grep -r "password: admin" pos-*/src/main/resources/application.yml 2>/dev/null | wc -l)
echo "Hardcoded admin passwords: $HARDCODED_ADMIN"
if [ "$HARDCODED_ADMIN" -eq 0 ]; then
    echo "  ✓ PASS: No hardcoded admin passwords found"
else
    echo "  ✗ FAIL: Found hardcoded admin passwords"
    grep -r "password: admin" pos-*/src/main/resources/application.yml 2>/dev/null
fi
echo ""

# List all services with updated configs
echo "=== Services with Environment Variables ==="
grep -l "SPRING_DATASOURCE_PASSWORD\|SSL_KEYSTORE_PASSWORD\|SPRING_SECURITY_USER_PASSWORD" pos-*/src/main/resources/application.yml 2>/dev/null | cut -d'/' -f1 | sort -u
echo ""

# Final summary
echo "=== Summary ==="
TOTAL_FAILS=$(( HARDCODED_DB + HARDCODED_KEYSTORE + HARDCODED_ADMIN ))
if [ "$TOTAL_FAILS" -eq 0 ]; then
    echo "✓ ALL CHECKS PASSED: No hardcoded secrets found!"
    echo "  Remember to set environment variables before running services."
else
    echo "✗ $TOTAL_FAILS ISSUES FOUND: Please review and fix hardcoded secrets."
fi
