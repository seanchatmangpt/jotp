# JOTP Docker Support

This directory contains Docker-related scripts and documentation for the JOTP project.

## Quick Start

### Build the Docker image

```bash
./docker/docker-build.sh
```

### Run the application

```bash
./docker/docker-run.sh
```

### Run tests in Docker

```bash
./docker/docker-test.sh
```

### Using docker-compose

```bash
# Start all services (application + dependencies)
docker-compose up -d

# Start only the application
docker-compose up -d jotp

# View logs
docker-compose logs -f jotp

# Stop all services
docker-compose down
```

## Script Details

### `docker-build.sh`

Builds Docker images for the JOTP project.

**Usage:**
```bash
./docker/docker-build.sh [OPTIONS]
```

**Options:**
- `--target=TARGET` - Build target: `runtime`, `test`, or `development` [default: runtime]
- `--tag=TAG` - Docker image tag [default: latest]
- `--no-cache` - Build without using cache
- `--push` - Push to registry after build
- `--registry=URL` - Docker registry [default: docker.io]
- `--help` - Show help message

**Examples:**
```bash
# Build runtime image
./docker/docker-build.sh

# Build test image with specific tag
./docker/docker-build.sh --target=test --tag=v1.0.0

# Build without cache and push
./docker/docker-build.sh --no-cache --push
```

### `docker-run.sh`

Runs JOTP containers with configurable options.

**Usage:**
```bash
./docker/docker-run.sh [OPTIONS]
```

**Options:**
- `--target=TARGET` - Build target [default: runtime]
- `--tag=TAG` - Docker image tag [default: latest]
- `--port=PORT` - Host port mapping [default: 8080]
- `--debug` - Enable debug port (5005)
- `--build` - Build image before running
- `--detach` - Run in detached mode
- `--rm` - Remove container on exit
- `--env=FILE` - Load environment variables from file
- `--help` - Show help message

**Examples:**
```bash
# Run in interactive mode
./docker/docker-run.sh

# Run in detached mode with debug port
./docker/docker-run.sh --detach --debug

# Run with custom port and environment file
./docker/docker-run.sh --port=9090 --env=.env.production
```

### `docker-test.sh`

Runs tests inside a Docker container.

**Usage:**
```bash
./docker/docker-test.sh [OPTIONS]
```

**Options:**
- `--tag=TAG` - Docker image tag [default: latest]
- `--build` - Build test image before running
- `--coverage` - Generate coverage report
- `--integration` - Run integration tests
- `--unit` - Run unit tests only
- `--parallel=N` - Run tests with N threads [default: auto]
- `--help` - Show help message

**Examples:**
```bash
# Run all tests
./docker/docker-test.sh

# Run unit tests only with coverage
./docker/docker-test.sh --unit --coverage

# Run integration tests in parallel
./docker/docker-test.sh --integration --parallel=4
```

## Docker Stages

The Dockerfile includes multiple build stages:

1. **builder** - Compiles the project
2. **runtime** - Minimal runtime image (default)
3. **test** - Test execution environment
4. **development** - Full development environment with debugging

## Environment Variables

Configure using environment variables or `.env` file:

- `VERSION` - Docker image version tag
- `SPRING_PROFILES_ACTIVE` - Spring profile (prod/dev/test)
- `LOG_LEVEL` - Logging level (INFO/DEBUG/ERROR)
- `POSTGRES_DB` - PostgreSQL database name
- `POSTGRES_USER` - PostgreSQL user
- `POSTGRES_PASSWORD` - PostgreSQL password

## Volumes

- `jotp-logs` - Application logs
- `jotp-postgres-data` - PostgreSQL data
- `jotp-redis-data` - Redis data
- `jotp-test-results` - Test result reports

## Networking

All services communicate over the `jotp-network` bridge network for isolation.

## Health Checks

The application includes health checks at:
- HTTP: `http://localhost:8080/actuator/health`
- Container-level health checks configured

## Security

- Non-root user (`jotp`) for runtime
- Minimal base images (Alpine)
- Security scanning support (Trivy)
- Secrets via environment variables

## Troubleshooting

### View container logs
```bash
docker logs -f jotp-app
```

### Execute commands in container
```bash
docker exec -it jotp-app bash
```

### Rebuild without cache
```bash
docker-compose build --no-cache
```

### Clean up everything
```bash
docker-compose down -v
docker system prune -a
```

## Production Considerations

For production deployments:

1. Use specific version tags instead of `latest`
2. Enable resource limits in docker-compose.yml
3. Configure proper secrets management (not environment variables)
4. Set up log aggregation drivers
5. Configure restart policies appropriately
6. Use health checks for orchestration
7. Scan images for vulnerabilities
8. Sign images for trust verification

## CI/CD Integration

Example GitHub Actions workflow:

```yaml
name: Docker

on: push

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Build Docker image
        run: ./docker/docker-build.sh --tag=${{ github.sha }}
      - name: Run tests
        run: ./docker/docker-test.sh --tag=${{ github.sha }}
```
