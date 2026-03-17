# JOTP Production Deployment Guide

## Executive Summary

This guide provides step-by-step instructions for deploying JOTP in production Kubernetes environments with operational confidence. It covers infrastructure sizing, multi-region deployment, monitoring setup, and disaster recovery procedures.

**Target Scale:** 1-10M concurrent processes per cluster
**Availability Target:** 99.99% (4.38 minutes downtime/month)
**Recovery Time Objective (RTO):** 5 minutes
**Recovery Point Objective (RPO):** 0 seconds (synchronous replication)

---

## 1. Infrastructure Requirements

### 1.1 Cluster Sizing Calculator

**For 1 Million Processes:**

```
Pods: 3 replicas
Per Pod:
  - Heap: 4Gi (supports ~500K processes)
  - Container Memory: 6Gi
  - CPU: 2 cores (request), 4 cores (limit)
  - Storage: 10Gi

Total Cluster:
  - Memory: 3 × 6Gi = 18Gi
  - CPU: 3 × 2 cores = 6 cores
  - Storage: 3 × 10Gi = 30Gi

Recommended Node: c5.xlarge (4 vCPU, 8Gi RAM)
Nodes: 3 (one per pod)
```

**For 10 Million Processes:**

```
Pods: 5 replicas
Per Pod:
  - Heap: 16Gi (supports ~5M processes)
  - Container Memory: 24Gi
  - CPU: 4 cores (request), 8 cores (limit)
  - Storage: 50Gi

Total Cluster:
  - Memory: 5 × 24Gi = 120Gi
  - CPU: 5 × 4 cores = 20 cores
  - Storage: 5 × 50Gi = 250Gi

Recommended Node: c5.4xlarge (16 vCPU, 32Gi RAM)
Nodes: 5 (one per pod)
```

**For 100 Million Processes (Multi-Cluster):**

```
Cluster 1 (US-East): 10 pods × 16Gi = 160Gi total
Cluster 2 (US-West): 10 pods × 16Gi = 160Gi total
Cluster 3 (EU-Central): 10 pods × 16Gi = 160Gi total

Total: 480Gi RAM, 160 CPU cores across 3 regions
Use federation layer for cross-cluster communication
```

### 1.2 Kubernetes Version Requirements

```yaml
minimumVersion: 1.25.0
recommendedVersion: 1.28.0+
features:
  - StatefulSet RollingUpdate
  - PodDisruptionBudget
  - TopologySpreadConstraints
  - CSI persistent storage
  - Network policies
```

### 1.3 Storage Requirements

**Storage Class Configuration:**

```yaml
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: jotp-fast-ssd
provisioner: kubernetes.io/aws-ebs
parameters:
  type: gp3
  iops: "3000"
  throughput: "125"
  encrypted: "true"
allowVolumeExpansion: true
reclaimPolicy: Retain
volumeBindingMode: WaitForFirstConsumer
```

**Sizing Formula:**
```
Base Storage: 10Gi
Per Process State: 1KB
Message Log (high throughput): 100MB/day
Retention: 30 days

10M processes:
  = 10Gi + (10M × 1KB) + (100MB × 30 days)
  = 10Gi + 10Gi + 3Gi
  = 23Gi → round to 50Gi for safety
```

---

## 2. Multi-Region Deployment

### 2.1 Architecture Overview

```
                    ┌─────────────────────┐
                    │   Global DNS / CDN  │
                    └─────────┬───────────┘
                              │
          ┌───────────────────┼───────────────────┐
          │                   │                   │
    ┌─────▼─────┐      ┌─────▼─────┐      ┌─────▼─────┐
    │ US-East   │      │ US-West   │      │ EU-Central│
    │ Cluster 1 │◀────▶│ Cluster 2 │◀────▶│ Cluster 3 │
    │ 10 pods   │      │ 10 pods   │      │ 10 pods   │
    └───────────┘      └───────────┘      └───────────┘
          │                   │                   │
          └───────────────────┼───────────────────┘
                              │
                    ┌─────────▼─────────┐
                    │  Federation Layer │
                    │  Global Registry  │
                    │  Async Replication│
                    └───────────────────┘
```

