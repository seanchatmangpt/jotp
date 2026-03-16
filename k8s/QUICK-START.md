# JOTP Kubernetes Quick Start

## 5-Minute Deployment

```bash
# 1. Build the Docker image
docker build -t jotp:1.0 .

# 2. Deploy to Kubernetes
cd k8s
./deploy.sh

# 3. Verify deployment
kubectl get pods -l app=jotp
kubectl logs -f statefulset/jotp

# 4. Access the application
kubectl port-forward svc/jotp 8080:8080
curl http://localhost:8080/actuator/health
```

## Common Commands

### Scaling
```bash
# Scale to 5 nodes
kubectl scale statefulset jotp --replicas=5

# Watch pods come up
kubectl get pods -l app=jotp -w
```

### Debugging
```bash
# Check pod logs
kubectl logs jotp-0

# Exec into a pod
kubectl exec -it jotp-0 -- bash

# Check peer connectivity
kubectl exec jotp-0 -- nslookup jotp-headless.default.svc.cluster.local

# Port-forward for debugging
kubectl port-forward jotp-0 5005:5005  # Java debug port
kubectl port-forward jotp-0 9091:9091  # Admin port
```

### Monitoring
```bash
# Check resource usage
kubectl top pod -l app=jotp

# Get metrics
kubectl port-forward svc/jotp-monitoring 9090:9090
curl http://localhost:9090/metrics

# Check health
kubectl exec jotp-0 -- curl http://localhost:9091/actuator/health
```

### Updates
```bash
# Rolling restart
kubectl rollout restart statefulset/jotp

# Check rollout status
kubectl rollout status statefulset/jotp

# Rollback
kubectl rollout undo statefulset/jotp
```

## Troubleshooting

### Pods not starting
```bash
# Describe pod
kubectl describe pod jotp-0

# Check events
kubectl get events --sort-by='.lastTimestamp'

# Check init container logs
kubectl logs jotp-0 -c wait-for-peers
```

### Peer discovery issues
```bash
# Check DNS records
kubectl run -it --rm debug --image=busybox:1.36 --restart=Never -- nslookup jotp-headless

# Test connectivity between pods
kubectl exec jotp-0 -- nc -zv jotp-1.jotp-headless 50051
```

### Memory issues
```bash
# Check current usage
kubectl top pod jotp-0

# Adjust heap size
kubectl edit configmap jotp-config
# Change: -Xmx4g → -Xmx6g

# Restart to apply
kubectl rollout restart statefulset jotp
```

## Architecture Overview

```
Internet → Ingress → Service (jotp) → StatefulSet (jotp-0, jotp-1, jotp-2)
                                       ↓
                                   Headless Service
                                   (peer discovery)
```

- **3 replicas** (default) for high availability
- **Headless service** for peer-to-peer gRPC communication
- **4GB heap** per pod (supports ~1M processes)
- **ZGC garbage collector** for low pause times
- **Health probes** for automatic restart on failure

## Next Steps

- Read [README.md](README.md) for detailed documentation
- Configure [Prometheus](examples/prometheus-rules.yaml) for alerting
- Set up [Grafana dashboard](examples/grafana-dashboard.json) for visualization
- Review [production checklist](README.md#production-checklist)

## Support

- **Documentation**: [k8s/README.md](README.md)
- **Issues**: [GitHub Issues](https://github.com/seanchatmangpt/jotp/issues)
- **Performance Guide**: [../docs/validation/performance/honest-performance-claims.md](../docs/validation/performance/honest-performance-claims.md)
