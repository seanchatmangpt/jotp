# Istio Integration Guide for JOTP

**Overview:** Running JOTP applications on Istio service mesh with mTLS, traffic management, and observability.

**Prerequisites:**
- Kubernetes cluster with Istio 1.19+
- JOTP application containerized
- Basic understanding of Istio concepts

---

## Table of Contents

1. [Quick Start](#quick-start)
2. [mTLS Configuration](#mtls-configuration)
3. [Traffic Management](#traffic-management)
4. [Observability](#observability)
5. [Security](#security)
6. [Production Examples](#production-examples)

---

## Quick Start

### 1. Enable Istio Injection for JOTP Namespace

```bash
# Label namespace for automatic sidecar injection
kubectl label namespace jotp-system istio-injection=enabled

# Verify injection
kubectl get namespace -L istio-injection
```

### 2. Deploy JOTP Application

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
        version: v1
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

### 3. Apply Configuration

```bash
kubectl apply -f deployment.yaml
kubectl get pods -n jotp-system -w
```

---

## mTLS Configuration

### Strict mTLS for JOTP Services

```yaml
# peer-authentication.yaml
apiVersion: security.istio.io/v1beta1
kind: PeerAuthentication
metadata:
  name: jotp-strict-mtls
  namespace: jotp-system
spec:
  mtls:
    mode: STRICT  # Enforce mTLS for all JOTP services
---
# Per-service override (if needed)
apiVersion: security.istio.io/v1beta1
kind: PeerAuthentication
metadata:
  name: jotp-legacy-permissive
  namespace: jotp-system
spec:
  selector:
    matchLabels:
      app: jotp-legacy-service
  mtls:
    mode: PERMISSIVE  # Allow both mTLS and plain text
```

### mTLS between JOTP Nodes

**JOTP Process Communication with mTLS:**

```java
// JOTP automatically benefits from Istio mTLS
// No code changes required - Istio sidecar handles encryption

// Example: Process-to-process communication
Proc<String, TelemetryMessage> sender = Proc.create(
    "telemetry-sender",
    initialSenderState,
    handler
);

// Messages between JOTP nodes are automatically encrypted by Istio
ProcRef<String, TelemetryMessage> remoteReceiver =
    ProcRef.register("telemetry-receiver");

// This message is encrypted at wire level by Istio
sender.tell(new TelemetryMessage("metrics-data", payload));
```

### Verify mTLS Status

```bash
# Check mTLS configuration
kubectl get peerauthentication -n jotp-system

# Verify mTLS in effect
istioctl authn tls-check jotp-telemetry.jotp-system.svc.cluster.local

# Test mTLS connectivity
kubectl exec -it -n jotp-system deploy/jotp-telemetry -c jotp -- \
  curl https://jotp-telemetry.jotp-system.svc.cluster.local:8080/health/ready -k
```

---

## Traffic Management

### VirtualService for Routing

```yaml
# virtualservice.yaml
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: jotp-telemetry
  namespace: jotp-system
spec:
  hosts:
  - jotp-telemetry
  http:
  # Route gRPC traffic
  - match:
    - headers:
        content-type:
          regex: "application/grpc.*"
    route:
    - destination:
        host: jotp-telemetry
        subset: v1
      weight: 100
    timeout: 30s
    retries:
      attempts: 3
      perTryTimeout: 10s
      retryOn: 5xx,reset,connect-failure,refused-stream

  # Route HTTP traffic
  - match:
    - uri:
        prefix: /api/
    route:
    - destination:
        host: jotp-telemetry
        subset: v1
      weight: 100
    fault:
      delay:
        percentage:
          value: 0.1  # 0.1% delay injection
        fixedDelay: 100ms
    timeout: 10s
```

### DestinationRule for Load Balancing

```yaml
# destinationrule.yaml
apiVersion: networking.istio.io/v1beta1
kind: DestinationRule
metadata:
  name: jotp-telemetry
  namespace: jotp-system
spec:
  host: jotp-telemetry
  trafficPolicy:
    loadBalancer:
      simple: LEAST_CONN  # Suitable for JOTP's process-based workload
    connectionPool:
      tcp:
        maxConnections: 100
        connectTimeout: 30ms
        tcpKeepalive:
          time: 7200s
          interval: 75s
      http:
        http2MaxRequests: 1000
        http2InitialWindowSize: 65536  # 64KB for gRPC streaming
        idleTimeout: 300s
        h2UpgradePolicy: UPGRADE  # Force HTTP/2 for gRPC
    outlierDetection:
      consecutiveGatewayErrors: 5
      interval: 30s
      baseEjectionTime: 30s
      maxEjectionPercent: 50
      minHealthPercent: 40
  subsets:
  - name: v1
    labels:
      version: v1
```

### Canary Deployment Strategy

```yaml
# canary-virtualservice.yaml
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: jotp-telemetry-canary
  namespace: jotp-system
spec:
  hosts:
  - jotp-telemetry
  http:
  - match:
    - headers:
        x-canary:
          exact: "true"
    route:
    - destination:
        host: jotp-telemetry
        subset: v2  # Canary version
      weight: 100

  # Production traffic: 95% v1, 5% v2
  - route:
    - destination:
        host: jotp-telemetry
        subset: v1
      weight: 95
    - destination:
        host: jotp-telemetry
        subset: v2
      weight: 5
```

### Blue-Green Deployment

```yaml
# blue-green-virtualservice.yaml
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: jotp-telemetry-bluegreen
  namespace: jotp-system
spec:
  hosts:
  - jotp-telemetry
  http:
  - match:
    - headers:
        x-environment:
          exact: "blue"
    route:
    - destination:
        host: jotp-telemetry
        subset: blue
      weight: 100

  - match:
    - headers:
        x-environment:
          exact: "green"
    route:
    - destination:
        host: jotp-telemetry
        subset: green
      weight: 100

  # Default to blue
  - route:
    - destination:
        host: jotp-telemetry
        subset: blue
      weight: 100
```

### Fault Injection Testing

```yaml
# fault-injection.yaml
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: jotp-fault-injection
  namespace: jotp-system
spec:
  hosts:
  - jotp-telemetry
  http:
  # Test JOTP supervisor restart behavior
  - match:
    - headers:
        x-chaos-test:
          exact: "crash-injection"
    fault:
      abort:
        percentage:
          value: 10  # 10% of requests
        httpStatus: 503
    route:
    - destination:
        host: jotp-telemetry
        subset: v1

  # Test timeout handling
  - match:
    - headers:
        x-chaos-test:
          exact: "delay-injection"
    fault:
      delay:
        percentage:
          value: 50  # 50% of requests
        fixedDelay: 5s  # Trigger JOTP timeout
    route:
    - destination:
        host: jotp-telemetry
        subset: v1
```

---

## Observability

### Distributed Tracing with Jaeger

**Automatic Trace Propagation:**

```java
// JOTP integrates with Istio's distributed tracing automatically
// Istio sidecar injects trace headers into all HTTP/gRPC requests

// Enable DistributedTracer in JOTP
DistributedTracer tracer = DistributedTracer.create("jotp-telemetry");

// JOTP processes automatically pick up trace context from Istio
Proc<String, TelemetryMessage> proc = Proc.create(
    "telemetry-processor",
    initialState,
    (state, msg) -> {
        // Span is automatically created with parent context from Istio
        Span span = tracer.spanBuilder("process-telemetry")
            .setParent(tracer.getCurrentContext().orElse(null))
            .setAttribute("message.type", msg.type())
            .startSpan();

        try (var scope = span.makeCurrent()) {
            span.addEvent("processing-start");
            var result = processMessage(msg);
            span.addEvent("processing-complete");
            span.setStatus(Span.StatusCode.OK);
            return result;
        } catch (Exception e) {
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }
);
```

### Metrics Integration

**JOTP Metrics + Prometheus + Istio:**

```yaml
# servicemonitor.yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: jotp-telemetry
  namespace: jotp-system
  labels:
    release: prometheus
spec:
  selector:
    matchLabels:
      app: jotp-telemetry
  endpoints:
  - port: http
    path: /metrics
    interval: 15s
    scrapeTimeout: 10s
```

**Expose JOTP ProcessMetrics:**

```java
// Expose ProcessMetrics as Prometheus endpoint
public class MetricsEndpoint {
    private final ProcessMetrics metrics;

    @GET
    @Path("/metrics")
    @Produces(MediaType.TEXT_PLAIN)
    public String getMetrics() {
        ProcessMetricsSnapshot snapshot = metrics.snapshot();

        return """
            # HELP jotp_processes_created Total processes created
            # TYPE jotp_processes_created counter
            jotp_processes_created %d

            # HELP jotp_processes_crashed Total process crashes
            # TYPE jotp_processes_crashed counter
            jotp_processes_crashed %d

            # HELP jotp_messages_sent Total messages sent
            # TYPE jotp_messages_sent counter
            jotp_messages_sent %d

            # HELP jotp_queue_depth Current queue depth
            # TYPE jotp_queue_depth gauge
            jotp_queue_depth %d

            # HELP jotp_crash_rate Crash rate percentage
            # TYPE jotp_crash_rate gauge
            jotp_crash_rate %.2f
            """.formatted(
            snapshot.processesCreated(),
            snapshot.processesCrashed(),
            snapshot.messagesSent(),
            snapshot.currentQueueDepth(),
            snapshot.crashRate()
        );
    }
}
```

### Kiali Dashboard Configuration

```yaml
# kiali-dashboard.yaml
apiVersion: kiali.io/v1alpha1
kind: Dashboard
metadata:
  name: jotp-process-metrics
  namespace: jotp-system
spec:
  title: JOTP Process Metrics
  items:
  - chart:
      name: "Process Crash Rate"
      unit: "%"
      spans: 4
      metricName: "jotp_crash_rate"
      dataType: "raw"
  - chart:
      name: "Message Throughput"
      unit: "msg/s"
      spans: 4
      metricName: "rate(jotp_messages_sent[1m])"
      dataType: "rate"
  - chart:
      name: "Queue Depth"
      unit: "messages"
      spans: 4
      metricName: "jotp_queue_depth"
      dataType: "raw"
```

### Grafana Dashboard Queries

```promql
# JOTP Process Health Panel
rate(jotp_processes_crashed[5m]) > 0.01

# Message Latency (from Istio metrics)
histogram_quantile(0.95,
  sum(rate(istio_request_duration_milliseconds_bucket{
    destination_service_name="jotp-telemetry"
  }[5m])) by (le)
)

# Service-to-Service Communication
sum(rate(istio_requests_total{
  source_service_name=~"jotp-.*",
  destination_service_name=~"jotp-.*"
}[5m])) by (source_service_name, destination_service_name)
```

---

## Security

### Authorization Policies

```yaml
# authorization-policy.yaml
apiVersion: security.istio.io/v1beta1
kind: AuthorizationPolicy
metadata:
  name: jotp-telemetry-policy
  namespace: jotp-system
spec:
  selector:
    matchLabels:
      app: jotp-telemetry
  action: ALLOW
  rules:
  # Allow health checks from Istio
  - from:
    - source:
        namespaces: ["istio-system"]
    to:
    - operation:
        paths: ["/health/ready", "/health/live"]

  # Allow gRPC from JOTP services only
  - from:
    - source:
        namespaces: ["jotp-system"]
        principals: ["cluster.local/ns/jotp-system/sa/jotp-telemetry"]
    to:
    - operation:
        ports: ["9090"]

  # Deny all other requests
  - when:
    - key: source.namespace
      notValues: ["jotp-system", "istio-system"]
```

### Network Policies

```yaml
# network-policy.yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: jotp-network-policy
  namespace: jotp-system
spec:
  podSelector:
    matchLabels:
      app: jotp-telemetry
  policyTypes:
  - Ingress
  - Egress
  ingress:
  # Allow from Istio sidecar
  - from:
    - namespaceSelector:
        matchLabels:
          istio-injection: enabled
    ports:
    - protocol: TCP
      port: 8080
    - protocol: TCP
      port: 9090
  egress:
  # Allow to Istio control plane
  - to:
    - namespaceSelector:
        matchLabels:
          name: istio-system
    ports:
    - protocol: TCP
      port: 15012  # Citadel
    - protocol: TCP
      port: 15014  # Mixer

  # Allow to other JOTP services
  - to:
    - namespaceSelector:
        matchLabels:
          name: jotp-system
    ports:
    - protocol: TCP
      port: 8080
    - protocol: TCP
      port: 9090

  # Allow DNS
  - to:
    - namespaceSelector: {}
    ports:
    - protocol: UDP
      port: 53
```

### Secrets Injection

```yaml
# secret.yaml
apiVersion: v1
kind: Secret
metadata:
  name: jotp-secrets
  namespace: jotp-system
type: Opaque
stringData:
  database-url: "postgresql://jotp-db:5432/telemetry"
  encryption-key: "your-encryption-key-here"
---
# envoy-secrets.yaml
apiVersion: v1
kind: Secret
metadata:
  name: jotp-istio-secrets
  namespace: jotp-system
type: istio.io/key-and-cert
data:
  cert.yaml: |
    # TLS certificate for service mesh
```

---

## Production Examples

### Example 1: Multi-Region JOTP Deployment

```yaml
# gateway.yaml
apiVersion: networking.istio.io/v1beta1
kind: Gateway
metadata:
  name: jotp-gateway
  namespace: jotp-system
spec:
  selector:
    istio: ingressgateway
  servers:
  - port:
      number: 443
      name: https
      protocol: HTTPS
    tls:
      mode: SIMPLE
      credentialName: jotp-cert
    hosts:
    - jotp.example.com
---
# multi-region-virtualservice.yaml
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: jotp-multi-region
  namespace: jotp-system
spec:
  hosts:
  - jotp.example.com
  gateways:
  - jotp-gateway
  http:
  - match:
    - headers:
        x-region:
          exact: "us-west"
    route:
    - destination:
        host: jotp-telemetry
        subset: us-west
      weight: 100

  - match:
    - headers:
        x-region:
          exact: "us-east"
    route:
    - destination:
        host: jotp-telemetry
        subset: us-east
      weight: 100

  # Default: geo-based routing
  - route:
    - destination:
        host: jotp-telemetry
        subset: us-east
      weight: 50
    - destination:
        host: jotp-telemetry
        subset: us-west
      weight: 50
```

### Example 2: JOTP Service Mesh with Circuit Breaking

```yaml
# circuit-breaker-destinationrule.yaml
apiVersion: networking.istio.io/v1beta1
kind: DestinationRule
metadata:
  name: jotp-circuit-breaker
  namespace: jotp-system
spec:
  host: jotp-telemetry
  trafficPolicy:
    connectionPool:
      tcp:
        maxConnections: 50
      http:
        http1MaxPendingRequests: 10
        http2MaxRequests: 100
        maxRequestsPerConnection: 2
    outlierDetection:
      consecutiveErrors: 7
      interval: 30s
      baseEjectionTime: 30s
      maxEjectionPercent: 50
      minHealthPercent: 30
    # Circuit breaking thresholds
    outlierDetection:
      consecutiveGatewayErrors: 5
      consecutive5xxErrors: 5
      interval: 10s
      baseEjectionTime: 30s
      maxEjectionPercent: 50
      enforcingPercentage: 100
```

### Example 3: JOTP Observability Stack

```yaml
# observability-stack.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: jotp-observability-config
  namespace: jotp-system
data:
  prometheus.yaml: |
    global:
      scrape_interval: 15s
      evaluation_interval: 15s

    scrape_configs:
    - job_name: 'jotp-processes'
      kubernetes_sd_configs:
      - role: pod
        namespaces:
          names:
          - jotp-system
      relabel_configs:
      - source_labels: [__meta_kubernetes_pod_label_app]
        action: keep
        regex: jotp-.*
      - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_scrape]
        action: keep
        regex: true
      - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_path]
        action: replace
        target_label: __metrics_path__
        regex: (.+)

  grafana-dashboard.json: |
    {
      "dashboard": {
        "title": "JOTP Process Monitoring",
        "panels": [
          {
            "title": "Process Crash Rate",
            "targets": [
              {
                "expr": "rate(jotp_processes_crashed[5m])"
              }
            ]
          },
          {
            "title": "Message Throughput",
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

## Best Practices

### 1. Resource Limits for JOTP + Istio

```yaml
resources:
  requests:
    memory: "512Mi"    # JOTP: 400Mi + Istio: 112Mi
    cpu: "500m"        # JOTP: 400m + Istio: 100m
  limits:
    memory: "2Gi"      # JOTP: 1.5Gi + Istio: 512Mi
    cpu: "2000m"       # JOTP: 1800m + Istio: 200m
```

### 2. Health Check Optimization

```yaml
readinessProbe:
  httpGet:
    path: /health/ready
    port: 8080
  initialDelaySeconds: 10
  periodSeconds: 3        # Faster detection
  timeoutSeconds: 2
  successThreshold: 1
  failureThreshold: 3
```

### 3. gRPC Configuration for JOTP

```yaml
# Ensure HTTP/2 for gRPC
trafficPolicy:
  connectionPool:
    http:
      h2UpgradePolicy: UPGRADE
      http2MaxRequests: 1000
```

### 4. Trace Sampling in Production

```yaml
# istio-config.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: istio
  namespace: istio-system
data:
  mesh: |
    enableTracing: true
    defaultConfig:
      tracing:
        sampling: 10.0  # 10% sampling in production
        custom_tags:
          jotp_process_id:
            environment:
              name: JOTP_PROCESS_ID
              defaultValue: "unknown"
```

---

## Troubleshooting

### Common Issues

**1. Sidecar Not Injected:**
```bash
# Check namespace label
kubectl get namespace -L istio-injection

# Manual injection for testing
istioctl kube-inject -f deployment.yaml | kubectl apply -f -
```

**2. mTLS Connection Issues:**
```bash
# Verify mTLS status
istioctl authn tls-check jotp-telemetry.jotp-system.svc.cluster.local

# Check peer authentication
kubectl get peerauthentication -n jotp-system
```

**3. High Memory Usage:**
```bash
# Check sidecar resources
kubectl top pod -n jotp-system -l app=jotp-telemetry --containers

# Adjust Istio proxy resources
kubectl patch deployment jotp-telemetry -n jotp-system -p '
{
  "spec": {
    "template": {
      "spec": {
        "containers": [{
          "name": "istio-proxy",
          "resources": {
            "requests": {"memory": "128Mi", "cpu": "100m"},
            "limits": {"memory": "256Mi", "cpu": "500m"}
          }
        }]
      }
    }
  }
}'
```

**4. Trace Context Not Propagating:**
```bash
# Verify DistributedTracer configuration
kubectl logs -n jotp-system deploy/jotp-telemetry -c jotp | grep tracer

# Check Istio trace sampling
kubectl get configmap istio -n istio-system -o yaml | grep sampling
```

---

## References

- [Istio Documentation](https://istio.io/latest/docs/)
- [JOTP Architecture](/Users/sac/jotp/docs/ARCHITECTURE.md)
- [Distributed Tracing Guide](/Users/sac/jotp/docs/distributed/OBSERVABILITY-MESH.md)
- [Kubernetes Network Policies](https://kubernetes.io/docs/concepts/services-networking/network-policies/)