### 2.2 Federation Configuration

**Cluster 1 (US-East) - Primary:**
```yaml
# k8s/overlays/us-east/kustomization.yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

namespace: jotp-prod-us-east

resources:
  - ../../base

replicas:
  - name: jotp
    count: 10

patches:
  - patch: |-
      - op: replace
        path: /spec/template/spec/containers/0/env/8/value
        value: "jotp-cluster-us-east"
      - op: replace
        path: /spec/template/spec/containers/0/env/10/value
        value: "primary"
    target:
      kind: StatefulSet
      name: jotp

configMapGenerator:
  - name: jotp-config
    literals:
      - JOTP_FEDERATION_MODE="primary"
      - JOTP_PEER_CLUSTERS="us-west,eu-central"
      - JOTP_REPLICATION_MODE="async"
```

**Cluster 2 (US-West) - Secondary:**
```yaml
# k8s/overlays/us-west/kustomization.yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

namespace: jotp-prod-us-west

resources:
  - ../../base

replicas:
  - name: jotp
    count: 10

patches:
  - patch: |-
      - op: replace
        path: /spec/template/spec/containers/0/env/8/value
        value: "jotp-cluster-us-west"
      - op: replace
        path: /spec/template/spec/containers/0/env/10/value
        value: "secondary"
    target:
      kind: StatefulSet
      name: jotp

configMapGenerator:
  - name: jotp-config
    literals:
      - JOTP_FEDERATION_MODE="secondary"
      - JOTP_PRIMARY_CLUSTER="us-east"
      - JOTP_REPLICATION_MODE="async"
```

### 2.3 Cross-Cluster Networking

**Using VPC Peering (AWS):**

```bash
# Peer VPCs across regions
aws ec2 create-vpc-peering-connection \
  --vpc-id vpc-1 \
  --peer-vpc-id vpc-2 \
  --peer-region us-west-2

# Accept in peer region
aws ec2 accept-vpc-peering-connection \
  --vpc-peering-connection-id pcx-12345

# Update route tables
aws ec2 create-route \
  --route-table-id rtb-1 \
  --destination-cidr-block 10.1.0.0/16 \
  --vpc-peering-connection-id pcx-12345
```

**Using Istio Multi-Cluster:**

```yaml
apiVersion: networking.istio.io/v1alpha3
kind: ServiceEntry
metadata:
  name: jotp-us-west
spec:
  hosts:
  - jotp-us-west.svc.cluster.local
  addresses:
  - 10.1.0.0/16
  ports:
  - number: 50051
    name: grpc
    protocol: GRPC
  location: MESH_INTERNAL
```

---

## 3. Deployment Procedure

### 3.1 Pre-Deployment Checklist

**Infrastructure:**
- [ ] Kubernetes cluster provisioned (1.28+)
- [ ] Node pools configured (3+ zones)
- [ ] Storage class created (fast-ssd)
- [ ] Network policies enabled
- [ ] Load balancer provisioned
- [ ] DNS records configured
- [ ] TLS certificates provisioned

**Application:**
- [ ] Docker image built and pushed
- [ ] Configuration values validated
- [ ] Secrets created (encrypted)
- [ ] Resource limits calculated
- [ ] Health check endpoints verified
- [ ] Monitoring endpoints exposed

**Operational:**
- [ ] Alerting rules configured
- [ ] Dashboards created
- [ ] Logging aggregation setup
- [ ] Backup schedules configured
- [ ] Disaster recovery tested
- [ ] Runbooks documented

### 3.2 Step-by-Step Deployment

**Step 1: Create Namespace and RBAC**

```bash
kubectl create namespace jotp-prod

kubectl apply -f k8s/serviceaccount.yaml -n jotp-prod
kubectl apply -f k8s/role.yaml -n jotp-prod
kubectl apply -f k8s/rolebinding.yaml -n jotp-prod
```

**Step 2: Deploy Configuration**

