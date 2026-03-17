# JOTP Helm Chart

Production-ready Helm chart for deploying JOTP (Java 26 OTP framework) on Kubernetes.

## Features

- **High Availability**: PodDisruptionBudget, topology spread constraints, multi-zone support
- **Scalability**: Horizontal Pod Autoscaler with configurable policies
- **Monitoring**: Prometheus service monitor and JMX exporter integration
- **Security**: Network policies, security contexts, non-root containers
- **Distributed**: Built-in support for multi-node JOTP clusters
- **Flexibility**: Configurable for 100K, 1M, or 10M concurrent processes

## Prerequisites

- Kubernetes 1.25+
- Helm 3.0+
- Java 26 runtime environment
- (Optional) Prometheus Operator for monitoring
- (Optional) Cert Manager for TLS certificates

## Installation

### Default Installation

```bash
helm install jotp ./helm/jotp
```

### Development Environment

```bash
helm install jotp ./helm/jotp -f helm/jotp/values-dev.yaml
```

### Staging Environment

```bash
helm install jotp ./helm/jotp -f helm/jotp/values-staging.yaml
```

### Production Environment

```bash
helm install jotp ./helm/jotp -f helm/jotp/values-prod.yaml
```

### Custom Configuration

Create a custom values file and install:

```bash
cat > my-values.yaml <<EOF
replicaCount: 3
jvm:
  xmx: "8G"
  xms: "8G"
jotp:
  maxProcesses: 1000000
EOF

helm install jotp ./helm/jotp -f my-values.yaml
```

## Configuration

### Key Parameters

| Parameter | Description | Default |
|-----------|-------------|---------|
| `replicaCount` | Number of replicas | `3` |
| `image.repository` | Container image repository | `ghcr.io/seanchatmangpt/jotp` |
| `image.tag` | Container image tag | `1.0.0` |
| `jvm.xmx` | Maximum heap size | `8G` |
| `jvm.xms` | Initial heap size | `8G` |
| `jvm.gc` | Garbage collector | `ZGC` |
| `jotp.maxProcesses` | Maximum concurrent processes | `1000000` |
| `jotp.distributed` | Enable distributed mode | `true` |
| `jotp.cookie` | Cluster authentication cookie | `jotp-cluster-cookie` |

### Process Count Configurations

The chart includes optimized configurations for different scales:

#### 100K Processes

```bash
helm install jotp ./helm/jotp \
  --set jvm.xmx=2G \
  --set jvm.xms=2G \
  --set jotp.maxProcesses=100000
```

#### 1M Processes (Use provided values file)

```bash
helm install jotp ./helm/jotp -f helm/jotp/values-1m-processes.yaml
```

#### 10M Processes (Use provided values file)

```bash
helm install jotp ./helm/jotp -f helm/jotp/values-10m-processes.yaml
```

## Upgrading

```bash
helm upgrade jotp ./helm/jotp -f helm/jotp/values-prod.yaml
```

## Uninstalling

```bash
helm uninstall jotp
```

## Monitoring

### Prometheus Metrics

Enable Prometheus monitoring:

```yaml
monitoring:
  enabled: true
  serviceMonitor:
    enabled: true
```

Access metrics:

```bash
kubectl port-forward -n default svc/jotp-jmx 9091:9091
curl http://localhost:9091/metrics
```

### Health Checks

Liveness and readiness probes are configured by default:

- **Liveness**: `/health/live` - Checks if the pod is alive
- **Readiness**: `/health/ready` - Checks if the pod can serve traffic
- **Startup**: `/health/startup` - Checks if the application has started

## Distributed Setup

For multi-node JOTP clusters, the chart automatically configures peer discovery via Kubernetes services:

1. **Headless Service**: Created for pod-to-pod communication
2. **Peer Discovery**: JOTP nodes discover each other via the headless service
3. **Cluster Cookie**: Shared secret for inter-node authentication

```yaml
jotp:
  distributed: true
  peerDiscovery:
    enabled: true
    serviceName: "jotp-headless"
    port: 9100
```

## High Availability

### Pod Disruption Budget

Ensures minimum availability during voluntary disruptions:

```yaml
podDisruptionBudget:
  enabled: true
  minAvailable: 2
```

### Topology Spread

Spreads pods across zones and nodes:

```yaml
topologySpreadConstraints:
  - maxSkew: 1
    topologyKey: topology.kubernetes.io/zone
```

### Rolling Updates

Zero-downtime rolling updates:

```yaml
rollingUpdate:
  maxUnavailable: 0
  maxSurge: 1
```

## Security

### Network Policies

Restricts pod-to-pod communication:

```yaml
networkPolicy:
  enabled: true
```

### Security Contexts

Runs as non-root user with read-only filesystem:

```yaml
podSecurityContext:
  runAsNonRoot: true
  runAsUser: 1000

securityContext:
  readOnlyRootFilesystem: true
```

## Backup and Restore

For production deployments with stateful sets:

```yaml
statefulSet:
  enabled: true
  persistence:
    enabled: true
    storageClass: "standard"
    size: "50Gi"
```

## Troubleshooting

### View Pod Logs

```bash
kubectl logs -l app.kubernetes.io/name=jotp --tail=100 -f
```

### Check Pod Status

```bash
kubectl get pods -l app.kubernetes.io/name=jotp
```

### Describe Pod

```bash
kubectl describe pod -l app.kubernetes.io/name=jotp
```

### Port Forward to Service

```bash
kubectl port-forward svc/jotp 8080:8080
```

## Values Reference

See `values.yaml` for all configurable parameters. Key sections:

- **Image Configuration**: Container image settings
- **JVM Configuration**: Heap size, GC settings, JVM options
- **JOTP Configuration**: Process limits, supervision, mailbox settings
- **Resources**: CPU and memory limits/requests
- **Autoscaling**: HPA configuration
- **Monitoring**: Prometheus and JMX exporter settings
- **Ingress**: External access configuration
- **Security**: Network policies, security contexts
- **High Availability**: PDB, topology spread, affinity rules

## Production Checklist

- [ ] Update `jotp.cookie` to a secure random value
- [ ] Configure proper resource limits based on load testing
- [ ] Enable network policies
- [ ] Configure TLS certificates
- [ ] Set up monitoring and alerting
- [ ] Configure backup strategy
- [ ] Review and adjust autoscaling parameters
- [ ] Enable PodDisruptionBudget
- [ ] Configure proper logging and aggregation
- [ ] Set up disaster recovery procedures

## Support

For issues and questions:
- GitHub: https://github.com/seanchatmangpt/jotp
- Documentation: https://jotp.io/docs
- Community: https://github.com/seanchatmangpt/jotp/discussions

## License

MIT License - see LICENSE file for details
