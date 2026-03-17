# JOTP Multi-Node Docker Compose Setup Guide

Complete guide for running JOTP clusters locally using Docker Compose with observability, monitoring, and supporting services.

## Overview

This setup provides a production-like multi-node JOTP cluster environment with:

- **3-node JOTP cluster** with peer-to-peer networking
- **Distributed process coordination** and leader election
- **Complete observability stack** (Prometheus, Grafana, Jaeger, Loki)
- **Supporting services** (PostgreSQL, Redis, NATS, Kafka)
- **Automatic peer discovery** and health monitoring
- **Resource limits** and health checks

## Quick Start

### 1. Clone and Setup

```bash
cd /path/to/jotp

# Copy environment template
cp .env.jotp.example .env.jotp

# Edit environment variables (optional)
vim .env.jotp
```

### 2. Start the Cluster

```bash
# Start JOTP cluster (3 nodes)
docker-compose -f docker-compose-jotp-cluster.yml --env-file .env.jotp up -d

# Start monitoring stack
docker-compose -f docker-compose-jotp-monitoring.yml up -d

# Start supporting services
docker-compose -f docker-compose-jotp-services.yml up -d

# Or start all at once
docker-compose -f docker-compose-jotp-cluster.yml \
               -f docker-compose-jotp-monitoring.yml \
               -f docker-compose-jotp-services.yml \
               --env-file .env.jotp up -d
```

### 3. Verify Cluster Status

```bash
# Check all containers
docker-compose -f docker-compose-jotp-cluster.yml ps

# View logs
docker-compose -f docker-compose-jotp-cluster.yml logs -f

# Check cluster health
curl http://localhost:8081/health/ready
curl http://localhost:8082/health/ready
curl http://localhost:8083/health/ready
```

### 4. Access Dashboards

- **Grafana**: http://localhost:3000 (admin/admin)
- **Prometheus**: http://localhost:9090
- **Jaeger**: http://localhost:16686
- **Kafka UI**: http://localhost:8090
- **RabbitMQ Management**: http://localhost:15672

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         JOTP Cluster                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐         │
│  │  jotp-node-1 │  │  jotp-node-2 │  │  jotp-node-3 │         │
│  │  :8081       │  │  :8082       │  │  :8083       │         │
│  │  :50051      │◄─┤  :50052      │◄─┤  :50053      │         │
│  │  (gRPC)      │  │  (gRPC)      │  │  (gRPC)      │         │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘         │
│         │                 │                 │                   │
│         └─────────────────┴─────────────────┘                   │
│                    (gRPC Mesh)                                  │
└─────────────────────────────────────────────────────────────────┘
         │                 │                 │
         │                 │                 │
┌────────▼────────┐ ┌──────▼──────┐ ┌───────▼────────┐
│  PostgreSQL     │ │  Redis      │ │  NATS/Kafka    │
│  (Saga State)   │ │  (Cache)    │ │  (Messaging)   │
│  :5432          │ │  :6379      │ │  :4222/:9092   │
└─────────────────┘ └─────────────┘ └────────────────┘
         │