```bash
kubectl create configmap jotp-config \
  --from-literal=java-opts="-Xms16G -Xmx16G -XX:+UseZGC" \
  --from-literal=jotp-cluster-name="jotp-prod" \
  --from-literal=jotp-max-processes="10000000" \
  -n jotp-prod

kubectl create secret generic jotp-secrets \
  --from-literal=cluster-cookie="$(openssl rand -base64 32)" \
  --from-literal=tls-key="$(cat tls.key)" \
  --from-literal=tls-cert="$(cat tls.crt)" \
  -n jotp-prod
```

**Step 3: Deploy Storage and Services**

```bash
kubectl apply -f k8s/StorageClass.yaml -n jotp-prod
kubectl apply -f k8s/service.yaml -n jotp-prod
kubectl apply -f k8s/PodDisruptionBudget.yaml -n jotp-prod
```

**Step 4: Deploy StatefulSet**

```bash
kubectl apply -f k8s/statefulset.yaml -n jotp-prod

# Watch pods come up
kubectl get pods -n jotp-prod -l app=jotp -w
```

**Step 5: Verify Deployment**

```bash
# Check all pods ready
kubectl wait --for=condition=ready pod -l app=jotp -n jotp-prod --timeout=600s

# Verify peer connectivity
kubectl exec jotp-0 -n jotp-prod -- nslookup jotp-headless.jotp-prod.svc.cluster.local

# Check health
kubectl exec jotp-0 -n jotp-prod -- curl http://localhost:9091/actuator/health

# Verify leader election
kubectl logs jotp-0 -n jotp-prod | grep -i leader
```

**Step 6: Setup Monitoring**

```bash
kubectl apply -f k8s/examples/prometheus-rules.yaml -n monitoring
kubectl apply -f k8s/examples/grafana-dashboard.json -n monitoring
```

### 3.3 Rolling Update Procedure

**Step 1: Prepare New Version**

```bash
# Build and push new image
docker build -t jotp:1.1.0 .
docker tag jotp:1.1.0 registry.example.com/jotp:1.1.0
docker push registry.example.com/jotp:1.1.0
```

**Step 2: Update Image**

```bash
kubectl set image statefulset jotp \
  jotp=registry.example.com/jotp:1.1.0 \
  -n jotp-prod
```

**Step 3: Monitor Rollout**

```bash
kubectl rollout status statefulset jotp -n jotp-prod

# Watch metrics
kubectl top pod -n jotp-prod -l app=jotp
```

**Step 4: Verify Health**

```bash
# Check no increase in error rate
kubectl logs jotp-0 -n jotp-prod --tail=100 | grep -i error

# Verify process count stable
kubectl exec jotp-0 -n jotp-prod -- curl http://localhost:9091/actuator/metrics/jotp.process.count
```

**Step 5: Rollback (if needed)**

```bash
kubectl rollout undo statefulset jotp -n jotp-prod
```

---

## 4. Monitoring and Alerting

### 4.1 Critical Metrics Dashboard

**System Metrics:**
```yaml
# Pod Health
  - Pod Running Count
  - Pod Ready Count
  - Pod Restart Count
  - Pod CPU Usage
  - Pod Memory Usage
  - Pod Disk Usage
  - Pod Network I/O
```

**Application Metrics:**
```yaml
# Process Metrics
  - Total Process Count
  - Active Process Count
  - Process Creation Rate
  - Process Crash Rate

# Message Metrics
  - Message Throughput (msg/s)
  - Message Latency (p50, p99)
  - Mailbox Size (p99)
  - Message Drop Rate

# Supervisor Metrics
  - Supervisor Restart Rate
  - Child Process Crash Rate
  - Supervisor Tree Depth
```

**Coordination Metrics:**
```yaml
# Cluster Health
  - Peer Connection Count
  - Leader Election Count
  - Network Partition Events
  - Split-Brain Events
  - Replication Lag
```

### 4.2 Alert Rules

**Critical Alerts (PagerDuty):**

