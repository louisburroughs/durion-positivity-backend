# Quick Command Reference - Secrets & Docker Compose

## Verification & Testing

```bash
# Verify all secrets are externalized
./verify-docker-compose-secrets.sh

# Check for any remaining hardcoded passwords
grep -r "password: positivity" .
grep -r "POSTGRES_PASSWORD: positivity" docker-compose.yml
grep -r "GF_SECURITY_ADMIN_PASSWORD: admin" docker-compose.yml
```

## Local Development

```bash
# Initial setup
cp .env.example .env
# Edit .env and replace all CHANGE_ME values with actual passwords

# Start services with secrets
source .env && docker-compose up -d

# Or all in one line
$(source .env; docker-compose up -d)

# Check service health
docker-compose ps

# View logs
docker-compose logs pos-accounting
docker-compose logs postgres
docker-compose logs grafana

# Stop services
docker-compose down

# Remove volumes (to reset database)
docker-compose down -v
```

## Environment Variables

```bash
# Show all environment variables being used
docker-compose config | grep -A 100 "environment:"

# Set specific variable for a command
SPRING_DATASOURCE_PASSWORD=mypassword docker-compose up -d

# Set multiple variables
export SPRING_DATASOURCE_PASSWORD=dbpassword
export GF_SECURITY_ADMIN_PASSWORD=grafanapassword
docker-compose up -d
```

## Database Operations

```bash
# Connect to PostgreSQL
docker-compose exec postgres psql -U positivity -d positivity

# Show databases
docker-compose exec postgres psql -U positivity -l

# Backup database
docker-compose exec postgres pg_dump -U positivity positivity > backup.sql

# Restore database
docker-compose exec -T postgres psql -U positivity positivity < backup.sql

# Check PostgreSQL is running
docker-compose exec postgres pg_isready -U positivity
```

## Service Health Checks

```bash
# Check all services health
for port in 8080 8081 8082 8083 8084 8085 8086 8087 8088 8089 8090 8091 8092 8093; do
  echo "Port $port:"
  curl -s http://localhost:$port/actuator/health | jq .
done

# Check Grafana
curl -s http://localhost:3000/api/health

# Check PostgreSQL
curl -s http://localhost:5432/

# Check Prometheus
curl -s http://localhost:9090/-/ready

# Check Jaeger
curl -s http://localhost:16686/api/v1/services
```

## Common Issues & Solutions

```bash
# PostgreSQL password not working
# Verify the value in .env
cat .env | grep POSTGRES_PASSWORD

# Spring Datasource won't connect
# Check Spring datasource logs
docker-compose logs pos-accounting | grep -i datasource

# Port already in use
# Find what's using the port
lsof -i :8080
netstat -tlnp | grep 8080

# Docker volume issues
# Clean up all volumes
docker-compose down -v
docker volume prune

# Rebuild containers with new env vars
docker-compose up -d --build
```

## Secret Management

```bash
# Never commit .env files
git status  # Should show .env as not tracked

# Verify .env.example is the only committed env file
git ls-files | grep "\.env"

# Create environment-specific files
cp .env.example .env.staging
# Edit with staging credentials

# Use specific env file
docker-compose --env-file .env.staging up -d

# Verify no secrets in source
grep -r "positivity" --exclude-dir=.git --exclude="*.md"
grep -r "changeit" --exclude-dir=.git
grep -r "password: admin" --exclude-dir=.git --exclude="*.md"
```

## CI/CD Integration

```bash
# GitHub Actions - in workflow file
env:
  POSTGRES_PASSWORD: ${{ secrets.POSTGRES_PASSWORD }}
  SPRING_DATASOURCE_PASSWORD: ${{ secrets.SPRING_DATASOURCE_PASSWORD }}
  GF_SECURITY_ADMIN_PASSWORD: ${{ secrets.GRAFANA_ADMIN_PASSWORD }}

# GitLab CI - in .gitlab-ci.yml
variables:
  POSTGRES_PASSWORD: $POSTGRES_PASSWORD
  SPRING_DATASOURCE_PASSWORD: $SPRING_DATASOURCE_PASSWORD

# Or pass at runtime
docker-compose up -d \
  --env POSTGRES_PASSWORD=$POSTGRES_PASSWORD \
  --env SPRING_DATASOURCE_PASSWORD=$SPRING_DATASOURCE_PASSWORD
```

## Monitoring & Observability

```bash
# View metrics in Prometheus
curl http://localhost:9090/api/v1/query?query=up

# View traces in Jaeger
# Open http://localhost:16686 in browser

# View dashboards in Grafana
# Open http://localhost:3000 in browser
# Default login: admin / password from GF_SECURITY_ADMIN_PASSWORD

# Check OTLP collector
curl -s http://localhost:13133/  # Health check
```

## Documentation Files

- **Setup Guide:** `SECRETS_MANAGEMENT_GUIDE.md`
- **Docker Migration:** `DOCKER_COMPOSE_SECRETS_MIGRATION.md`  
- **Completion Report:** `SECRETS_COMPLETION_REPORT.md`
- **Verification Script:** `verify-docker-compose-secrets.sh`
- **Environment Template:** `.env.example`

## Related Commands

```bash
# Build the entire project
./mvnw clean package -DskipTests

# Build a specific module
./mvnw clean package -DskipTests -pl pos-accounting

# Run tests
./mvnw test

# Check for hardcoded secrets across all Java files
grep -r "\"positivity\"" src/
grep -r "password.*=" src/ | grep -v "${" | grep -v "//"
```

---

**Remember:** Never commit `.env` files to git. Always use `.env.example` as your template and `.gitignore` will prevent accidents.
