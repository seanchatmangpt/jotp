# JOTP Docker Configuration

This directory contains all Docker-related configuration for running JOTP clusters locally and in production.

## Quick Start

```bash
# Run the quick start script
./start-jotp-cluster.sh

# Or manually:
cp .env.jotp.example .env.jotp
docker-compose -f docker-compose-jotp-cluster.yml --env-file .env.jotp up -d
docker-compose -f docker-compose-jotp-monitoring.yml up -d
docker-compose -f docker-compose-jotp-services.yml up -d
```

## Directory Structure

```
docker/
├── postgres/
│   ├── init/
│   │   └── 01-init-databases.sh       # Database initialization script
│   └── conf/                           # PostgreSQL configuration
├── prometheus/
│   ├── prometheus.yml                  # Scrape configuration
│   └── rules/
│       └── jotp-alerts.yml             # Alert rules
├── grafana/
│   ├── provisioning/
│   │   ├── datasources/
│   │   │   └── prometheus.yml          # Prometheus datasource
│   │   └── dashboards/
│   │       └── dashboard.yml           # Dashboard provisioning
│   └── dashboards/
│       ├── jotp-cluster-overview.json  # Cluster overview dashboard
│       └── jotp-node-details.json      # Node details dashboard
├── alertmanager/
│   └── alertmanager.yml                # Alert routing configuration
├── loki/
│   └── loki-config.yml                 # Log aggregation configuration
├── promtail/
│   └── promtail-config.yml             # Log collection configuration
├── redis/
│   └── redis.conf                      # Redis configuration
└── exporter/
    └── process-exporter.yml            # Process monitoring configuration
```

## Docker Compose Files

### docker-compose-jotp-cluster.yml
Main cluster configuration with 3 JOTP nodes:
- Peer-to-peer gRPC networking
- Leader election
- Health checks
- Resource limits

### docker-compose-jotp-monitoring.yml
Complete observability stack:
- Prometheus (metrics)
- Grafana (dashboards)
- Jaeger (tracing)
- Loki (logs)
- AlertManager (alerts)

### docker-compose-jotp-services.yml
Supporting services:
- PostgreSQL (state persistence)
- Redis (caching)
- NATS (messaging)
- Kafka (event streaming)
- RabbitMQ (message broker)
- MongoDB (document store)

## Configuration Files

### PostgreSQL
- **init/01-init-databases.sh**: Initializes databases for saga state, events, and distributed state
- Creates tables: `saga_state`, `saga_log`, `event_store`, `process_registry`, `distributed_lock`

### Prometheus
- **prometheus.yml**: Scrape configs for JOTP nodes, databases, and system metrics
- **rules/jotp-alerts.yml**: Alert rules for cluster health, performance, and failures