```yaml
groups:
  - name: jotp-critical
    rules:
      # Cluster health
      - alert: JOTPClusterDegraded
        expr: |
          count(kube_pod_status_ready{app="jotp"} == 0) /
          count(kube_pod_status_ready{app="jotp"}) > 0.33
        for: 2m
        labels:
          severity: critical
          team: platform
        annotations:
          summary: "JOTP cluster degraded: more than 33% pods down"
          runbook: "https://runbooks.example.com/jotp-cluster-down"

      # Resource exhaustion
      - alert: JOTPHeapTooHigh
        expr: |
          jvm_memory_used_bytes{area="heap"} /
          jvm_memory_max_bytes{area="heap"} > 0.9
        for: 5m
        labels:
          severity: critical
          team: platform
        annotations:
          summary: "JOTP heap usage >90% for 5 minutes"
          runbook: "https://runbooks.example.com/jotp-heap-high"

      # Message loss
      - alert: JOTPMessageLossDetected
        expr: |
          rate(jotp_messages_dropped_total[5m]) > 0
        for: 1m
        labels:
          severity: critical
          team: platform
        annotations:
          summary: "JOTP is dropping messages"
          runbook: "https://runbooks.example.com/jotp-message-loss"
```

**Warning Alerts (Slack):**

```yaml
  - name: jotp-warning
    rules:
      # Performance degradation
      - alert: JOTPMessageLatencyHigh
        expr: |
          histogram_quantile(0.99, jotp_message_latency_seconds) > 0.1
        for: 5m
        labels:
          severity: warning
          team: platform
        annotations:
          summary: "JOTP message latency p99 >100ms"

      # Scale needed
      - alert: JOTPHighCPUUsage
        expr: |
          rate(container_cpu_usage_seconds_total{app="jotp"}[5m]) > 0.8
        for: 10m
        labels:
          severity: warning
          team: platform
        annotations:
          summary: "JOTP CPU usage >80% for 10 minutes"
          description: "Consider scaling up"
```

---

## 5. Disaster Recovery

### 5.1 Backup Strategy

**Automated Daily Backups:**

```yaml
apiVersion: batch/v1
kind: CronJob
metadata:
  name: jotp-backup
  namespace: jotp-prod
spec:
  schedule: "0 2 * * *"  # Daily at 2 AM
  jobTemplate:
    spec:
      template:
        spec:
          containers:
          - name: backup
            image: amazon/aws-cli:latest
            command:
            - /bin/bash
            - -c
            - |
              # Snapshot all PVCs
              for pvc in $(kubectl get pvc -n jotp-prod -o name); do
                kubectl create -n jotp-prod -f - <<EOF
              apiVersion: v1
              kind: PersistentVolumeClaim
              metadata:
                name: ${pvc}-backup-$(date +%Y%m%d)
              spec:
                accessModes: [ "ReadWriteOnce" ]
                storageClassName: jotp-backup
                dataSource:
                  name: ${pvc}
                  kind: PersistentVolumeClaim
              EOF
              done
          env:
          - name: AWS_ACCESS_KEY_ID
            valueFrom:
              secretKeyRef:
                name: backup-credentials
                key: access-key
          - name: AWS_SECRET_ACCESS_KEY
            valueFrom:
              secretKeyRef:
                name: backup-credentials
                key: secret-key
          restartPolicy: OnFailure
```

**Backup Retention:**

```bash
# Keep daily backups for 30 days
kubectl label pvc -n jotp-prod backup-date=$(date +%Y%m%d) --overwrite

# Cleanup old backups
kubectl delete pvc -n jotp-prod -l backup-date=$(date -d "30 days ago" +%Y%m%d)
```

### 5.2 Recovery Procedures

**Scenario 1: Single Pod Failure**

```bash
# Kubernetes auto-restarts failed pods
# Manual intervention not required

# Verify recovery
kubectl get pod jotp-0 -n jotp-prod
kubectl logs jotp-0 -n jotp-prod --tail=50
```

**Scenario 2: Node Failure**

```bash
# Identify failed node
kubectl get nodes -l kubernetes.io/hostname=failed-node

# Cordone and drain
kubectl cordon failed-node
kubectl drain failed-node --ignore-daemonsets --force

# Verify pods rescheduled
kubectl get pods -n jotp-prod -o wide

# Remove node (if permanent)
kubectl delete node failed-node
```

**Scenario 3: Region Failure (Failover)**

