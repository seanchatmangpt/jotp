# Kubernetes Deployment Guide for JOTP

This guide covers deploying distributed JOTP applications on Kubernetes using the provided manifests.

## Overview

The Kubernetes manifests deploy a **3-node JOTP cluster** with:
- **StatefulSet** for stable network identities and persistent storage
- **Headless Service** for peer-to-peer communication via gRPC
- **ClusterIP Service** for client access and load balancing
- **ConfigMap** for JVM optimization (1M+ processes with 4GB heap)
- **RBAC** for leader election and peer discovery
- **Health probes** for liveness, readiness, and startup checks

## Prerequisites

1. **Kubernetes cluster** (v1.25+ recommended)
2. **kubectl** configured to talk to your cluster
3. **StorageClass** named `fast-ssd` (or update the StatefulSet)
4. **JOTP Docker image** built and available:
   ```bash
   docker build -t jotp:1.0 .
   # Or use your registry:
   # docker push your-registry/jotp:1.0
   ```

## Quick Start

### 1. Deploy the manifests

```bash
# Deploy all resources
kubectl apply -f k8s/

# Verify deployment
kubectl get statefulset jotp
kubectl get pods -l app=jotp
kubectl get svc -l app=jotp
```

Expected output:
```
NAME    READY   AGE
jotp    3/3     1m

NAME          READY   STATUS    RESTARTS   AGE
jotp-0        2/2     Running   0          1m
jotp-1        2/2     Running   0          1m
jotp-2        2/2     Running   0          1m

NAME              TYPE        CLUSTER-IP      PORT(S)
jotp              ClusterIP   10.96.123.45    8080/TCP,50051/TCP
jotp-headless     ClusterIP   None            50051/TCP
jotp-monitoring   ClusterIP   10.96.234.56    9090/TCP
```

### 2. Verify the cluster is healthy

```bash
# Check peer discovery
kubectl exec jotp-0 -- curl -s http://localhost:9091/actuator/health | jq .

# View logs
kubectl logs -f statefulset/jotp

# Check metrics
kubectl port-forward svc/jotp-monitoring 9090:9090
curl http://localhost:9090/metrics
```

## Architecture

### Pod Architecture

```
┌─────────────────────────────────────────────────────────┐
│ StatefulSet: jotp (3 replicas)                          │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  Pod: jotp-0 (Pod IP: 10.244.1.10)                      │
│  ┌──────────────────────────────────────────────────┐  │
│  │ Init Container: wait-for-peers                   │  │
│  │ - Waits for DNS records of peer pods            │  │
│  └──────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────┐  │
│  │ Container: jotp                                  │  │
│  │ - Ports: 50051 (gRPC), 8080 (HTTP), 9090 (Prom) │  │
│  │ - Volume: /app/data (PVC)                        │  │
│  │ - Volume: /app/logs (emptyDir)                   │  │
│  │ - Probes: Liveness, Readiness, Startup          │  │
│  └──────────────────────────────────────────────────┘  │
│                                                         │
│  Peer Discovery:                                        │
│  - jotp-0.jotp-headless.default.svc.cluster.local       │
│  - jotp-1.jotp-headless.default.svc.cluster.local       │
│  - jotp-2.jotp-headless.default.svc.cluster.local       │
└─────────────────────────────────────────────────────────┘
```

### Service Mesh

```
                    ┌─────────────────┐
                    │ Client Traffic  │
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │ Service: jotp   │ (ClusterIP)
                    │   Port: 8080    │
                    └────────┬────────┘
                             │
            ┌────────────────┼────────────────┐
            │                │                │
      ┌─────▼─────┐    ┌────▼────┐    ┌─────▼─────┐
      │  jotp-0   │    │ jotp-1  │    │  jotp-2   │
      │ 10.244.1.10│   │10.244.2.10│   │10.244.3.10│
      └───────────┘    └─────────┘    └───────────┘
            │                │                │
            └────────────────┼────────────────┘
                             │
                    Peer Discovery (Headless Service)
                    jotp-headless.default.svc.cluster.local
```

## Configuration

### JVM Memory Configuration

The default configuration optimizes for **1M+ concurrent processes**:

```yaml
resources:
  requests:
    memory: "6Gi"    # Kubernetes pod request
    cpu: "2000m"
  limits:
    memory: "8Gi"    # Kubernetes pod limit
    cpu: "4000m"

# JVM flags (in ConfigMap)
JAVA_OPTS:
  -Xms4g                    # Initial heap
  -Xmx4g                    # Max heap (75% of pod limit)
  -XX:+UseZGC               # ZGC for low pause
  -XX:+ZGenerational        # Generational ZGC
  -XX:SoftMaxHeapSize=6g    # Soft limit
  -Djdk.virtualThreadScheduler.parallelism=16
```

