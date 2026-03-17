# Linkerd Integration Guide for JOTP

**Overview:** Running JOTP applications on Linkerd service mesh with lightweight mTLS, automatic observability, and reliability features.

**Prerequisites:**
- Kubernetes cluster with Linkerd 2.14+ (stable-2.14.x)
- JOTP application containerized
- linkerd CLI installed

---

## Table of Contents

1. [Quick Start](#quick-start)
2. [Installation](#installation)
3. [Service Profiles](#service-profiles)
4. [Traffic Splitting](#traffic-splitting)
5. [Observability](#observability)
6. [Security](#security)
7. [Production Examples](#production-examples)

---

## Quick Start

### 1. Install Linkerd CLI

```bash
# macOS
brew install linkerd

# Linux
curl -sL https://run.linkerd.io/install | sh

# Verify installation
linkerd version
```

### 2. Install Linkerd onto Cluster

```bash
# Validate cluster
linkerd check --pre

# Install control plane
linkerd install | kubectl apply -f -

# Verify installation
linkerd check

# Install viz extension (optional but recommended)
linkerd viz install | kubectl apply -f -
```

### 3. Enable Linkerd for JOTP Namespace

```bash
# Annotate namespace for automatic proxy injection
kubectl annotate namespace jotp-system linkerd.io/inject=enabled

# Verify annotation
kubectl get namespace jotp-system -o yaml | grep inject
```

### 4. Deploy JOTP Application

```yaml
# deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: jotp-telemetry
  namespace: jotp-system
spec:
  replicas: 3
  selector:
    matchLabels:
      app: jotp-telemetry
  template:
    metadata:
      labels:
        app: jotp-telemetry
      annotations:
        linkerd.io/inject: enabled  # Explicit injection
    spec:
      containers:
      - name: jotp
        image: jotp/telemetry-service:1.0.0
        ports:
        - containerPort: 8080
          name: http
        - containerPort: 9090
          name: grpc
        env:
        - name: JAVA_OPTS
          value: "-XX:MaxRAMPercentage=75.0 -Duser.timezone=UTC"
        - name: LINKERD_PROXY_LOG
          value: "warn"  # Reduce proxy log noise
        resources:
          requests:
            memory: "512Mi"
            cpu: "500m"
          limits:
            memory: "2Gi"
            cpu: "2000m"
        readinessProbe:
          httpGet:
            path: /health/ready
            port: 8080
          initialDelaySeconds: 10
          periodSeconds: 5
        livenessProbe:
          httpGet:
            path: /health/live
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
---
apiVersion: v1
kind: Service
metadata:
  name: jotp-telemetry
  namespace: jotp-system
spec:
  ports:
  - port: 8080
    name: http
    targetPort: 8080
  - port: 9090
    name: grpc
    targetPort: 9090
  selector:
    app: jotp-telemetry
```

### 5. Verify Mesh Deployment

```bash
# Check proxy injection
kubectl get pods -n jotp-system -l app=jotp-telemetry

# Verify proxy status
linkerd -n jotp-system check --proxy

# View dashboard
linkerd viz dashboard &
```

---

## Installation

### Linkerd Control Plane Configuration

```bash
# Install with custom configuration
linkerd install --helm-set \
  identity.issuer.tls.issuerExpiryYears=1,\
  proxy.resources.requests.cpu=100m,\
  proxy.resources.requests.memory=128Mi | kubectl apply -f -

# Install Jaeger extension for tracing
linkerd jaeger install | kubectl apply -f -

# Install multicluster extension (if using multi-cluster)
linkerd multicluster install | kubectl apply -f -
```

### Proxy Configuration for JOTP

```yaml
# config-linkerd-proxy.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: linkerd-config
  namespace: jotp-system
data:
  linkerd-proxy-config: |
    # Proxy timeout configuration
    proxy:
      outboundConnectTimeout: 30s
      grpc:
        # gRPC specific settings for JOTP
        maxStreams: 1000
        windowSize: 65536  # 64KB

      # Resource limits
      resources:
        requests:
          cpu: 100m
          memory: 128Mi
        limits:
          cpu: 500m
          memory: 512Mi
```

---

## Service Profiles

### Creating Service Profiles

**Service Profile for JOTP gRPC Service:**

```yaml
# jotp-telemetry-profile.yaml
apiVersion: linkerd.io/v1alpha2
kind: ServiceProfile
metadata:
  name: jotp-telemetry
  namespace: jotp-system
spec:
  # gRPC service definition
  routes:
  - name: ProcessTelemetry
    condition:
      method: POST  # gRPC POST for unary calls
      pathRegex: "/io.github.seanchatmangpt.jotp.TelemetryService/ProcessTelemetry"
    # Define timeout for JOTP process operations
    timeout: 30s
    # Retry configuration
    isRetryable: true
    retries:
      limit: 3
      initialInterval: 100ms
      maxInterval: 1s

  - name: StreamTelemetry
    condition:
      method: POST
      pathRegex: "/io.github.seanchatmangpt.jotp.TelemetryService/StreamTelemetry"
    timeout: 5m  # Longer timeout for streaming
    isRetryable: false  # Don't retry streaming calls

  - name: GetProcessStatus
    condition:
      method: GET
      pathRegex: "/io.github.seanchatmangpt.jotp.TelemetryService/GetProcessStatus"
    timeout: 5s
    isRetryable: true

  # HTTP endpoints
  - name: HealthCheck
    condition:
      method: GET
      pathRegex: "/health/.*"
    timeout: 2s
    isRetryable: true

  - name: Metrics
    condition:
      method: GET
      pathRegex: "/metrics"
    timeout: 5s
    isRetryable: true

  # Retry policy configuration
  retryFilter:
    backoff:
      minMs: 100
      maxMs: 1000
    # Retry on specific JOTP error conditions
    retryableStatuses:
    - 503  # Service unavailable (process restart)
    - 504  # Gateway timeout (process timeout)
    - 408  # Request timeout
```

### Generating Service Profile from Proto

```bash
# Automatically generate from gRPC proto file
linkerd profile --proto proto/telemetry.proto jotp-telemetry -n jotp-system

# Or from existing OpenAPI spec
linkerd profile --openapi openapi.yaml jotp-telemetry -n jotp-system

# Or auto-generate from tap (requires live traffic)
linkerd profile auto-jotp-telemetry -n jotp-system --duration 5m
```

### Service Profile for JOTP Process Communication

```yaml
# jotp-process-profile.yaml
apiVersion: linkerd.io/v1alpha2
kind: ServiceProfile
metadata:
  name: jotp-process-registry
  namespace: jotp-system
spec:
  routes:
  - name: RegisterProcess
    condition:
      method: POST
      pathRegex: "/api/v1/process/register"
    timeout: 5s
    isRetryable: false  # Registration should not be retried

  - name: LookupProcess
    condition:
      method: GET
      pathRegex: "/api/v1/process/lookup/.*"
    timeout: 2s
    isRetryable: true
    retries:
      limit: 2
      initialInterval: 50ms
      maxInterval: 200ms

  - name: SendMessage
    condition:
      method: POST
      pathRegex: "/api/v1/message/send"
    timeout: 10s
    isRetryable: true
    retries:
      limit: 3
      initialInterval: 100ms
      maxInterval: 500ms

  # Response classification
  responseClasses:
  - class: Success
    status:
    - 200
    - 201
    - 204
  - class: RetryableError
    status:
    - 408  # Timeout
    - 503  # Service unavailable (process restart)
    - 504  # Gateway timeout
  - class: FatalError
    status:
    - 400  # Bad request
    - 404  # Process not found
```

---

## Traffic Splitting

### Canary Deployments

```yaml
# canary-traffic-split.yaml
apiVersion: split.smi-spec.io/v1alpha2
kind: TrafficSplit
metadata:
  name: jotp-telemetry-canary
  namespace: jotp-system
spec:
  service: jotp-telemetry
  backends:
  - service: jotp-telemetry
    weight: 95  # 95% to stable version
  - service: jotp-telemetry-canary
    weight: 5   # 5% to canary version
---
# Deploy canary version
apiVersion: v1
kind: Service
metadata:
  name: jotp-telemetry-canary
  namespace: jotp-system
spec:
  ports:
  - port: 8080
    name: http
    targetPort: 8080
  selector:
    app: jotp-telemetry
    version: v2  # Canary version label
```

### Blue-Green Deployments

```yaml
# blue-green-traffic-split.yaml
apiVersion: split.smi-spec.io/v1alpha2
kind: TrafficSplit
metadata:
  name: jotp-telemetry-bluegreen
  namespace: jotp-system
spec:
  service: jotp-telemetry
  backends:
  - service: jotp-telemetry-blue
    weight: 100  # All traffic to blue
  - service: jotp-telemetry-green
    weight: 0    # No traffic to green
---
# Header-based routing (for testing)
apiVersion: policy.linkerd.io/v1beta1
kind: HTTPRoute
metadata:
  name: jotp-telemetry-header-route
  namespace: jotp-system
spec:
  parentRefs:
  - name: jotp-telemetry
    kind: Service
  rules:
  - matches:
    - headers:
      - name: x-environment
        value: green
    backendRefs:
    - name: jotp-telemetry-green
      port: 8080
  - backendRefs:
    - name: jotp-telemetry-blue
      port: 8080
```

### A/B Testing

```yaml
# ab-test-traffic-split.yaml
apiVersion: split.smi-spec.io/v1alpha2
kind: TrafficSplit
metadata:
  name: jotp-telemetry-ab-test
  namespace: jotp-system
spec:
  service: jotp-telemetry
  backends:
  - service: jotp-telemetry-variant-a
    weight: 50  # 50% variant A
  - service: jotp-telemetry-variant-b
    weight: 50  # 50% variant B
---
# Header-based A/B testing
apiVersion: policy.linkerd.io/v1beta1
kind: HTTPRoute
metadata:
  name: jotp-telemetry-ab-route
  namespace: jotp-system
spec:
  parentRefs:
  - name: jotp-telemetry
    kind: Service
  rules:
  - matches:
    - headers:
      - name: x-variant
        value: A
    backendRefs:
    - name: jotp-telemetry-variant-a
      port: 8080
  - matches:
    - headers:
      - name: x-variant
        value: B
    backendRefs:
    - name: jotp-telemetry-variant-b
      port: 8080
```

### Progressive Rollout

```bash
# Script for gradual traffic shift
#!/bin/bash
for percentage in 5 10 25 50 75 100; do
  kubectl patch trafficsplit jotp-telemetry-canary -n jotp-system -p '
  {
    "spec": {
      "backends": [
        {"service": "jotp-telemetry", "weight": '"$((100 - percentage))"' },
        {"service": "jotp-telemetry-canary", "weight": '"$percentage"' }
      ]
    }
  }
  '
  echo "Canary traffic: $percentage%"
  sleep 300  # Wait 5 minutes between shifts
done
```

---

## Observability

### Linkerd Viz Dashboard

**Access the Dashboard:**

```bash
# Open dashboard in browser
linkerd viz dashboard

# Or port-forward
kubectl -n linkerd-viz port-forward svc/web 8084:8084
```

**Key Metrics for JOTP:**

```
# In the Linkerd Viz dashboard:
1. Service Mesh Overview
   - JOTP service latency (p50, p95, p99)
   - Success rate (should be > 99.9%)
   - Requests per second

2. Service Details (jotp-telemetry)
   - Inbound/Outbound traffic
   - Top sources/destinations
   - HTTP status distribution

3. Deployment Details
   - Pod-to-pod communication
   - Resource usage
   - Proxy metrics
```

### Tap: Live Traffic Inspection

```bash
# Watch JOTP process messages in real-time
linkerd viz tap deploy/jotp-telemetry -n jotp-system

# Filter for specific routes
linkerd viz tap deploy/jotp-telemetry -n jotp-system \
  --path regex:/api/v1/process/.*

# Filter for errors
linkerd viz tap deploy/jotp-telemetry -n jotp-system \
  --to deployment/jotp-telemetry \
  --method GET

# Filter for gRPC calls
linkerd viz tap deploy/jotp-telemetry -n jotp-system \
  --scheme grpc
```

### Top: Resource Usage Analysis

```bash
# Top services by request rate
linkerd viz top deploy -n jotp-system

# Top services by latency
linkerd viz top deploy -n jotp-system --sort latency

# Top services by error rate
linkerd viz top deploy -n jotp-system --sort error-rate

# Watch specific deployment
linkerd viz top deploy/jotp-telemetry -n jotp-system
```

### Stat: Metrics Endpoint

```bash
# Get metrics for specific pod
linkerd viz stat deploy/jotp-telemetry -n jotp-system

# Detailed metrics
linkerd viz stat deploy/jotp-telemetry -n jotp-system --by pod

# Include namespace metrics
linkerd viz stat ns/jotp-system

# Export metrics for Prometheus
linkerd viz stat deploy/jotp-telemetry -n jotp-system --format json
```

### JOTP Metrics Integration

```java
// JOTP metrics that integrate with Linkerd
public class JOTPMetricsExporter {
    private final ProcessMetrics metrics;

    @GET
    @Path("/metrics")
    @Produces(MediaType.TEXT_PLAIN)
    public String getMetrics() {
        ProcessMetricsSnapshot snapshot = metrics.snapshot();

        // Linkerd automatically scrapes this endpoint
        return """
            # JOTP Process Metrics (compatible with Linkerd)
            # HELP jotp_process_count Total JOTP processes
            # TYPE jotp_process_count gauge
            jotp_process_count %d

            # HELP jotp_message_throughput Messages per second
            # TYPE jotp_message_throughput gauge
            jotp_message_throughput %.2f

            # HELP jotp_supervisor_restarts Supervisor restart count
            # TYPE jotp_supervisor_restarts counter
            jotp_supervisor_restarts %d

            # HELP jotp_queue_depth Process queue depth
            # TYPE jotp_queue_depth gauge
            jotp_queue_depth %d
            """.formatted(
            snapshot.processesCreated() - snapshot.processesTerminated(),
            snapshot.messagesSent() / 60.0,  # per minute
            snapshot.processesRestarted(),
            snapshot.currentQueueDepth()
        );
    }
}
```

### Grafana Dashboard for JOTP

```json
{
  "dashboard": {
    "title": "JOTP on Linkerd",
    "panels": [
      {
        "title": "Request Rate (Linkerd)",
        "targets": [
          {
            "expr": "sum(rate(request_total{namespace=\"jotp-system\",deployment=\"jotp-telemetry\"}[1m]))"
          }
        ]
      },
      {
        "title": "Latency P95 (Linkerd)",
        "targets": [
          {
            "expr": "histogram_quantile(0.95, sum(rate(response_latency_ms_bucket{namespace=\"jotp-system\",deployment=\"jotp-telemetry\"}[5m])) by (le))"
          }
        ]
      },
      {
        "title": "Success Rate (Linkerd)",
        "targets": [
          {
            "expr": "sum(rate(response_total{namespace=\"jotp-system\",deployment=\"jotp-telemetry\",classification=\"success\"}[5m])) / sum(rate(response_total{namespace=\"jotp-system\",deployment=\"jotp-telemetry\"}[5m]))"
          }
        ]
      },
      {
        "title": "JOTP Process Count",
        "targets": [
          {
            "expr": "jotp_process_count{namespace=\"jotp-system\"}"
          }
        ]
      },
      {
        "title": "JOTP Message Throughput",
        "targets": [
          {
            "expr": "rate(jotp_messages_sent[1m])"
          }
        ]
      }
    ]
  }
}
```

---

## Security

### mTLS Configuration

**Linkerd provides mTLS by default:**

```bash
# Verify mTLS is enabled
linkerd -n jotp-system check --proxy --tls

# View identity certificates
kubectl get secrets -n jotp-system -l linkerd.io/control-plane-ns=linkerd

# Check proxy TLS status
kubectl exec -n jotp-system deploy/jotp-telemetry -c linkerd-proxy -- \
  /usr/bin/curl -s localhost:4191/metrics | grep tls
```

### Identity and Trust

```bash
# Install Linkerd with custom identity
linkerd install --identity-issuer-type=kubernetes.io/tls | kubectl apply -f -

# Or use external identity (e.g., cert-manager)
linkerd install --identity-issuer-type=cert-manager \
  --identity-issuer-certificate-external \
  | kubectl apply -f -
```

### Network Policies with Linkerd

```yaml
# network-policy.yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: jotp-linkerd-policy
  namespace: jotp-system
spec:
  podSelector:
    matchLabels:
      app: jotp-telemetry
  policyTypes:
  - Ingress
  - Egress
  ingress:
  # Allow from Linkerd proxy
  - from:
    - namespaceSelector:
        matchLabels:
          linkerd.io/inject: enabled
    ports:
    - protocol: TCP
      port: 8080
    - protocol: TCP
      port: 9090
  egress:
  # Allow to Linkerd control plane
  - to:
    - namespaceSelector:
        matchLabels:
          name: linkerd
    ports:
    - protocol: TCP
      port: 8443  # Destination service

  # Allow to other JOTP services
  - to:
    - namespaceSelector:
        matchLabels:
          linkerd.io/inject: enabled
  # Allow DNS
  - to:
    - namespaceSelector: {}
    ports:
    - protocol: UDP
      port: 53
```

### Server Authorization Policies

```yaml
# server-authentication-policy.yaml
apiVersion: policy.linkerd.io/v1beta1
kind: Server
metadata:
  name: jotp-telemetry
  namespace: jotp-system
spec:
  podSelector:
    matchLabels:
      app: jotp-telemetry
  port: http
  proxyProtocol: HTTP/1
  # Only allow authenticated requests from Linkerd mesh
---
apiVersion: policy.linkerd.io/v1beta1
kind: ServerAuthorization
metadata:
  name: jotp-telemetry-authz
  namespace: jotp-system
spec:
  server:
    name: jotp-telemetry
  # Require mTLS from JOTP services
  clientAuthentication:
    mTLS:
      unauthenticated: false
      cacheEnabled: true
  # Allow health checks from all sources
  rules:
  - clientAuthentication:
      mTLS:
        unauthenticated: true  # Allow health checks
  - clientAuthentication:
      mTLS:
        unauthenticated: false
        identities:
        - "cluster.local/ns/jotp-system/sa/jotp-telemetry"
```

---

## Production Examples

### Example 1: Multi-Cluster JOTP Deployment

```bash
# Install multicluster extension
linkerd multicluster install | kubectl apply -f -

# Link clusters
linkerd multicluster link --cluster-name us-east \
  --gateway-name jotp-gateway-us-east

linkerd multicluster link --cluster-name us-west \
  --gateway-name jotp-gateway-us-west

# Mirror service across clusters
kubectl apply -f - <<EOF
apiVersion: split.smi-spec.io/v1alpha2
kind: TrafficSplit
metadata:
  name: jotp-telemetry-multicluster
  namespace: jotp-system
spec:
  service: jotp-telemetry
  backends:
  - service: jotp-telemetry
    weight: 50
    cluster: us-east
  - service: jotp-telemetry
    weight: 50
    cluster: us-west
EOF
```

### Example 2: JOTP with Automatic Retry

```yaml
# retry-policy.yaml
apiVersion: linkerd.io/v1alpha2
kind: ServiceProfile
metadata:
  name: jotp-retry-policy
  namespace: jotp-system
spec:
  routes:
  - name: ProcessMessage
    condition:
      method: POST
      pathRegex: "/api/v1/process"
    # Automatic retry on JOTP process restart
    isRetryable: true
    retries:
      limit: 3
      initialInterval: 100ms
      maxInterval: 1s
      # Only retry on specific errors
      retryableStatuses:
      - 503  # Service unavailable (process restart)
      - 504  # Gateway timeout (process timeout)
      - 408  # Request timeout
```

### Example 3: JOTP with Fault Injection

```yaml
# chaos-testing.yaml
apiVersion: policy.linkerd.io/v1alpha2
kind: HTTPRetryFilter
metadata:
  name: jotp-fault-injection
  namespace: jotp-system
spec:
  # Inject 10% latency
  delay:
    percentage: 10
    duration: 500ms
  # Apply to specific routes
  routes:
  - name: ProcessTelemetry
  - name: SendMessage
```

### Example 4: Complete JOTP Stack

```yaml
# complete-stack.yaml
apiVersion: v1
kind: Namespace
metadata:
  name: jotp-system
  annotations:
    linkerd.io/inject: enabled
---
# JOTP telemetry service
apiVersion: apps/v1
kind: Deployment
metadata:
  name: jotp-telemetry
  namespace: jotp-system
spec:
  replicas: 3
  selector:
    matchLabels:
      app: jotp-telemetry
  template:
    metadata:
      labels:
        app: jotp-telemetry
      annotations:
        linkerd.io/inject: enabled
        config.linkerd.io/proxy-throw-resource-overload: "true"
    spec:
      containers:
      - name: jotp
        image: jotp/telemetry-service:1.0.0
        ports:
        - containerPort: 8080
          name: http
        - containerPort: 9090
          name: grpc
        env:
        - name: JAVA_OPTS
          value: "-XX:MaxRAMPercentage=75.0"
        envFrom:
        - secretRef:
            name: jotp-secrets
        resources:
          requests:
            memory: "512Mi"
            cpu: "500m"
          limits:
            memory: "2Gi"
            cpu: "2000m"
        readinessProbe:
          httpGet:
            path: /health/ready
            port: 8080
          initialDelaySeconds: 10
          periodSeconds: 5
        livenessProbe:
          httpGet:
            path: /health/live
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
---
# Service profile
apiVersion: linkerd.io/v1alpha2
kind: ServiceProfile
metadata:
  name: jotp-telemetry
  namespace: jotp-system
spec:
  routes:
  - name: ProcessTelemetry
    condition:
      method: POST
      pathRegex: "/api/v1/telemetry/process"
    timeout: 30s
    isRetryable: true
    retries:
      limit: 3
      initialInterval: 100ms
      maxInterval: 1s
---
# Traffic split for canary
apiVersion: split.smi-spec.io/v1alpha2
kind: TrafficSplit
metadata:
  name: jotp-telemetry-canary
  namespace: jotp-system
spec:
  service: jotp-telemetry
  backends:
  - service: jotp-telemetry
    weight: 95
  - service: jotp-telemetry-canary
    weight: 5
---
# Server authorization
apiVersion: policy.linkerd.io/v1beta1
kind: ServerAuthorization
metadata:
  name: jotp-telemetry-authz
  namespace: jotp-system
spec:
  server:
    name: jotp-telemetry
  clientAuthentication:
    mTLS:
      unauthenticated: false
      cacheEnabled: true
      identities:
      - "cluster.local/ns/jotp-system/sa/jotp-telemetry"
```

---

## Best Practices

### 1. Resource Allocation

```yaml
resources:
  requests:
    memory: "512Mi"    # JOTP: 400Mi + Linkerd: 112Mi
    cpu: "500m"        # JOTP: 400m + Linkerd: 100m
  limits:
    memory: "2Gi"      # JOTP: 1.5Gi + Linkerd: 512Mi
    cpu: "2000m"       # JOTP: 1800m + Linkerd: 200m
```

### 2. Proxy Configuration

```yaml
annotations:
  # Reduce proxy memory usage
  config.linkerd.io/proxy-memory-limit: "512Mi"
  config.linkerd.io/proxy-cpu-limit: "500m"

  # Disable unnecessary features
  config.linkerd.io/proxy-disable-external-profiles: "true"

  # Enable proxy metrics
  config.linkerd.io/proxy-export-metrics: "true"
```

### 3. Timeout Configuration

```yaml
# Set appropriate timeouts for JOTP operations
spec:
  routes:
  - name: LongRunningProcess
    timeout: 5m  # For long-running JOTP processes
  - name: QuickQuery
    timeout: 2s  # For fast queries
```

### 4. Retry Strategy

```yaml
# Configure retries based on JOTP error types
retries:
  limit: 3
  initialInterval: 100ms
  maxInterval: 1s
  retryableStatuses:
  - 503  # Service unavailable (process restart)
  - 504  # Gateway timeout (process timeout)
  - 408  # Request timeout
```

---

## Troubleshooting

### Common Issues

**1. Proxy Not Injected:**
```bash
# Check namespace annotation
kubectl get namespace jotp-system -o yaml | grep inject

# Verify proxy status
linkerd -n jotp-system check --proxy

# Manual injection
kubectl get deployment jotp-telemetry -n jotp-system -o yaml \
  | linkerd inject - \
  | kubectl apply -f -
```

**2. mTLS Connection Issues:**
```bash
# Check mTLS status
linkerd -n jotp-system check --proxy --tls

# View identity certificates
kubectl describe secret -n jotp-system \
  $(kubectl get secrets -n jotp-system \
    -l linkerd.io/control-plane-ns=linkerd \
    -o jsonpath='{.items[0].metadata.name}')

# Verify TLS version
kubectl exec -n jotp-system deploy/jotp-telemetry -c linkerd-proxy -- \
  /usr/bin/curl -s localhost:4191/metrics | grep tls_version
```

**3. High Latency:**
```bash
# Check proxy resource usage
kubectl top pod -n jotp-system -l app=jotp-telemetry --containers

# Increase proxy resources
kubectl annotate deployment jotp-telemetry \
  config.linkerd.io/proxy-cpu-limit="1000m" \
  config.linkerd.io/proxy-memory-limit="1Gi" \
  -n jotp-system
```

**4. Metrics Not Appearing:**
```bash
# Check metrics endpoint
kubectl exec -n jotp-system deploy/jotp-telemetry -c linkerd-proxy -- \
  curl -s localhost:4191/metrics | grep request

# Verify Prometheus scraping
kubectl logs -n linkerd-viz deploy/prometheus -c prometheus | grep jotp
```

---

## Comparison: Istio vs Linkerd for JOTP

| Feature | Istio | Linkerd |
|---------|-------|---------|
| **Complexity** | High | Low |
| **Resource Usage** | High (512Mi+ per proxy) | Low (128Mi per proxy) |
| **Setup Time** | 30+ minutes | 10 minutes |
| **mTLS** | Configurable | On by default |
| **Traffic Splitting** | VirtualService | TrafficSplit (SMI) |
| **Observability** | Built-in (Kiali) | Linkerd Viz |
| **Best For** | Complex routing, large orgs | Simple deployment, small teams |

**Recommendation:** Start with Linkerd for JOTP - it's simpler, lighter, and provides all essential features. Move to Istio only if you need advanced traffic management.

---

## References

- [Linkerd Documentation](https://linkerd.io/latest/docs/)
- [SMI Specification](https://smi-spec.io/)
- [JOTP Architecture](/Users/sac/jotp/docs/ARCHITECTURE.md)
- [Istio Comparison](https://linkerd.io/latest/overview/comparison/)