```bash
# Update DNS to point to backup region
aws route53 change-resource-record-sets \
  --hosted-zone-id Z123456 \
  --change-batch '{
    "Changes": [{
      "Action": "UPSERT",
      "ResourceRecordSet": {
        "Name": "jotp.example.com",
        "Type": "CNAME",
        "TTL": 60,
        "ResourceRecords": [{"Value": "jotp-backup.example.com"}]
      }
    }]
  }'

# Verify backup cluster takes over
kubectl get pods -n jotp-prod-backup
kubectl exec jotp-0 -n jotp-prod-backup -- curl http://localhost:9091/actuator/health

# Restore primary region
aws route53 change-resource-record-sets \
  --hosted-zone-id Z123456 \
  --change-batch '{
    "Changes": [{
      "Action": "UPSERT",
      "ResourceRecordSet": {
        "Name": "jotp.example.com",
        "Type": "CNAME",
        "TTL": 60,
        "ResourceRecords": [{"Value": "jotp-primary.example.com"}]
      }
    }]
  }'
```

**Scenario 4: Data Corruption**

```bash
# Identify corrupted PVC
kubectl describe pvc jotp-data-jotp-0 -n jotp-prod

# Stop affected pod
kubectl scale statefulset jotp --replicas=2 -n jotp-prod

# Restore from backup
kubectl delete pvc jotp-data-jotp-0 -n jotp-prod
kubectl apply -f jotp-data-jotp-0-backup.yaml -n jotp-prod

# Restart pod
kubectl scale statefulset jotp --replicas=3 -n jotp-prod

# Verify data integrity
kubectl exec jotp-0 -n jotp-prod -- curl http://localhost:9091/actuator/health
```

### 5.3 Recovery Time Objectives

| Scenario | RTO | RPO | Automation |
|----------|-----|-----|------------|
| Pod failure | 2min | 0 | ✓ Auto |
| Node failure | 5min | 0 | ✓ Auto |
| Zone failure | 10min | 0 | ✓ Auto |
| Region failure | 30min | 5min | ⚠ Semi-auto |
| Data corruption | 1hour | 24h | ✗ Manual |
| Delete all pods | 5min | 0 | ✓ Auto |

---

## 6. Performance Tuning

### 6.1 JVM Optimization

**For 16Gi Heap (10M processes):**

```bash
JAVA_OPTS="
  -Xms16G
  -Xmx16G
  -XX:+UseZGC
  -XX:+DisableExplicitGC
  -XX:ConcGCThreads=4
  -XX:ParallelGCThreads=8
  -XX:ZAllocationSpikeTolerance=5
  -XX:+AlwaysPreTouch
  -XX:+PrintGCDetails
  -XX:+PrintGCTimeStamps
  -XX:+HeapDumpOnOutOfMemoryError
  -XX:HeapDumpPath=/logs/heapdump.hprof
  -XX:+ExitOnOutOfMemoryError
  -Djuby.memory.max=16G
"
```

**GC Monitoring:**

```bash
# Check GC pause times
kubectl exec jotp-0 -n jotp-prod -- jstat -gc 1

# Expected ZGC pauses:
# - p50: <1ms
# - p99: <10ms
# - Max: <100ms
```

### 6.2 Kubernetes Resource Tuning

**CPU Throttling Prevention:**

```yaml
resources:
  requests:
    cpu: "4000m"  # Match baseline usage
  limits:
    cpu: "8000m"  # Allow 2x burst
```

**Memory Limit Headroom:**

```yaml
resources:
  requests:
    memory: "24Gi"  # Heap + non-heap
  limits:
    memory: "32Gi"  # 25% headroom
```

### 6.3 Network Optimization

**gRPC Tuning:**

```yaml
env:
  - name: GRPC_POLL_STRATEGY
    value: "polling"  # Better latency
  - name: GRPC_KEEPALIVE_TIME
    value: "10s"
  - name: GRPC_KEEPALIVE_TIMEOUT
    value: "5s"
  - name: GRPC_KEEPALIVE_PERMIT_WITHOUT_STREAMS
    value: "true"
```

---

## 7. Security Hardening

### 7.1 Pod Security

