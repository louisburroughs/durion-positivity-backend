# PostgreSQL Docker Container Setup

## Quick Start

PostgreSQL has been added to `docker-compose.yml`. Start it with your other services:

```bash
# Start all services including PostgreSQL
docker-compose up -d

# View logs
docker-compose logs -f postgres

# Stop all services
docker-compose down

# Stop and remove volumes (clean database)
docker-compose down -v
```

## Connection Details

| Property | Value |
|----------|-------|
| **Host** | `postgres` (from within Docker network) or `localhost` (from host) |
| **Port** | `5432` |
| **Username** | `positivity` |
| **Password** | `positivity` |
| **Database** | `positivity` |
| **Container Name** | `postgres-positivity` |

## JDBC Connection String

```
jdbc:postgresql://postgres:5432/positivity
```

Or from host machine:
```
jdbc:postgresql://localhost:5432/positivity
```

## Environment Variables for Services

To connect services running in Docker to PostgreSQL, add these environment variables to each service in `docker-compose.yml`:

```yaml
environment:
  SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/positivity
  SPRING_DATASOURCE_USERNAME: positivity
  SPRING_DATASOURCE_PASSWORD: positivity
  SPRING_DATASOURCE_DRIVER_CLASS_NAME: org.postgresql.Driver
```

## psql Access

Connect directly to PostgreSQL from the host:

```bash
# Using psql
psql -h localhost -U positivity -d positivity

# Using Docker exec
docker exec -it postgres-positivity psql -U positivity -d positivity
```

## Persistent Storage

- Database data is stored in the `postgres-data` volume
- Volume survives container restarts: `docker-compose down`
- Volume is removed only with: `docker-compose down -v`

## Troubleshooting

```bash
# Check if PostgreSQL is running
docker-compose ps postgres

# View logs
docker-compose logs postgres

# Access PostgreSQL container shell
docker exec -it postgres-positivity sh

# Verify database exists
docker exec postgres-positivity psql -U positivity -l
```

## Updating Service Dependencies

Add PostgreSQL as a dependency to services that need it:

```yaml
service-name:
  depends_on:
    postgres:
      condition: service_healthy
    eureka-server:
      condition: service_healthy
```

This ensures PostgreSQL starts before the service.