┌────────▼──────────────────────────────────────────────────────┐
│                    Observability Stack                         │
├────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐       │
│  │ Prometheus   │  │  Grafana     │  │  Jaeger      │       │
│  │ :9090        │  │  :3000       │  │  :16686      │       │
│  └──────────────┘  └──────────────┘  └──────────────┘       │
│                                                                 │
│  ┌──────────────┐  ┌──────────────┐                          │
│  │  Loki        │  │ AlertManager │                          │
│  │  :3100       │  │  :9093       │                          │
│  └──────────────┘  └──────────────┘                          │
└─────────────────────────────────────────────────────────────────┘
```

## File Structure

```
jotp/
├── docker-compose-jotp-cluster.yml      # Main cluster definition
├── docker-compose-jotp-monitoring.yml   # Observability stack
├── docker-compose-jotp-services.yml     # Supporting services
├── .env.jotp.example                    # Environment template
└── docker/
    ├── postgres/
    │   ├── init/
    │   │   └── 01-init-databases.sh   # Database initialization
    │   └── conf/
    ├── prometheus/
    │   ├── prometheus.yml              # Scrape configuration
    │   └── rules/
    │       └── jotp-alerts.yml         # Alert rules
    ├── grafana/
    │   ├── provisioning/
    │   │   ├── datasources/
    │   │   └── dashboards/
    │   └── dashboards/
    │       └── jotp-cluster-overview.json
    ├── alertmanager/
    │   └── alertmanager.yml           # Alert routing
    ├── loki/
    │   └── loki-config.yml            # Log aggregation
    ├── promtail/
    │   └── promtail-config.yml        # Log collection
    ├── redis/
    │   └── redis.conf                # Redis configuration
    └── exporter/
        └── process-exporter.yml      # Process monitoring
```

## Configuration

### Environment Variables

Key environment variables in `.env.jotp`:

```bash
# Cluster Configuration
JOTP_CLUSTER_MODE=distributed
JOTP_LEADER_ELECTION_ENABLED=true
JOTP_PEER_SEEDS=jotp-node-2:50051,jotp-node-3:50051

# JVM Settings
JAVA_OPTS=-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0

# Database
POSTGRES_DB=jotp_cluster
POSTGRES_USER=jotp
POSTGRES_PASSWORD=jotp_cluster_secret

# Observability
JOTP_METRICS_ENABLED=true
JOTP_TRACING_ENABLED=true
OTEL_EXPORTER_OTLP_ENDPOINT=http://jaeger:4317
```

### Resource Limits

Default resource limits per node:

```yaml
deploy:
  resources:
    limits:
      cpus: '2'
      memory: 2G
    reservations:
      cpus: '0.5'
      memory: 512M
```

## Operations

### Scaling the Cluster

```bash
# Scale to 5 nodes
docker-compose -f docker-compose-jotp-cluster.yml up -d --scale jotp-node=5

# Scale down to 3 nodes
docker-compose -f docker-compose-jotp-cluster.yml up -d --scale jotp-node=3
```

### Viewing Logs

```bash
# All cluster nodes
docker-compose -f docker-compose-jotp-cluster.yml logs -f jotp-node-1

# Specific service
docker-compose -f docker-compose-jotp-monitoring.yml logs -f prometheus

# All services
docker-compose -f docker-compose-jotp-cluster.yml logs -f
```

### Restarting Services

```bash
# Restart single node
docker-compose -f docker-compose-jotp-cluster.yml restart jotp-node-1

# Restart entire cluster
docker-compose -f docker-compose-jotp-cluster.yml restart

# Restart monitoring
docker-compose -f docker-compose-jotp-monitoring.yml restart
```

### Stopping and Cleaning

```bash
# Stop all services
docker-compose -f docker-compose-jotp-cluster.yml \
               -f docker-compose-jotp-monitoring.yml \
               -f docker-compose-jotp-services.yml down

# Stop and remove volumes
docker-compose -f docker-compose-jotp-cluster.yml \
               -f docker-compose-jotp-monitoring.yml \
               -f docker-compose-jotp-services.yml down -v

# Remove all containers, networks, and volumes
docker-compose -f docker-compose-jotp-cluster.yml \
               -f docker-compose-jotp-monitoring.yml \
               -f docker-compose-jotp-services.yml down -v --remove-orphans
```

## Health Checks

### Check Container Health

```bash
# Docker health status
docker-compose -f docker-compose-jotp-cluster.yml ps

# Detailed health check
docker inspect jotp-node-1 | jq '.[0].State.Health'
```

### Application Health

```bash
# Readiness probe
curl http://localhost:8081/health/ready
curl http://localhost:8082/health/ready
curl http://localhost:8083/health/ready

# Liveness probe
curl http://localhost:8081/health/live

