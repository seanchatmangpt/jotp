#!/usr/bin/env bash
#
# JOTP Development Environment Startup Script
# Initializes Redis, PostgreSQL, Maven proxy, and prepares the development environment
#
# Usage:
#   ./scripts/startup.sh                  # Start all services
#   ./scripts/startup.sh --redis-only     # Start only Redis
#   ./scripts/startup.sh --postgres-only  # Start only PostgreSQL
#   ./scripts/startup.sh --maven-proxy    # Configure Maven proxy only
#   ./scripts/startup.sh --help           # Show help
#
# Prerequisites:
#   - Docker and Docker Compose installed
#   - Maven or mvnd available
#   - Java 26+ for compilation
#

set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
DOCKER_COMPOSE_FILE="${PROJECT_ROOT}/docker-compose.yml"
MAVEN_PROXY_HOST="${MAVEN_PROXY_HOST:-localhost}"
MAVEN_PROXY_PORT="${MAVEN_PROXY_PORT:-8081}"
REDIS_PORT="${REDIS_PORT:-6379}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"
POSTGRES_DB="${POSTGRES_DB:-jotp}"
POSTGRES_USER="${POSTGRES_USER:-jotp}"
POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-jotp_secret}"

# Logging functions
log_info() {
    echo -e "${BLUE}[INFO]${NC} $*"
}

log_success() {
    echo -e "${GREEN}[✓]${NC} $*"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $*"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $*"
}

# Help function
show_help() {
    cat << 'EOF'
JOTP Development Environment Startup Script

Usage:
  ./scripts/startup.sh [OPTIONS]

Options:
  --all                 Start all services (default)
  --redis-only          Start only Redis
  --postgres-only       Start only PostgreSQL
  --maven-proxy         Configure Maven proxy only
  --stop                Stop all running services
  --status              Show status of services
  --clean               Remove all volumes and containers
  --help                Show this help message

Environment Variables:
  MAVEN_PROXY_HOST      Maven proxy host (default: localhost)
  MAVEN_PROXY_PORT      Maven proxy port (default: 8081)
  REDIS_PORT            Redis port (default: 6379)
  POSTGRES_PORT         PostgreSQL port (default: 5432)
  POSTGRES_DB           PostgreSQL database name (default: jotp)
  POSTGRES_USER         PostgreSQL user (default: jotp)
  POSTGRES_PASSWORD     PostgreSQL password (default: jotp_secret)

Examples:
  # Start all services with default settings
  ./scripts/startup.sh

  # Start only PostgreSQL and Redis (no Maven proxy)
  docker-compose up -d postgres redis

  # Check service status
  ./scripts/startup.sh --status

  # Stop all services
  ./scripts/startup.sh --stop

  # Clean up everything
  ./scripts/startup.sh --clean
EOF
}

# Check prerequisites
check_prerequisites() {
    log_info "Checking prerequisites..."

    if ! command -v docker &> /dev/null; then
        log_error "Docker is not installed. Please install Docker first."
        exit 1
    fi

    if ! command -v docker-compose &> /dev/null; then
        log_error "Docker Compose is not installed. Please install Docker Compose first."
        exit 1
    fi

    if [ ! -f "$DOCKER_COMPOSE_FILE" ]; then
        log_error "docker-compose.yml not found at: $DOCKER_COMPOSE_FILE"
        exit 1
    fi

    log_success "All prerequisites met"
}

# Configure Maven proxy
configure_maven_proxy() {
    log_info "Configuring Maven proxy..."

    # Create .mvn directory if it doesn't exist
    mkdir -p "$PROJECT_ROOT/.mvn"

    # Create Maven settings template
    local settings_template="$PROJECT_ROOT/.mvn/settings-proxy.xml"
    cat > "$settings_template" << 'MAVEN_EOF'
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0
                              http://maven.apache.org/xsd/settings-1.0.0.xsd">

    <!-- Maven Repository Proxy Configuration -->
    <proxies>
        <proxy>
            <id>maven-proxy</id>
            <active>true</active>
            <protocol>http</protocol>
            <host>${MAVEN_PROXY_HOST}</host>
            <port>${MAVEN_PROXY_PORT}</port>
            <nonProxyHosts>localhost|127.0.0.1</nonProxyHosts>
        </proxy>
    </proxies>

    <!-- Mirror Configuration (optional) -->
    <mirrors>
        <!-- Use proxy for all repositories -->
        <mirror>
            <id>maven-proxy-mirror</id>
            <mirrorOf>*</mirrorOf>
            <url>http://${MAVEN_PROXY_HOST}:${MAVEN_PROXY_PORT}/repository/maven-public/</url>
        </mirror>
    </mirrors>

    <!-- Local Repository -->
    <localRepository>${user.home}/.m2/repository</localRepository>

    <!-- Optional: Maven Central configuration -->
    <repositories>
        <repository>
            <id>central</id>
            <url>https://repo.maven.apache.org/maven2</url>
            <snapshots>
                <enabled>false</enabled>
            </snapshots>
        </repository>
    </repositories>

    <pluginRepositories>
        <pluginRepository>
            <id>central</id>
            <url>https://repo.maven.apache.org/maven2</url>
            <snapshots>
                <enabled>false</enabled>
            </snapshots>
        </pluginRepository>
    </pluginRepositories>

</settings>
MAVEN_EOF

    log_success "Maven proxy configuration template created at: $settings_template"
    log_info "To activate proxy, set: export MAVEN_OPTS='-s $settings_template'"
}

