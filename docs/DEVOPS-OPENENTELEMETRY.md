# DevOps: OpenTelemetry Integration

This guide describes the OpenTelemetry integration for JOTP observability.

## Quick Start

```bash
# Start observability stack
make otel-start

# Run with tracing
make otel-run

# View traces
make otel-traces

# View metrics
make otel-metrics

# Stop observability stack
make otel-stop
```

## Services

| Service | URL | Credentials |
|---------|-----|-------------|
| Jaeger UI | http://localhost:16686 | None |
| Prometheus | http://localhost:9090 | None |
| Grafana | http://localhost:3000 | admin/admin |

## Configuration

Edit `otel/config/collector-config.yaml` to customize:
- OTLP receivers (gRPC/HTTP)
- Processors (batch, memory limiter)
- Exporters (Jaeger, Prometheus)

## Makefile Targets

```bash
make otel-setup    # Setup directories
make otel-start    # Start stack
make otel-stop     # Stop stack
make otel-validate # Check connectivity
make otel-status   # Show status
make otel-logs     # View logs
make otel-clean    # Remove all
```

## GitHub Actions

The `.github/workflows/observability.yml` workflow validates the integration on every PR.

## Architecture

```
JOTP → OpenTelemetry Service → OTLP Collector → Jaeger/Prometheus/Grafana
```
