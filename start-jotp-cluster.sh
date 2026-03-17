#!/bin/bash
set -e

# JOTP Docker Compose Quick Start Script
# This script sets up and starts a complete JOTP cluster environment

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Functions
print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check if Docker is installed
check_docker() {
    if ! command -v docker &> /dev/null; then
        print_error "Docker is not installed. Please install Docker first."
        exit 1
    fi
    print_info "Docker is installed: $(docker --version)"
}

# Check if Docker Compose is installed
check_docker_compose() {
    if ! command -v docker-compose &> /dev/null && ! docker compose version &> /dev/null; then
        print_error "Docker Compose is not installed. Please install Docker Compose first."
        exit 1
    fi
    print_info "Docker Compose is available"
}

# Create .env.jotp from example if it doesn't exist
setup_environment() {
    if [ ! -f .env.jotp ]; then
        print_info "Creating .env.jotp from .env.jotp.example"
        cp .env.jotp.example .env.jotp
        print_warn "Please review and customize .env.jotp for your environment"
    else
        print_info ".env.jotp already exists, skipping creation"
    fi
}

# Create necessary directories
create_directories() {
    print_info "Creating necessary directories"
    mkdir -p docker/postgres/init
    mkdir -p docker/prometheus/rules
    mkdir -p docker/grafana/provisioning/datasources
    mkdir -p docker/grafana/provisioning/dashboards
    mkdir -p docker/grafana/dashboards
    mkdir -p docker/alertmanager
    mkdir -p docker/loki
    mkdir -p docker/promtail
    mkdir -p docker/exporter
    mkdir -p docker/redis
    mkdir -p docs/docker
}

# Pull latest images
pull_images() {
    print_info "Pulling latest Docker images"
    docker-compose -f docker-compose-jotp-cluster.yml pull
    docker-compose -f docker-compose-jotp-monitoring.yml pull
    docker-compose -f docker-compose-jotp-services.yml pull
}

# Start the cluster
start_cluster() {
    print_info "Starting JOTP cluster..."
    docker-compose -f docker-compose-jotp-cluster.yml --env-file .env.jotp up -d
}

# Start monitoring
start_monitoring() {
    print_info "Starting monitoring stack..."
    docker-compose -f docker-compose-jotp-monitoring.yml up -d
}

# Start services
start_services() {
    print_info "Starting supporting services..."
    docker-compose -f docker-compose-jotp-services.yml up -d
}

# Wait for services to be healthy
wait_for_health() {
    print_info "Waiting for services to become healthy..."
    sleep 10

    local max_attempts=30
    local attempt=0

    while [ $attempt -lt $max_attempts ]; do
        local healthy=0

        # Check JOTP nodes
        if curl -sf http://localhost:8081/health/ready > /dev/null 2>&1; then
            ((healthy++))
        fi
        if curl -sf http://localhost:8082/health/ready > /dev/null 2>&1; then
            ((healthy++))
        fi
        if curl -sf http://localhost:8083/health/ready > /dev/null 2>&1; then
            ((healthy++))
        fi

        # Check monitoring
        if curl -sf http://localhost:9090/-/healthy > /dev/null 2>&1; then
            ((healthy++))
        fi
        if curl -sf http://localhost:3000/api/health > /dev/null 2>&1; then
            ((healthy++))
        fi

        if [ $healthy -ge 3 ]; then
            print_info "Services are healthy!"
            return 0
        fi

        ((attempt++))
        sleep 2
    done

    print_warn "Some services may not be fully healthy yet. Check logs with:"
    echo "  docker-compose -f docker-compose-jotp-cluster.yml logs -f"
}

# Display status
show_status() {
    print_info "Cluster Status:"
    echo ""
    echo "JOTP Nodes:"
    docker-compose -f docker-compose-jotp-cluster.yml ps | grep jotp-node
    echo ""
    echo "Monitoring Services:"
    docker-compose -f docker-compose-jotp-monitoring.yml ps | grep -E "prometheus|grafana|jaeger"
    echo ""
    echo "Access URLs:"
    echo "  Grafana:        http://localhost:3000 (admin/admin)"
    echo "  Prometheus:     http://localhost:9090"
    echo "  Jaeger:         http://localhost:16686"
    echo "  JOTP Node 1:    http://localhost:8081"
    echo "  JOTP Node 2:    http://localhost:8082"
    echo "  JOTP Node 3:    http://localhost:8083"
    echo "  Kafka UI:       http://localhost:8090"
    echo "  RabbitMQ:       http://localhost:15672"
    echo ""
}

# Main execution
main() {
    echo "=========================================="
    echo "JOTP Docker Compose Quick Start"
    echo "=========================================="
    echo ""

    check_docker
    check_docker_compose
    create_directories
    setup_environment

    # Ask if user wants to pull images
    read -p "Pull latest Docker images? (y/n): " -n 1 -r
    echo ""
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        pull_images
    fi

    # Start everything
    start_cluster
    start_monitoring
    start_services

    # Wait for health
    wait_for_health

    # Show status
    show_status

    print_info "JOTP cluster is now running!"
    echo ""
    echo "Useful commands:"
    echo "  View logs:     docker-compose -f docker-compose-jotp-cluster.yml logs -f"
    echo "  Stop cluster:  docker-compose -f docker-compose-jotp-cluster.yml down"
    echo "  Restart node:  docker-compose -f docker-compose-jotp-cluster.yml restart jotp-node-1"
    echo ""
    echo "For detailed setup instructions, see: docs/docker/COMPOSE-SETUP.md"
}

# Run main function
main