# Start services
start_services() {
    local services=$1

    log_info "Starting services: $services"
    cd "$PROJECT_ROOT"

    if [ -z "$services" ]; then
        # Start all services
        docker-compose up -d
        services="postgres redis"
    else
        docker-compose up -d $services
    fi

    log_info "Waiting for services to be healthy..."
    sleep 5

    # Check service health
    check_service_health "$services"

    log_success "Services started successfully"
}

# Check service health
check_service_health() {
    local services=$1

    for service in $services; do
        case $service in
            postgres)
                log_info "Checking PostgreSQL health..."
                if docker-compose exec -T postgres pg_isready -U "$POSTGRES_USER" &> /dev/null; then
                    log_success "PostgreSQL is healthy (port $POSTGRES_PORT)"
                else
                    log_warn "PostgreSQL not yet ready, retrying..."
                    sleep 3
                fi
                ;;
            redis)
                log_info "Checking Redis health..."
                if docker-compose exec -T redis redis-cli ping &> /dev/null; then
                    log_success "Redis is healthy (port $REDIS_PORT)"
                else
                    log_warn "Redis not yet ready, retrying..."
                    sleep 3
                fi
                ;;
        esac
    done
}

# Stop services
stop_services() {
    log_info "Stopping services..."
    cd "$PROJECT_ROOT"
    docker-compose down
    log_success "Services stopped"
}

# Show service status
show_status() {
    log_info "Service Status:"
    cd "$PROJECT_ROOT"
    docker-compose ps

    log_info ""
    log_info "Service Details:"
    log_info "  PostgreSQL:  $POSTGRES_HOST:$POSTGRES_PORT (database: $POSTGRES_DB)"
    log_info "  Redis:       localhost:$REDIS_PORT"
    log_info "  Maven Proxy: $MAVEN_PROXY_HOST:$MAVEN_PROXY_PORT"
}

# Clean up
cleanup_services() {
    log_warn "Cleaning up all services and volumes..."
    read -p "Are you sure you want to remove all containers and volumes? (y/N) " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        cd "$PROJECT_ROOT"
        docker-compose down -v
        log_success "Cleanup completed"
    else
        log_info "Cleanup cancelled"
    fi
}

# Initialize PostgreSQL with schema
initialize_postgres() {
    log_info "Initializing PostgreSQL database..."

    cd "$PROJECT_ROOT"

    # Wait for PostgreSQL to be ready
    log_info "Waiting for PostgreSQL to be ready..."
    local max_attempts=30
    local attempt=0
    while ! docker-compose exec -T postgres pg_isready -U "$POSTGRES_USER" &> /dev/null; do
        attempt=$((attempt + 1))
        if [ $attempt -ge $max_attempts ]; then
            log_error "PostgreSQL failed to start after $max_attempts attempts"
            return 1
        fi
        sleep 2
    done

    log_success "PostgreSQL is ready"

    # Create schema if needed
    local init_script="$PROJECT_ROOT/docker/postgres/init/01-init-databases.sh"
    if [ -f "$init_script" ]; then
        log_info "Running initialization script..."
        bash "$init_script"
        log_success "PostgreSQL initialization completed"
    else
        log_warn "No initialization script found at: $init_script"
    fi
}

# Main function
main() {
    log_info "JOTP Development Environment Startup"
    log_info "========================================"

    # Parse arguments
    local action="all"

    while [[ $# -gt 0 ]]; do
        case $1 in
            --all)
                action="all"
                shift
                ;;
            --redis-only)
                action="redis_only"
                shift
                ;;
            --postgres-only)
                action="postgres_only"
                shift
                ;;
            --maven-proxy)
                action="maven_proxy"
                shift
                ;;
            --stop)
                action="stop"
                shift
                ;;
            --status)
                action="status"
                shift
                ;;
            --clean)
                action="clean"
                shift
                ;;
            --help|-h)
                show_help
                exit 0
                ;;
            *)
                log_error "Unknown option: $1"
                show_help
                exit 1
                ;;
        esac
    done

    # Execute action
    case $action in
        all)
            check_prerequisites
            start_services "postgres redis"
            configure_maven_proxy
            initialize_postgres
            log_success "All services started and configured"
            show_status
            ;;
        redis_only)
            check_prerequisites
            start_services "redis"
            ;;
        postgres_only)
            check_prerequisites
            start_services "postgres"
            initialize_postgres
            ;;
        maven_proxy)
            configure_maven_proxy
            ;;
        stop)
            stop_services
            ;;
        status)
            show_status
            ;;
        clean)
            cleanup_services
            ;;
        *)
            log_error "Unknown action: $action"
            show_help
            exit 1
            ;;
    esac
}

# Run main function
main "$@"