**Memory Breakdown:**
- **4GB heap**: ~1M processes × 3.9KB/process = 3.9GB
- **2GB overhead**: Metaspace, thread stacks, direct buffers
- **2GB headroom**: GC working set, OS buffers

### Scaling the Cluster

#### Scale up to 5 nodes

```bash
kubectl scale statefulset jotp --replicas=5

# Verify new pods are ready
kubectl get pods -l app=jotp -w
```

#### Scale down to 2 nodes

```bash
kubectl scale statefulset jotp --replicas=2

# Note: Highest-index pods are terminated first (jotp-4, jotp-3)
```

#### Update cluster size in ConfigMap

```bash
kubectl edit configmap jotp-config

# Change:
JOTP_CLUSTER_SIZE: "5"

# Then scale the StatefulSet
kubectl scale statefulset jotp --replicas=5
```

### Adjusting Memory for Different Process Counts

| Target Processes | Heap Size | Pod Memory | JAVA_OPTS |
|-----------------|-----------|------------|-----------|
| 100K | 512MB | 1Gi | `-Xms512m -Xmx512m` |
| 1M | 4GB | 6Gi | `-Xms4g -Xmx4g` |
| 5M | 16GB | 20Gi | `-Xms16g -Xmx16g` |
| 10M | 32GB | 40Gi | `-Xms32g -Xmx32g` |

Update the ConfigMap and StatefulSet resources accordingly.

## Monitoring

### Health Checks

```bash
# Liveness (is the pod alive?)
kubectl exec jotp-0 -- curl http://localhost:9091/actuator/health/liveness

# Readiness (can it handle traffic?)
kubectl exec jotp-0 -- curl http://localhost:9091/actuator/health/readiness

# Startup (has it finished initializing?)
kubectl exec jotp-0 -- curl http://localhost:9091/actuator/health/startup
```

### Prometheus Metrics

```bash
# Port-forward to access metrics
kubectl port-forward svc/jotp-monitoring 9090:9090

# Scrape metrics
curl http://localhost:9090/metrics | grep jotp

# Example metrics:
# jotp_processes_total{node="jotp-0",} 1000000
# jotp_messages_total{node="jotp-0",} 5000000
# jotp_supervisor_restarts_total{node="jotp-0",} 42
```

### Logs

```bash
# Follow logs from all pods
kubectl logs -f -l app=jotp

# Logs from specific pod
kubectl logs -f jotp-0

# Logs with peer discovery debug info
kubectl logs jotp-0 | grep -i "peer\|discovery"
```

### Distributed Node Status

```bash
# Connect to a pod and check node status
kubectl exec jotp-0 -- bash -c '
  curl -s http://localhost:9091/actuator/health | jq .
  echo "---"
  echo "Peer nodes:"
  nslookup jotp-headless.default.svc.cluster.local
'
```

## Troubleshooting

### Pods stuck in `Init:0/1`

**Symptom:** Pods stuck at `Init:0/1` (wait-for-peers)

**Cause:** DNS records not ready for peer pods

**Solution:**
```bash
# Check DNS records
kubectl run -it --rm debug --image=busybox:1.36 --restart=Never -- nslookup jotp-headless.default.svc.cluster.local

# Check if all pods are scheduled
kubectl get pods -l app=jotp -o wide

# Verify pod-to-pod DNS resolution
kubectl exec jotp-0 -- nslookup jotp-1.jotp-headless.default.svc.cluster.local
```

### Pods crash with `OutOfMemoryError`

**Symptom:** Pods OOMKilled or crash with OOM

**Cause:** Heap size too large for pod memory limit

**Solution:**
```bash
# Check current memory usage
kubectl top pod jotp-0

# Adjust heap size (leave 25% headroom)
kubectl edit configmap jotp-config
# Change: -Xmx4g → -Xmx3g

# Adjust pod memory limit
kubectl edit statefulset jotp
# Change: memory: "8Gi" → memory: "6Gi"
```

### Peers cannot connect

**Symptom:** Logs show "Connection refused" to peer pods

**Cause:** gRPC port not open or firewall rules

**Solution:**
```bash
# Verify gRPC port is listening
kubectl exec jotp-0 -- netstat -tlnp | grep 50051

# Test peer connectivity
kubectl exec jotp-0 -- nc -zv jotp-1.jotp-headless.default.svc.cluster.local 50051

# Check NetworkPolicy (if present)
kubectl get networkpolicy -l app=jotp
```

### Slow leader election