```yaml
securityContext:
  runAsNonRoot: true
  runAsUser: 1000
  runAsGroup: 1000
  fsGroup: 1000
  seccompProfile:
    type: RuntimeDefault
  capabilities:
    drop:
    - ALL
  readOnlyRootFilesystem: true
```

### 7.2 Network Policies

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: jotp-network-policy
  namespace: jotp-prod
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
    - namespaceSelector:
        matchLabels:
          kubernetes.io/metadata.name: ingress-nginx
    ports:
    - protocol: TCP
      port: 50051  # gRPC
    - protocol: TCP
      port: 8080  # HTTP
  egress:
  - to:
    - podSelector:
        matchLabels:
          app: jotp
    ports:
    - protocol: TCP
      port: 50051
  - to:
    - namespaceSelector:
        matchLabels:
          kubernetes.io/metadata.name: kube-system
    ports:
    - protocol: UDP
      port: 53  # DNS
```

### 7.3 Secrets Management

```bash
# Use encrypted secrets
kubectl create secret generic jotp-secrets \
  --from-literal=cluster-cookie="$(openssl rand -base64 32)" \
  --from-literal=tls-key="$(cat tls.key)" \
  --from-literal=tls-cert="$(cat tls.crt)" \
  --dry-run=client -o yaml | \
  kubectl encrypt -n jotp-prod | \
  kubectl apply -f -

# Enable encryption at rest
kubectl patch encryptionconfig configuration \
  --type='json' -p='[{"op": "add", "path": "/resources/-", "value": {"secretbox": {"keys": [{"name": "key1", "secret": "$(openssl rand -base64 32)"}]}}}]'
```

---

## 8. Runbooks

### 8.1 Common Issues

**Issue: Pods Not Ready**

```bash
# Check pod status
kubectl describe pod jotp-0 -n jotp-prod

# Common causes:
# 1. Image pull error → Check image name/registry
# 2. Resource limits → Check node capacity
# 3. Init container failure → Check peer DNS
# 4. Liveness probe failing → Check app logs

# View logs
kubectl logs jotp-0 -n jotp-prod --all-containers=true
```

**Issue: High Memory Usage**

```bash
# Check memory usage
kubectl top pod jotp-0 -n jotp-prod

# Check heap usage
kubectl exec jotp-0 -n jotp-prod -- jmap -heap 1

# If heap >90%:
# 1. Check for memory leaks
kubectl exec jotp-0 -n jotp-prod -- jmap -histo:live 1 | head -20

# 2. Take heap dump
kubectl exec jotp-0 -n jotp-prod -- jcmd 1 GC.heap_dump /logs/heapdump.hprof

# 3. Scale up or restart
kubectl rollout restart statefulset jotp -n jotp-prod
```

**Issue: Leader Election Failing**

```bash
# Check leader election logs
kubectl logs jotp-0 -n jotp-prod | grep -i election

# Check network connectivity
kubectl exec jotp-0 -n jotp-prod -- nc -zv jotp-1.jotp-headless 50051

# If split-brain suspected:
# 1. Stop all pods
kubectl scale statefulset jotp --replicas=0 -n jotp-prod

# 2. Start pods sequentially
kubectl scale statefulset jotp --replicas=1 -n jotp-prod
kubectl wait --for=condition=ready pod -l app=jotp -n jotp-prod
kubectl scale statefulset jotp --replicas=3 -n jotp-prod
```

---

## 9. Validation Checklist

**Pre-Deployment:**
- [ ] Infrastructure provisioned
- [ ] DNS records configured
- [ ] TLS certificates valid
- [ ] Monitoring setup
- [ ] Alerts configured
- [ ] Backup scheduled

**Post-Deployment:**
- [ ] All pods ready
- [ ] Leader elected
- [ ] Peer connectivity verified
- [ ] Health checks passing
- [ ] Metrics streaming
- [ ] Logs ingesting

**Rollback Criteria:**
- [ ] Error rate >1%
- [ ] Latency p99 >100ms
- [ ] Pod restart count >3
- [ ] Memory usage >90%
- [ ] Leader election failures

---

**Document Version:** 1.0
**Last Updated:** 2025-03-17
**Maintained By:** Platform Team
