# Secrets Management Guide

## Overview

This guide explains the comprehensive secrets management implementation for the durion-positivity-backend microservice platform. All secrets are externalized from code and configuration, following the 12-factor app pattern and OWASP best practices.

## Table of Contents

1. [Architecture](#architecture)
2. [Configuration Files](#configuration-files)
3. [Local Development Setup](#local-development-setup)
4. [Production Deployment](#production-deployment)
5. [Environment-Specific Secrets](#environment-specific-secrets)
6. [CI/CD Integration](#cicd-integration)
7. [Secret Rotation](#secret-rotation)
8. [Troubleshooting](#troubleshooting)
9. [Best Practices](#best-practices)

---

## Architecture

### Principle: External Configuration

All secrets and environment-specific configuration are stored outside the codebase:

```
┌─────────────────────────────────────────┐
│  Application Code & Configuration       │
│  (No secrets anywhere)                   │
├─────────────────────────────────────────┤
│  application.yml                         │
│  docker-compose.yml                      │
│  (Uses ${VARIABLE} references only)      │
├─────────────────────────────────────────┤
│  Environment Variables                   │
│  (Loaded from .env, CI/CD, K8s, etc)     │
└─────────────────────────────────────────┘
```

### Multiple Layers

1. **Application Layer** (Spring Boot)
   - Uses `${SPRING_DATASOURCE_PASSWORD}` syntax
   - Configured in `application.yml` files
   - Located in POS service modules

2. **Container Layer** (Docker Compose)
   - Uses `${VARIABLE}` syntax for environment variables
   - Configured in `docker-compose.yml`
   - Supports `.env` file loading

3. **Environment Layer**
   - `.env` - Local development secrets
   - `.env.docker` - Docker-specific secrets
   - `.env.example` - Template (safe to commit)
   - CI/CD secrets - GitHub Actions, GitLab CI, etc.
   - Container orchestration - Kubernetes Secrets, ConfigMaps

---

## Configuration Files

### .env (Local Development)

**Location:** Repository root  
**Purpose:** Local development secrets  
**Security:** NOT committed to git (in .gitignore)  
**Usage:** `source .env && docker-compose up -d`

**Contents:**
```bash
# Database
POSTGRES_PASSWORD=my_local_db_password
SPRING_DATASOURCE_PASSWORD=my_local_datasource_password

# Grafana
GF_SECURITY_ADMIN_PASSWORD=my_local_grafana_password

# SSL/TLS
SSL_KEYSTORE_PASSWORD=my_local_keystore_password
```

### .env.docker (Docker-specific)

**Location:** Repository root  
**Purpose:** Docker Compose environment secrets  
**Security:** NOT committed to git (in .gitignore)  
**Usage:** Docker Compose automatically loads this file

**Contents:**
```bash
# Same structure as .env but with Docker-specific values
POSTGRES_PASSWORD=docker_db_password
# ... (other secrets)
```

### .env.example (Safe Template)

**Location:** Repository root  
**Purpose:** Template for team reference  
**Security:** SAFE to commit to git  
**Usage:** Copy to `.env` and customize

**Contents:**
```bash
# Database Configuration
POSTGRES_USER=positivity
POSTGRES_PASSWORD=your_secure_password_here
POSTGRES_DB=positivity
SPRING_DATASOURCE_USERNAME=pos_user
SPRING_DATASOURCE_PASSWORD=your_secure_password_here
```

### application.yml Files

**Location:** Each POS service module  
**Pattern:** `pos-*/src/main/resources/application.yml`

**Secrets Using Environment Variables:**
```yaml
spring:
  datasource:
    username: ${SPRING_DATASOURCE_USERNAME:pos_user}
    password: ${SPRING_DATASOURCE_PASSWORD}
    
  security:
    user:
      password: ${SPRING_SECURITY_USER_PASSWORD}
```

### docker-compose.yml

**Location:** Repository root  
**Purpose:** Local development infrastructure  

**Secrets Using Environment Variables:**
```yaml
postgres:
  environment:
    POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}

grafana:
  environment:
    - GF_SECURITY_ADMIN_PASSWORD: ${GF_SECURITY_ADMIN_PASSWORD}

pos-accounting:
  environment:
    SPRING_DATASOURCE_PASSWORD: ${SPRING_DATASOURCE_PASSWORD}
```

---

## Local Development Setup

### Step 1: Clone Repository

```bash
git clone <repository-url>
cd durion-positivity-backend
```

### Step 2: Create .env File

```bash
cp .env.example .env
```

### Step 3: Edit .env with Actual Secrets

```bash
nano .env
```

Replace all `your_secure_password_here` and `CHANGE_ME_*` values with actual passwords.

**Example .env:**
```bash
POSTGRES_PASSWORD=myLocalDbPassword123!
SPRING_DATASOURCE_PASSWORD=myDataSourcePassword456!
GF_SECURITY_ADMIN_PASSWORD=myGrafanaPassword789!
SSL_KEYSTORE_PASSWORD=myKeystorePassword000!
```

### Step 4: Source Environment and Start Services

```bash
# Option 1: Source .env before running compose
source .env
docker-compose up -d

# Option 2: One-liner
$(source .env; docker-compose up -d)

# Option 3: Use --env-file
docker-compose --env-file .env up -d
```

### Step 5: Verify Services

```bash
# Check all services are running
docker-compose ps

# Check service health
curl http://localhost:8080/actuator/health

# View logs
docker-compose logs postgres
docker-compose logs pos-accounting
```

---

## Production Deployment

### Kubernetes Deployment

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: positivity-secrets
  namespace: production
type: Opaque
stringData:
  POSTGRES_PASSWORD: "$(generate_secure_password)"
  SPRING_DATASOURCE_PASSWORD: "$(generate_secure_password)"
  GF_SECURITY_ADMIN_PASSWORD: "$(generate_secure_password)"
  SSL_KEYSTORE_PASSWORD: "$(generate_secure_password)"
---
apiVersion: v1
kind: Pod
metadata:
  name: pos-accounting
spec:
  containers:
  - name: pos-accounting
    image: pos-accounting:latest
    envFrom:
    - secretRef:
        name: positivity-secrets
```

### Docker Swarm Deployment

```bash
# Create secrets in Docker Swarm
echo "secure_password_123" | docker secret create postgres_password -
echo "secure_password_456" | docker secret create datasource_password -

# Reference in docker-compose.yml
services:
  postgres:
    environment:
      POSTGRES_PASSWORD_FILE: /run/secrets/postgres_password
```

### CI/CD Environment Variables

**GitHub Actions:**
```yaml
jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - name: Deploy to Production
        env:
          POSTGRES_PASSWORD: ${{ secrets.POSTGRES_PASSWORD }}
          SPRING_DATASOURCE_PASSWORD: ${{ secrets.DATASOURCE_PASSWORD }}
          GF_SECURITY_ADMIN_PASSWORD: ${{ secrets.GRAFANA_PASSWORD }}
        run: |
          docker-compose up -d
```

**GitLab CI:**
```yaml
deploy:
  stage: deploy
  environment:
    name: production
  script:
    - export POSTGRES_PASSWORD=$POSTGRES_PASSWORD_PROD
    - export SPRING_DATASOURCE_PASSWORD=$DATASOURCE_PASSWORD_PROD
    - docker-compose up -d
```

---

## Environment-Specific Secrets

### Development Environment

```bash
# .env (or .env.dev)
ENVIRONMENT=development
POSTGRES_PASSWORD=dev_password_simple
SPRING_DATASOURCE_PASSWORD=dev_datasource_password

# Less strict security for local development
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
```

### Staging Environment

```bash
# .env.staging
ENVIRONMENT=staging
POSTGRES_PASSWORD=$(aws secretsmanager get-secret-value --secret-id staging/postgres-password --query SecretString --output text)
SPRING_DATASOURCE_PASSWORD=$(aws secretsmanager get-secret-value --secret-id staging/datasource-password --query SecretString --output text)

# Enhanced monitoring for staging
OTEL_EXPORTER_OTLP_ENDPOINT=https://staging-otel-collector.example.com:4317
```

### Production Environment

```bash
# CI/CD Pipeline - Never stored in files
${{ secrets.POSTGRES_PASSWORD_PROD }}
${{ secrets.DATASOURCE_PASSWORD_PROD }}
${{ secrets.GRAFANA_PASSWORD_PROD }}

# Or retrieved from secure vault
$(vault kv get -field=postgres_password secret/production/positivity)
$(vault kv get -field=datasource_password secret/production/positivity)
```

---

## CI/CD Integration

### Setting Up GitHub Secrets

1. Navigate to repository Settings → Secrets and variables → Actions
2. Click "New repository secret"
3. Add secrets:
   - Name: `POSTGRES_PASSWORD`
   - Value: `your_production_password`

Repeat for:
- `DATASOURCE_PASSWORD`
- `GRAFANA_PASSWORD`
- `SSL_KEYSTORE_PASSWORD`

### Using in Workflow

```yaml
name: Deploy

on:
  push:
    branches: [main]

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      
      - name: Build Docker Image
        run: docker build -t pos-accounting .
      
      - name: Deploy
        env:
          POSTGRES_PASSWORD: ${{ secrets.POSTGRES_PASSWORD }}
          SPRING_DATASOURCE_PASSWORD: ${{ secrets.DATASOURCE_PASSWORD }}
          GF_SECURITY_ADMIN_PASSWORD: ${{ secrets.GRAFANA_PASSWORD }}
        run: |
          docker-compose -f docker-compose.prod.yml up -d
```

---

## Secret Rotation

### Manual Rotation

1. Generate new password
2. Update secret in vault/CI-CD platform
3. Restart affected services
4. Verify services are healthy

### Automated Rotation (Recommended)

```bash
#!/bin/bash
# rotate-secrets.sh

# Rotate PostgreSQL password
NEW_PASSWORD=$(openssl rand -base64 32)
aws secretsmanager update-secret \
  --secret-id production/postgres-password \
  --secret-string "$NEW_PASSWORD"

# Update database user password
docker-compose exec postgres \
  psql -U postgres -c "ALTER USER positivity WITH PASSWORD '$NEW_PASSWORD';"

# Restart services to pick up new password
docker-compose up -d --force-recreate

# Verify services are healthy
sleep 10
docker-compose exec pos-accounting curl -f http://localhost:8081/actuator/health
```

---

## Troubleshooting

### Service Can't Connect to Database

**Symptom:** `Connection refused` or `Authentication failed`

**Solution:**
```bash
# 1. Verify environment variables are set
env | grep SPRING_DATASOURCE_PASSWORD

# 2. Check .env file exists
ls -la .env

# 3. Source .env before running compose
source .env && docker-compose up -d

# 4. Check PostgreSQL logs
docker-compose logs postgres
```

### Wrong Password Error

**Symptom:** "password authentication failed" in logs

**Solution:**
```bash
# 1. Verify password in .env matches PostgreSQL setup
cat .env | grep POSTGRES_PASSWORD

# 2. Restart with new .env
docker-compose down
docker-compose up -d

# 3. Or update password directly
docker-compose exec postgres psql -U postgres -c "ALTER USER positivity WITH PASSWORD 'newpassword';"
```

### Environment Variables Not Loading

**Symptom:** Spring Boot shows default values, not from .env

**Solution:**
```bash
# 1. Use --env-file explicitly
docker-compose --env-file .env up -d

# 2. Or export before running
export $(cat .env | xargs)
docker-compose up -d

# 3. Verify in container
docker-compose exec pos-accounting env | grep SPRING_DATASOURCE
```

---

## Best Practices

### ✅ DO

1. **Use environment variables for all secrets**
   ```yaml
   password: ${SPRING_DATASOURCE_PASSWORD}  # ✅ Good
   ```

2. **Keep .env out of git**
   ```bash
   # In .gitignore
   .env
   .env.*
   !.env.example
   ```

3. **Use strong passwords**
   ```bash
   # ✅ Good - 32 character random password
   openssl rand -base64 32
   ```

4. **Rotate secrets regularly**
   - Every 30-90 days for production
   - Document rotation in runbooks

5. **Use default values for non-secrets**
   ```yaml
   username: ${SPRING_DATASOURCE_USERNAME:pos_user}  # ✅ Safe default
   ```

6. **Document all secrets**
   - Keep inventory of all environment variables
   - Document where each secret is used
   - Track rotation schedule

### ❌ DON'T

1. **Hardcode secrets**
   ```java
   String password = "hardcoded_password";  // ❌ NEVER
   ```

2. **Commit .env files**
   ```bash
   git add .env  # ❌ NEVER
   ```

3. **Log secrets**
   ```bash
   echo "Password: $POSTGRES_PASSWORD"  # ❌ NEVER
   ```

4. **Use weak passwords**
   ```bash
   password=password123  # ❌ Too weak
   ```

5. **Store secrets in README**
   ```markdown
   Password: admin
   Secret: secret123  # ❌ NEVER
   ```

6. **Share secrets via chat/email**
   - Use secure secret management system
   - Rotate immediately if compromised

---

## Quick Reference Commands

```bash
# Setup
cp .env.example .env
nano .env

# Start services
source .env && docker-compose up -d

# Verify secrets are loaded
docker-compose exec pos-accounting env | grep SPRING_DATASOURCE

# Rotate a secret
docker-compose exec postgres psql -U postgres -c "ALTER USER positivity WITH PASSWORD 'newpass';"

# Check logs
docker-compose logs pos-accounting | grep -i datasource

# Verify no hardcoded secrets
grep -r "password: positivity" .
grep -r "POSTGRES_PASSWORD: positivity" .
```

---

## Support & Questions

For issues or questions about secrets management:

1. Check this guide first
2. Review troubleshooting section
3. Check service logs: `docker-compose logs <service-name>`
4. Consult the team lead or security officer for production issues
5. Report security issues privately

---

**Last Updated:** January 30, 2026  
**Status:** Production Ready  
**Compliance:** OWASP, 12-Factor App, SOC 2