# Metrics endpoint
curl http://localhost:9091/metrics
curl http://localhost:9092/metrics
curl http://localhost:9093/metrics
```

### Cluster Status

```bash
# Check peer connections
curl http://localhost:8081/cluster/peers

# Check leader election
curl http://localhost:8081/cluster/leader

# Process registry
curl http://localhost:8081/cluster/processes
```

## Monitoring

### Prometheus Queries

**Cluster Health:**
```promql
# Active nodes
count(up{job="jotp-cluster"} == 1)

# Message throughput
sum(rate(jotp_messages_processed_total[5m])) by (instance)

# Active processes
sum(jotp_process_registry_size) by (instance)
```

**Performance:**
```promql
# Heap memory usage
jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"}

# GC pause rate
rate(jvm_gc_pause_seconds_sum[5m])

# Message latency
histogram_quantile(0.99, rate(jotp_message_processing_duration_seconds_bucket[5m]))
```

**Alerts:**
```promql
# Node down
up{job="jotp-cluster"} == 0

# High memory usage
jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"} > 0.9

# Message queue backlog
jotp_mailbox_size / jotp_mailbox_capacity > 0.8
```

### Grafana Dashboards

Available dashboards:
- **JOTP Cluster Overview**: Cluster-wide metrics and health
- **JOTP Node Details**: Per-node performance metrics
- **JOTP Process Analysis**: Process registry and supervision trees
- **JOTP Messaging**: Message throughput and latency
- **JOTP Resource Usage**: CPU, memory, and network metrics

### Distributed Tracing

Access Jaeger UI at http://localhost:16686:

```bash
# View traces for specific service
# Filter by service: jotp-node-1, jotp-node-2, jotp-node-3

# Search by operation
# Operations: process_message, supervisor_restart, leader_election
```

## Troubleshooting

### Common Issues

**Node fails to start:**
```bash
# Check logs
docker-compose -f docker-compose-jotp-cluster.yml logs jotp-node-1

# Verify configuration
docker-compose -f docker-compose-jotp-cluster.yml config

# Check resource limits
docker stats jotp-node-1
```

**Peer discovery fails:**
```bash
# Verify network connectivity
docker network inspect jotp-cluster-network

# Check DNS resolution
docker exec jotp-node-1 nslookup jotp-node-2

# Test gRPC connectivity
docker exec jotp-node-1 nc -zv jotp-node-2 50051
```

**Health checks failing:**
```bash
# Check endpoint availability
docker exec jotp-node-1 curl http://localhost:8080/health/ready

# Verify environment variables
docker exec jotp-node-1 env | grep JOTP

# Check JVM settings
docker exec jotp-node-1 jinfo 1
```

**Memory issues:**
```bash
# Check heap usage
docker stats jotp-node-1 --no-stream

# Adjust memory limits
# Edit docker-compose-jotp-cluster.yml
# deploy.resources.limits.memory: 4G

# Tune GC settings
# Edit .env.jotp
# JAVA_OPTS: -XX:+UseZGC -XX:MaxRAMPercentage=75.0
```

### Debug Mode

Enable debug logging:

```bash
# Edit .env.jotp
LOG_LEVEL=DEBUG
DEBUG_ENABLED=true

# Restart nodes
docker-compose -f docker-compose-jotp-cluster.yml restart
```

### Network Issues

```bash
# Inspect network
docker network inspect jotp-cluster-network

# Rebuild network
docker-compose -f docker-compose-jotp-cluster.yml down
docker network rm jotp-cluster-network
docker-compose -f docker-compose-jotp-cluster.yml up -d
```

## Advanced Usage

### Custom Configuration

```bash
# Create custom compose override
cat > docker-compose.override.yml <<EOF
version: '3.8'
services:
  jotp-node-1:
    environment:
      - JOTP_CUSTOM_SETTING=value
    volumes:
      - ./custom-config:/app/config:ro
EOF