**Symptom:** Application takes >30s to become ready

**Cause:** Failover timeout or peer discovery latency

**Solution:**
```bash
# Check current timeouts
kubectl get configmap jotp-config -o yaml | grep timeout

# Reduce failover timeout (default: 3s)
kubectl edit configmap jotp-distributed
# Change: failover_timeout: 3s → failover_timeout: 1s

# Restart pods to apply config
kubectl rollout restart statefulset jotp
```

### High CPU usage during startup

**Symptom:** CPU spikes to 400% (4 cores) during startup

**Cause:** JIT compilation and class loading (normal for first 5-10 minutes)

**Solution:** This is expected. To reduce startup time:
```bash
# Use class data sharing (CDS)
# Add to JAVA_OPTS in ConfigMap:
-XX:DumpLoadedClassList=classes.lst
-XX:SharedClassListFile=classes.lst
-XX:SharedArchiveFile=jotp.jsa
-Xshare:dump
```

## Maintenance

### Rolling Updates

```bash
# Trigger a rolling update
kubectl rollout restart statefulset jotp

# Watch update progress
kubectl rollout status statefulset jotp

# Rollback if needed
kubectl rollout undo statefulset jotp
```

### Changing JVM Options

```bash
# Edit the ConfigMap
kubectl edit configmap jotp-config

# Rolling restart to apply changes
kubectl rollout restart statefulset jotp

# Verify new options in logs
kubectl logs jotp-0 | grep "VM Options"
```

### Backup and Restore

```bash
# Backup PVCs
kubectl get pvc -l app=jotp
for pvc in $(kubectl get pvc -l app=jotp -o name); do
  kubectl get $pvc -o yaml > backup-$(basename $pvc).yaml
done

# Restore PVC
kubectl apply -f backup-jotp-data-jotp-0.yaml
```

## Performance Tuning

### Optimize for Throughput

```yaml
# Update ConfigMap
JAVA_OPTS:
  -Djdk.virtualThreadScheduler.parallelism=32  # Increase parallelism
  -XX:+AlwaysPreTouch                          # Pre-touch heap pages
  -XX:+UseLargePages                           # Use large pages (if available)

# Update StatefulSet resources
resources:
  limits:
    cpu: "8000m"  # More CPU for higher throughput
```

### Optimize for Latency

```yaml
# Update ConfigMap
JAVA_OPTS:
  -XX:+UseSerialGC                           # Lower GC pauses
  -XX:MaxGCPauseMillis=10                    # Target GC pause time
  -Djdk.virtualThreadScheduler.parallelism=8 # Reduce parallelism

# Update StatefulSet resources
resources:
  limits:
    cpu: "2000m"  # Consistent CPU for lower jitter
```

### Network Performance

```yaml
# Use hostNetwork for lowest latency (requires security review)
spec:
  template:
    spec:
      hostNetwork: true
      dnsPolicy: ClusterFirstWithHostNet
```

## Security

### Enable Pod Security Standards

```yaml
# Add to StatefulSet pod spec
securityContext:
  runAsNonRoot: true
  runAsUser: 1000
  fsGroup: 1000
  seccompProfile:
    type: RuntimeDefault
```

### Network Policies

```yaml
# Create network policy to restrict traffic
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: jotp-network-policy
spec:
  podSelector:
    matchLabels:
      app: jotp
  policyTypes:
  - Ingress
  - Egress
  ingress:
  - from:
    - podSelector:
        matchLabels:
          app: jotp
    ports:
    - protocol: TCP
      port: 50051
  egress:
  - to:
    - podSelector:
        matchLabels:
          app: jotp
    ports:
    - protocol: TCP
      port: 50051
```

## Production Checklist

- [ ] Adjust replica count for HA (minimum 3)
- [ ] Configure pod anti-affinity for spread across nodes
- [ ] Set up Prometheus scraping for metrics
- [ ] Configure alerting for pod restarts
- [ ] Test failover scenarios (kill pod, watch leader election)
- [ ] Set up log aggregation (ELK, Loki, etc.)
- [ ] Configure resource limits based on load testing
- [ ] Enable pod disruption budgets for zero-downtime updates
- [ ] Set up automated backups for PVCs
- [ ] Configure ingress/tls for external access
- [ ] Test rolling updates and rollbacks
- [ ] Document runbooks for common incidents

## Additional Resources

- [JOTP Documentation](../README.md)
- [Performance Guide](../docs/validation/performance/honest-performance-claims.md)
- [Distributed Node API](../src/main/java/io/github/seanchatmangpt/jotp/distributed/DistributedNode.java)
- [Kubernetes StatefulSet Documentation](https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/)