### Grafana
- **provisioning/datasources/prometheus.yml**: Prometheus datasource configuration
- **provisioning/dashboards/dashboard.yml**: Dashboard auto-provisioning
- **dashboards/**: Pre-built dashboards for cluster monitoring

### AlertManager
- **alertmanager.yml**: Alert routing to email, Slack, webhooks
- Inhibition rules to prevent alert spam

### Loki & Promtail
- **loki-config.yml**: Log aggregation configuration
- **promtail-config.yml**: Log collection from containers and system logs

### Redis
- **redis.conf**: Production Redis configuration with persistence and memory limits

### Exporters
- **process-exporter.yml**: Monitor JOTP, PostgreSQL, Redis, and other processes

## Environment Variables

See `/Users/sac/jotp/.env.jotp.example` for all available environment variables.

Key variables:
- `JOTP_CLUSTER_MODE`: Distributed cluster mode
- `JOTP_PEER_SEEDS`: Comma-separated list of peer addresses
- `JAVA_OPTS`: JVM configuration
- `POSTGRES_PASSWORD`: Database password
- `JOTP_METRICS_ENABLED`: Enable metrics collection

## Operations

### Start Services

```bash
# Start cluster only
docker-compose -f docker-compose-jotp-cluster.yml --env-file .env.jotp up -d

# Start monitoring
docker-compose -f docker-compose-jotp-monitoring.yml up -d

# Start services
docker-compose -f docker-compose-jotp-services.yml up -d

# Start all
docker-compose -f docker-compose-jotp-cluster.yml \
               -f docker-compose-jotp-monitoring.yml \
               -f docker-compose-jotp-services.yml \
               --env-file .env.jotp up -d
```

### View Logs

```bash
# All cluster logs
docker-compose -f docker-compose-jotp-cluster.yml logs -f

# Specific node
docker-compose -f docker-compose-jotp-cluster.yml logs -f jotp-node-1

# Monitoring logs
docker-compose -f docker-compose-jotp-monitoring.yml logs -f prometheus
```

### Stop Services

```bash
# Stop cluster
docker-compose -f docker-compose-jotp-cluster.yml down

# Stop all
docker-compose -f docker-compose-jotp-cluster.yml \
               -f docker-compose-jotp-monitoring.yml \
               -f docker-compose-jotp-services.yml down

# Stop and remove volumes
docker-compose -f docker-compose-jotp-cluster.yml down -v
```

### Scale Cluster

```bash
# Scale to 5 nodes
docker-compose -f docker-compose-jotp-cluster.yml up -d --scale jotp-node=5
```

## Health Checks

```bash
# Check container health
docker-compose -f docker-compose-jotp-cluster.yml ps

# Application health
curl http://localhost:8081/health/ready
curl http://localhost:8082/health/ready
curl http://localhost:8083/health/ready

# Metrics
curl http://localhost:9091/metrics
```

## Monitoring

### Access Dashboards

- **Grafana**: http://localhost:3000 (admin/admin)
- **Prometheus**: http://localhost:9090
- **Jaeger**: http://localhost:16686
- **Kafka UI**: http://localhost:8090
- **RabbitMQ**: http://localhost:15672

### Prometheus Queries

```promql
# Cluster health
count(up{job="jotp-cluster"} == 1)

# Message throughput
sum(rate(jotp_messages_processed_total[5m])) by (instance)

# Active processes
sum(jotp_process_registry_size) by (instance)

# Memory usage
jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"}
```

## Troubleshooting

### Container Won't Start

```bash
# Check logs
docker-compose -f docker-compose-jotp-cluster.yml logs jotp-node-1

# Verify configuration
docker-compose -f docker-compose-jotp-cluster.yml config

# Check resource limits
docker stats jotp-node-1
```

### Network Issues

```bash
# Inspect network
docker network inspect jotp-cluster-network

# Test connectivity
docker exec jotp-node-1 ping jotp-node-2

# Rebuild network
docker-compose -f docker-compose-jotp-cluster.yml down
docker network rm jotp-cluster-network
docker-compose -f docker-compose-jotp-cluster.yml up -d
```

### Database Issues

```bash
# Enter PostgreSQL
docker exec -it jotp-postgres psql -U jotp -d jotp_cluster

# Check connections
docker exec jotp-postgres psql -U jotp -c "SELECT * FROM pg_stat_activity;"

# Restart database
docker-compose -f docker-compose-jotp-services.yml restart postgres
```

## Development

### Local Development

```bash
# Mount source for development
docker-compose -f docker-compose-jotp-cluster.yml up -d --build

# Run with debug port
docker-compose -f docker-compose-jotp-cluster.yml up -d --scale jotp-node=1

# Connect debugger to localhost:5005
```

### Testing

```bash
# Run tests in container
docker-compose -f docker-compose-jotp-cluster.yml run --rm jotp-node-1 mvn test

# Load test
ab -n 10000 -c 100 http://localhost:8081/api/process
```

## Production Deployment

### Security

1. Change default passwords in `.env.jotp`
2. Enable TLS: Set `TLS_ENABLED=true`
3. Enable authentication: Set `AUTH_ENABLED=true`
4. Use secrets management for sensitive data

### Resource Planning

**Minimum (3 nodes):**
- CPU: 6 cores (2 per node)
- Memory: 6GB (2GB per node)
- Disk: 20GB

**Recommended:**
- CPU: 12 cores (4 per node)
- Memory: 12GB (4GB per node)
- Disk: 50GB SSD

### Backup

```bash
# Backup PostgreSQL
docker exec jotp-postgres pg_dump -U jotp jotp_cluster > backup.sql

# Backup volumes
docker run --rm -v jotp-state-1:/data -v $(pwd):/backup \
  ubuntu tar czf /backup/state-1-backup.tar.gz -C /data .
```

## Additional Resources

- **Setup Guide**: `/Users/sac/jotp/docs/docker/COMPOSE-SETUP.md`
- **Main Documentation**: `/Users/sac/jotp/docs/`
- **Examples**: `/Users/sac/jotp/src/main/java/io/github/seanchatmangpt/jotp/examples/`
- **Tests**: `/Users/sac/jotp/src/test/java/io/github/seanchatmangpt/jotp/`

## Support

For issues and questions:
- GitHub Issues: https://github.com/seanchatmangpt/jotp/issues
- Documentation: `/Users/sac/jotp/docs/`
