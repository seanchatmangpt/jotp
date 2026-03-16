# JOTP Helm Chart - Installation Guide

This guide provides step-by-step instructions for deploying JOTP on Kubernetes using Helm.

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Quick Start](#quick-start)
3. [Environment-Specific Installation](#environment-specific-installation)
4. [Configuration](#configuration)
5. [Verification](#verification)
6. [Troubleshooting](#troubleshooting)
7. [Upgrading](#upgrading)
8. [Uninstallation](#uninstallation)

## Prerequisites

### Required Tools

- **kubectl**: 1.25+
  ```bash
  kubectl version --client
  ```

- **helm**: 3.0+
  ```bash
  helm version
  ```

### Kubernetes Cluster Requirements

- Kubernetes version: 1.25+
- Minimum resources for development:
  - 1 node with 2 CPU cores, 4GB RAM
- Minimum resources for production:
  - 3+ nodes with 8+ CPU cores, 32GB+ RAM each
- (Optional) Persistent storage for StatefulSet deployments

### Optional Components

- **Prometheus Operator**: For advanced monitoring
  ```bash
  kubectl get deployment prometheus-operator
  ```

- **Cert Manager**: For automatic TLS certificate management
  ```bash
  kubectl get deployment cert-manager
  ```

- **Ingress Controller**: For external access (nginx, traefik, etc.)
  ```bash
  kubectl get deployment -n ingress-nginx
  ```

## Quick Start

### 1. Add the Helm Repository (if published)

```bash
helm repo add jotp https://charts.jotp.io
helm repo update
```

### 2. Create Namespace

```bash
kubectl create namespace jotp
```

### 3. Install JOTP

```bash
# Development environment
helm install jotp -n jotp ./helm/jotp -f helm/jotp/values-dev.yaml

# Production environment
helm install jotp -n jotp ./helm/jotp -f helm/jotp/values-prod.yaml
```

### 4. Verify Installation

```bash
# Check pods
kubectl get pods -n jotp

# Check services
kubectl get svc -n jotp

# Check deployment status
helm status jotp -n jotp
```

## Environment-Specific Installation

### Development Environment

Lightweight configuration for local development:

```bash
helm install jotp-dev -n jotp-dev ./helm/jotp \
  -f helm/jotp/values-dev.yaml \
  --create-namespace
```

**Characteristics:**
- 1 replica
- 2GB heap
- 100K process capacity
- Network policies disabled
- No autoscaling

**Access the application:**
```bash
kubectl port-forward -n jotp-dev svc/jotp-dev 8080:8080
curl http://localhost:8080/health/ready
```

### Staging Environment

Pre-production testing environment:

```bash
helm install jotp-staging -n jotp-staging ./helm/jotp \
  -f helm/jotp/values-staging.yaml \
  --create-namespace
```

**Characteristics:**
- 2 replicas
- 4GB heap
- 500K process capacity
- Network policies enabled
- Horizontal Pod Autoscaler enabled
- Basic ingress configuration

### Production Environment

Highly available production deployment:

```bash
# Create production namespace
kubectl create namespace jotp-prod

# Create secret for cluster cookie
kubectl create secret generic jotp-prod-cookie \
  --from-literal=cookie=$(openssl rand -base64 32) \
  -n jotp-prod

# Install with production values
helm install jotp-prod -n jotp-prod ./helm/jotp \
  -f helm/jotp/values-prod.yaml \
  --set jotp.cookie=$(kubectl get secret jotp-prod-cookie -n jotp-prod -o jsonpath='{.data.cookie}' | base64 -d)
```

**Characteristics:**
- 3+ replicas
- 16GB heap
- 10M process capacity
- Full monitoring stack
- PodDisruptionBudget
- Multi-zone spread
- Network policies
- Ingress with TLS

## Configuration

### Generate Secure Cookie

```bash
# Generate secure cluster cookie
COOKIE=$(openssl rand -base64 32)
echo "Generated cookie: $COOKIE"

# Use in installation
helm install jotp ./helm/jotp \
  --set jotp.cookie="$COOKIE"
```

### Custom Process Capacity

```bash
# 100K processes
helm install jotp ./helm/jotp \
  --set jvm.xmx=2G \
  --set jvm.xms=2G \
  --set jotp.maxProcesses=100000

# 1M processes (use provided values)
helm install jotp ./helm/jotp -f helm/jotp/values-1m-processes.yaml

# 10M processes (use provided values)
helm install jotp ./helm/jotp -f helm/jotp/values-10m-processes.yaml
```

### Enable Monitoring

```bash
helm install jotp ./helm/jotp \
  --set monitoring.enabled=true \
  --set monitoring.serviceMonitor.enabled=true
```

### Configure Ingress

```bash
helm install jotp ./helm/jotp \
  --set ingress.enabled=true \
  --set ingress.hosts[0].host=jotp.example.com \
  --set ingress.hosts[0].paths[0].path=/ \
  --set ingress.hosts[0].paths[0].pathType=Prefix \
  --set ingress.tls[0].secretName=jotp-tls \
  --set ingress.tls[0].hosts[0]=jotp.example.com
```

### StatefulSet Deployment

For stateful applications with persistent storage:

```bash
helm install jotp ./helm/jotp \
  --set statefulSet.enabled=true \
  --set statefulSet.persistence.enabled=true \
  --set statefulSet.persistence.storageClass=standard \
  --set statefulSet.persistence.size=50Gi
```

## Verification

### Check Pod Status

```bash
# List all pods
kubectl get pods -n jotp -l app.kubernetes.io/name=jotp

# Get detailed pod information
kubectl describe pod -n jotp <pod-name>

# View pod logs
kubectl logs -n jotp <pod-name> -f
```

### Check Services

```bash
# List all services
kubectl get svc -n jotp

# Get service details
kubectl describe svc jotp -n jotp
```

### Test Health Endpoints

```bash
# Port forward to local
kubectl port-forward -n jotp svc/jotp 8080:8080

# Test health endpoints
curl http://localhost:8080/health/live    # Liveness
curl http://localhost:8080/health/ready   # Readiness
curl http://localhost:8080/actuator/info # Application info
```

### Test Metrics Endpoint

```bash
# Prometheus metrics
kubectl port-forward -n jotp svc/jotp-jmx 9091:9091
curl http://localhost:9091/metrics
```

### View Cluster Peers

```bash
# Check JOTP cluster peers
kubectl exec -n jotp <pod-name> -- \
  curl http://localhost:8080/actuator/jotp/peers
```

## Troubleshooting

### Pods Not Starting

```bash
# Check pod events
kubectl describe pod -n jotp <pod-name>

# Common issues:
# 1. Image pull errors → Check image repository and tag
# 2. Resource constraints → Check resource requests/limits
# 3. Security context → Check podSecurityContext settings
```

### Connection Issues

```bash
# Check network policies
kubectl get networkpolicy -n jotp

# Test pod-to-pod communication
kubectl exec -n jotp <pod-1> -- ping <pod-2-ip>

# Check service DNS resolution
kubectl exec -n jotp <pod-name> -- nslookup jotp-headless
```

### Performance Issues

```bash
# Check resource usage
kubectl top pods -n jotp
kubectl top nodes

# View JVM metrics
kubectl exec -n jotp <pod-name> -- \
  curl http://localhost:9091/metrics | grep jvm_memory

# Check GC activity
kubectl logs -n jotp <pod-name> | grep GC
```

### Heap Dump Analysis

```bash
# Trigger heap dump (if configured)
kubectl exec -n jotp <pod-name> -- \
  jcmd 1 GC.heap_dump /logs/heapdump.hprof

# Copy heap dump locally
kubectl cp -n jotp <pod-name>:/logs/heapdump.hprof ./heapdump.hprof
```

## Upgrading

### Standard Upgrade

```bash
helm upgrade jotp -n jotp ./helm/jotp \
  -f helm/jotp/values-prod.yaml
```

### Upgrade with Specific Version

```bash
helm upgrade jotp -n jotp ./helm/jotp \
  --set image.tag=1.1.0
```

### Rollback

```bash
# List revisions
helm history jotp -n jotp

# Rollback to previous version
helm rollback jotp -n jotp

# Rollback to specific revision
helm rollback jotp -n jotp 2
```

### Zero-Downtime Upgrade

The chart is configured for zero-downtime rolling updates by default:

```yaml
rollingUpdate:
  maxUnavailable: 0  # No pods unavailable during update
  maxSurge: 1        # One extra pod during update
```

Monitor the upgrade:

```bash
kubectl rollout status deployment/jotp -n jotp
```

## Uninstallation

### Remove Release

```bash
helm uninstall jotp -n jotp
```

### Remove Persistent Data (StatefulSet)

```bash
# Delete StatefulSet (pods are terminated sequentially)
kubectl delete statefulset jotp -n jotp

# Delete persistent volume claims
kubectl delete pvc -l app.kubernetes.io/name=jotp -n jotp
```

### Remove Namespace

```bash
kubectl delete namespace jotp
```

### Clean Up Secrets

```bash
kubectl delete secret jotp-cookie -n jotp
kubectl delete secret jotp-prod-cookie -n jotp-prod
```

## Advanced Topics

### Custom Resource Profiles

Create a custom values file for specific workloads:

```yaml
# values-high-throughput.yaml
replicaCount: 5
jvm:
  xmx: "16G"
  xms: "16G"
  extraOpts: "-XX:+UseZGC -XX:ConcGCThreads=4"
jotp:
  maxProcesses: 5000000
  mailbox:
    capacity: 20000
resources:
  requests:
    cpu: "8"
    memory: "24Gi"
  limits:
    cpu: "12"
    memory: "32Gi"
```

### Multi-Region Deployment

Deploy across multiple Kubernetes clusters:

```bash
# Region 1
helm install jotp-us-east -n jotp ./helm/jotp \
  -f values-prod.yaml \
  --set jotp.nodeName=jotp-us-east \
  --set jotp.peerDiscovery.serviceName=jotp-us-east-headless

# Region 2
helm install jotp-us-west -n jotp ./helm/jotp \
  -f values-prod.yaml \
  --set jotp.nodeName=jotp-us-west \
  --set jotp.peerDiscovery.serviceName=jotp-us-west-headless
```

### Disaster Recovery

Backup and restore procedures:

```bash
# Backup: Export configuration
helm get values jotp -n jotp > jotp-backup.yaml

# Backup: Export secrets
kubectl get secret jotp-cookie -n jotp -o yaml > jotp-secret-backup.yaml

# Restore: Reinstall from backup
helm install jotp-restored -n jotp ./helm/jotp \
  -f jotp-backup.yaml \
  --set jotp.cookie=<backup-cookie>
```

## Support

For issues and questions:
- GitHub Issues: https://github.com/seanchatmangpt/jotp/issues
- Documentation: https://jotp.io/docs
- Slack: #jotp-community
