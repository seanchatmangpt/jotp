# JOTP Development Environment Setup
## Redis, PostgreSQL, and Maven Proxy Configuration

This guide walks you through setting up a complete development environment for JOTP with Redis, PostgreSQL, and Maven proxy caching.

## Quick Start

```bash
# Make startup script executable
chmod +x scripts/startup.sh

# Start all services (PostgreSQL, Redis) and configure Maven proxy
./scripts/startup.sh

# Verify services are running
./scripts/startup.sh --status
```

## Prerequisites

- **Docker** 20.10+ ([Install Docker](https://docs.docker.com/get-docker/))
- **Docker Compose** 2.0+ ([Install Docker Compose](https://docs.docker.com/compose/install/))
- **Java 26** with preview features enabled
- **Maven 4.0+** or use included Maven Wrapper (`./mvnw`)
- **Git** for version control

## Components

### 1. **PostgreSQL Database**
- Version: `postgres:16-alpine`
- Port: `5432` (default)
- Database: `jotp` (configurable)
- User: `jotp` (configurable)
- Password: `jotp_secret` (change in production!)
- Features:
  - Persistent data storage
  - Health checks
  - Auto-initialization scripts

### 2. **Redis Cache**
- Version: `redis:7-alpine`
- Port: `6379` (default)
- Features:
  - Append-only file (AOF) persistence
  - Data volume persistence
  - Health checks
  - Optional password protection

### 3. **Maven Repository Proxy**
Two options available:

#### **Option A: Nexus Repository Manager** (Recommended for Production)
- Port: `8081`
- Features:
  - Central repository caching
  - Artifact management
  - Multi-format support (Maven, npm, Docker, etc.)
- Requires ~2GB RAM
- Default credentials: `admin / admin123`

#### **Option B: Artifactory OSS** (Lightweight Alternative)
- Port: `8082`
- Features:
  - Lightweight (~500MB)
  - Fast artifact resolution
  - Docker registry support
- Default credentials: `admin / password`

## Startup Script Usage

### Start Everything
```bash
./scripts/startup.sh --all
```

Starts:
- PostgreSQL database
- Redis cache
- Configures Maven proxy settings

### Start Specific Services
```bash
# PostgreSQL only
./scripts/startup.sh --postgres-only

# Redis only
./scripts/startup.sh --redis-only

# Maven proxy configuration only (no Docker services)
./scripts/startup.sh --maven-proxy
```

### Service Management
```bash
# Check service status
./scripts/startup.sh --status

# Stop all services
./scripts/startup.sh --stop

# Clean up all containers and volumes
./scripts/startup.sh --clean
```

### Help
```bash
./scripts/startup.sh --help
```

## Environment Variables

Configure services via environment variables:

```bash
# PostgreSQL Configuration
export POSTGRES_DB=jotp
export POSTGRES_USER=jotp
export POSTGRES_PASSWORD=jotp_secret
export POSTGRES_PORT=5432

# Redis Configuration
export REDIS_PORT=6379
export REDIS_PASSWORD=""  # Optional

# Maven Proxy Configuration
export MAVEN_PROXY_HOST=localhost
export MAVEN_PROXY_PORT=8081

# Start with custom configuration
./scripts/startup.sh --all
```

## Maven Proxy Configuration

### Using with Nexus (Recommended)

1. **Start Nexus service:**
   ```bash
   docker-compose -f docker-compose-maven-proxy.yml up -d nexus
   ```

2. **Wait for Nexus to initialize** (30-60 seconds)

3. **Configure Maven settings:**
   ```bash
   # Copy template settings
   cp .mvn/settings-template.xml ~/.m2/settings.xml

   # Or reference during build
   mvn -s .mvn/settings-template.xml clean compile
   ```

4. **Test Maven connectivity:**
   ```bash
   mvn help:describe -Dplugin=org.apache.maven.plugins:maven-compiler-plugin
   ```

### Using with Artifactory (Lightweight)

1. **Start Artifactory service:**
   ```bash
   docker-compose -f docker-compose-maven-proxy.yml --profile artifactory up -d artifactory
   ```

2. **Configure Maven settings:**
   Edit `.mvn/settings-template.xml` and:
   - Set `artifactory-mirror` to active
   - Set `nexus-mirror` to false

3. **Test Maven connectivity:**
   ```bash
   mvn dependency:resolve
   ```

### Direct Maven Central (No Proxy)

If you prefer not to use a local proxy, Maven will resolve artifacts directly from Maven Central:

```bash
# No special configuration needed
mvn clean compile
```

**Trade-off:** Slower initial builds, but no additional Docker services required.

## Docker Compose Commands

### Using Default Compose File
```bash
# Start PostgreSQL and Redis only
docker-compose up -d postgres redis

# Stop services
docker-compose down

# View logs
docker-compose logs -f postgres redis

# Execute commands in containers
docker-compose exec postgres psql -U jotp -d jotp
docker-compose exec redis redis-cli
```

### Using Maven Proxy Compose File
```bash
# Start all services including Nexus
docker-compose -f docker-compose-maven-proxy.yml up -d

# Start with Artifactory instead
docker-compose -f docker-compose-maven-proxy.yml --profile artifactory up -d artifactory postgres redis

# Stop all services
docker-compose -f docker-compose-maven-proxy.yml down -v
```

## Verification

### Check Services Are Running
```bash
# List running containers
docker ps

# Output should include:
# - jotp-postgres (or similar)
# - jotp-redis (or similar)
# - jotp-nexus (if proxy enabled)
```

### Test PostgreSQL Connection
```bash
# From inside container
docker-compose exec postgres psql -U jotp -d jotp -c "SELECT version();"

# From host (requires psql installed)
psql -h localhost -U jotp -d jotp -c "SELECT version();"
```

### Test Redis Connection
```bash
# From inside container
docker-compose exec redis redis-cli ping

# Expected output: PONG

# From host (requires redis-cli installed)
redis-cli -h localhost ping
```

### Test Maven Proxy
```bash
# List Nexus repositories
curl -u admin:admin123 http://localhost:8081/service/rest/v1/repositories

# Test artifact resolution
mvn dependency:get -Dartifact=org.junit.jupiter:junit-jupiter:5.12.2 \
    -Ddest=/tmp/junit.jar
```

## Performance Tuning

### PostgreSQL Configuration

Add to `docker-compose.yml` environment section:
```yaml
environment:
  - POSTGRES_INITDB_ARGS=-c shared_buffers=256MB -c effective_cache_size=1GB -c work_mem=16MB
```

### Redis Configuration

For production workloads:
```bash
docker-compose exec redis redis-cli CONFIG SET maxmemory 512mb
docker-compose exec redis redis-cli CONFIG SET maxmemory-policy allkeys-lru
```

### Maven Proxy Memory Tuning

**Nexus (in docker-compose-maven-proxy.yml):**
```yaml
environment:
  - JAVA_OPTS=-Xms2g -Xmx2g
```

**Artifactory (in docker-compose-maven-proxy.yml):**
```yaml
environment:
  - JAVA_OPTS=-Xms512m -Xmx1g
```

## Troubleshooting

### Services Won't Start

```bash
# Check Docker daemon
docker version

# Review service logs
docker-compose logs -f

# Verify ports are not in use
lsof -i :5432  # PostgreSQL
lsof -i :6379  # Redis
lsof -i :8081  # Nexus proxy
```

### PostgreSQL Won't Initialize

```bash
# Check initialization logs
docker-compose logs postgres

# Recreate container
docker-compose down -v postgres
docker-compose up -d postgres

# Run init manually
docker-compose exec postgres bash /docker-entrypoint-initdb.d/01-init-databases.sh
```

### Redis Data Loss

```bash
# Check AOF (Append-Only File) persistence
docker-compose exec redis redis-cli BGREWRITEAOF

# Verify data directory
docker volume inspect jotp-redis-data
```

### Maven Proxy Not Found

```bash
# Check Nexus is running
curl -u admin:admin123 http://localhost:8081/service/rest/v1/status

# Verify network connectivity
docker network inspect jotp-network

# Check Maven settings
cat ~/.m2/settings.xml | grep -A 5 proxy
```

## Integration with JOTP Build

### Using Make Targets
```bash
# Compile with Maven proxy
make compile

# Run tests
make test

# Full verification (tests + quality checks)
make verify
```

### Using Maven Directly
```bash
# With proxy settings
mvn -s .mvn/settings-template.xml clean verify

# With environment variable
export MAVEN_OPTS="-s $(pwd)/.mvn/settings-template.xml"
mvn clean verify
```

### Using Maven Daemon (mvnd)
```bash
# Faster builds with persistent JVM
mvnd clean verify

# With proxy settings
mvnd -s .mvn/settings-template.xml clean verify
```

## Database Initialization

The PostgreSQL container automatically runs initialization scripts from:
```
docker/postgres/init/*.sh
```

If you need to create custom schemas or load data:

1. **Create init script:**
   ```bash
   mkdir -p docker/postgres/init
   cat > docker/postgres/init/02-create-tables.sql << 'EOF'
   CREATE TABLE IF NOT EXISTS events (
       id SERIAL PRIMARY KEY,
       event_type VARCHAR(50),
       created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
   );
   EOF
   ```

2. **Recreate PostgreSQL container:**
   ```bash
   docker-compose down -v postgres
   docker-compose up -d postgres
   ```

## Cleanup and Reset

### Remove All Containers and Volumes
```bash
./scripts/startup.sh --clean
```

### Remove Only Database Data
```bash
docker volume rm jotp-postgres-data
docker-compose up -d postgres
```

### Remove Only Cache Data
```bash
docker volume rm jotp-redis-data
docker-compose up -d redis
```

### Full System Reset
```bash
# Stop all services
docker-compose down -v

# Remove all volumes
docker volume rm jotp-postgres-data jotp-redis-data jotp-nexus-data

# Remove all images (optional)
docker image rm postgres:16-alpine redis:7-alpine
```

## Security Considerations

### Production Deployment

1. **Change default passwords:**
   ```bash
   export POSTGRES_PASSWORD=secure_password_here
   export REDIS_PASSWORD=secure_password_here
   ```

2. **Use environment files:**
   ```bash
   # Create .env file (add to .gitignore)
   echo "POSTGRES_PASSWORD=prod_secret" > .env
   docker-compose --env-file .env up -d
   ```

3. **Configure network isolation:**
   ```bash
   # Restrict port exposure in docker-compose.yml
   # Remove ports: section for internal services only
   ```

4. **Enable SSL/TLS:**
   - PostgreSQL: [SSL Setup](https://www.postgresql.org/docs/16/ssl-tcp.html)
   - Redis: [Redis TLS](https://redis.io/docs/management/security/encryption/)
   - Nexus: [HTTPS Configuration](https://help.sonatype.com/repomanager3/planning-your-deployment/system-requirements#ssl)

5. **Use secrets management:**
   - Kubernetes Secrets for production
   - Docker Secrets for Swarm mode
   - HashiCorp Vault for external services

## Next Steps

1. **Read JOTP Documentation:**
   - [Architecture Guide](../docs/ARCHITECTURE.md)
   - [User Guide](../docs/user-guide/getting-started.md)

2. **Run Examples:**
   ```bash
   make test T=ProcTest
   ```

3. **Explore Dogfood Tests:**
   Self-validation of JOTP using JOTP patterns

4. **Build and Deploy:**
   ```bash
   make verify && make package
   ```

## Additional Resources

- [JOTP GitHub Repository](https://github.com/seanchatmangpt/jotp)
- [Docker Documentation](https://docs.docker.com/)
- [Maven Central Repository](https://repo.maven.apache.org/maven2)
- [PostgreSQL Documentation](https://www.postgresql.org/docs/)
- [Redis Documentation](https://redis.io/docs/)
- [Sonatype Nexus](https://help.sonatype.com/repomanager3)
- [JFrog Artifactory](https://jfrog.com/help/r/artifactory)

## Getting Help

If you encounter issues:

1. Check the [Troubleshooting](#troubleshooting) section
2. Review Docker Compose logs: `docker-compose logs -f`
3. Check JOTP GitHub Issues: [Issues](https://github.com/seanchatmangpt/jotp/issues)
4. Review Maven logs for build issues

---

**Last Updated:** 2026-03-17
**JOTP Version:** 2026.1.0
**Java Version Required:** Java 26+