# Start with override
docker-compose -f docker-compose-jotp-cluster.yml \
               -f docker-compose.override.yml up -d
```

### Backup and Restore

```bash
# Backup PostgreSQL
docker exec jotp-postgres pg_dump -U jotp jotp_cluster > backup.sql

# Restore PostgreSQL
docker exec -i jotp-postgres psql -U jotp jotp_cluster < backup.sql

# Backup volumes
docker run --rm -v jotp-state-1:/data -v $(pwd):/backup \
  ubuntu tar czf /backup/state-1-backup.tar.gz -C /data .
```

### Performance Testing

```bash
# Load test with Apache Bench
ab -n 10000 -c 100 http://localhost:8081/api/process

# Monitor during test
# Watch Grafana dashboard: http://localhost:3000
```

## Security

### Enable Authentication

```bash
# Edit .env.jotp
AUTH_ENABLED=true
AUTH_PROVIDER=jwt
AUTH_JWT_SECRET=your-secret-key
AUTH_JWT_EXPIRATION=3600
```

### Enable TLS

```bash
# Edit .env.jotp
TLS_ENABLED=true
TLS_KEY_STORE_PATH=/app/keystore.jks
TLS_KEY_STORE_PASSWORD=changeit
```

### Network Isolation

```bash
# Create isolated network
docker network create --driver bridge --internal jotp-isolated

# Update docker-compose files to use isolated network
# networks:
#   jotp-cluster-network:
#     external: true
#     name: jotp-isolated
```

## Production Deployment

### Resource Planning

**Minimum Requirements (3-node cluster):**
- CPU: 6 cores (2 per node)
- Memory: 6GB (2GB per node)
- Disk: 20GB (for state and logs)

**Recommended Requirements:**
- CPU: 12 cores (4 per node)
- Memory: 12GB (4GB per node)
- Disk: 50GB SSD

### High Availability

```bash
# Enable PostgreSQL replication
# Edit docker-compose-jotp-services.yml
# Add postgres-replica service

# Enable Redis Sentinel
# Already included in docker-compose-jotp-services.yml

# Configure multiple Grafana instances
# Use external Prometheus with HA configuration
```

### Monitoring Setup

```bash
# Configure AlertManager notifications
# Edit docker/alertmanager/alertmanager.yml
# Add Slack, PagerDuty, or email endpoints

# Set up Grafana notifications
# Configure alert channels in Grafana UI
# Alert on: node down, high memory, process crash rate
```

## Maintenance

### Log Rotation

```bash
# Configure log rotation in .env.jotp
LOG_MAX_SIZE=100MB
LOG_MAX_FILES=10
LOG_RETENTION_DAYS=30
```

### Database Maintenance

```bash
# PostgreSQL vacuum
docker exec jotp-postgres psql -U jotp -d jotp_cluster -c "VACUUM ANALYZE;"

# Redis cleanup
docker exec jotp-redis redis-cli CONFIG SET maxmemory-policy allkeys-lru
```

### Updates

```bash
# Pull latest images
docker-compose -f docker-compose-jotp-cluster.yml pull

# Rebuild JOTP image
docker build -t jotp:latest .

# Rolling update
docker-compose -f docker-compose-jotp-cluster.yml up -d --no-deps jotp-node-1
docker-compose -f docker-compose-jotp-cluster.yml up -d --no-deps jotp-node-2
docker-compose -f docker-compose-jotp-cluster.yml up -d --no-deps jotp-node-3
```

## Support and Resources

- **Documentation**: `/Users/sac/jotp/docs/`
- **Examples**: `/Users/sac/jotp/src/main/java/io/github/seanchatmangpt/jotp/examples/`
- **Tests**: `/Users/sac/jotp/src/test/java/io/github/seanchatmangpt/jotp/`
- **GitHub Issues**: https://github.com/seanchatmangpt/jotp/issues

## License

Apache License 2.0 - See LICENSE file for details
