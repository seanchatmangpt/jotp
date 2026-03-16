# JOTP Helm Chart - Quick Start Guide

Get JOTP running on Kubernetes in minutes with this quick start guide.

## Prerequisites

- Kubernetes 1.25+ cluster
- kubectl configured
- Helm 3.0+ installed

## 5-Minute Deployment

### 1. Clone/Navigate to JOTP

```bash
cd /Users/sac/jotp
```

### 2. Deploy to Development

```bash
./helm/jotp/scripts/deploy.sh ./helm/jotp jotp-dev jotp-dev dev
```

That's it! JOTP is now deployed.

### 3. Verify Deployment

```bash
# Check pods
kubectl get pods -n jotp-dev

# Port forward to access
kubectl port-forward -n jotp-dev svc/jotp-dev 8080:8080

# Test health endpoint
curl http://localhost:8080/health/ready
```

## Production Deployment

### 1. Generate Secure Cookie

```bash
export JOTP_COOKIE=$(openssl rand -base64 32)
```

### 2. Deploy to Production

```bash
helm install jotp-prod ./helm/jotp \
  -f helm/jotp/values-prod.yaml \
  -n jotp-prod \
  --create-namespace \
  --set jotp.cookie="$JOTP_COOKIE"
```

### 3. Enable Monitoring

```bash
# If you have Prometheus Operator:
helm upgrade jotp-prod ./helm/jotp \
  -f helm/jotp/values-prod.yaml \
  -n jotp-prod \
  --set monitoring.serviceMonitor.enabled=true
```

## Common Commands

### Check Status

```bash
helm status jotp-dev -n jotp-dev
kubectl get all -n jotp-dev
```

### View Logs

```bash
kubectl logs -n jotp-dev -l app.kubernetes.io/name=jotp -f
```

### Scale Up/Down

```bash
kubectl scale deployment jotp-dev -n jotp-dev --replicas=5
```

### Uninstall

```bash
helm uninstall jotp-dev -n jotp-dev
kubectl delete namespace jotp-dev
```

## Configuration Cheat Sheet

### Set Process Capacity

```bash
# 100K processes (2GB heap)
helm install jotp ./helm/jotp --set jvm.xmx=2G --set jotp.maxProcesses=100000

# 1M processes (8GB heap)
helm install jotp ./helm/jotp -f helm/jotp/values-1m-processes.yaml

# 10M processes (32GB heap)
helm install jotp ./helm/jotp -f helm/jotp/values-10m-processes.yaml
```

### Enable Ingress

```bash
helm install jotp ./helm/jotp \
  --set ingress.enabled=true \
  --set ingress.hosts[0].host=jotp.example.com \
  --set ingress.tls[0].secretName=jotp-tls
```

### Enable Autoscaling

```bash
helm install jotp ./helm/jotp \
  --set autoscaling.enabled=true \
  --set autoscaling.minReplicas=3 \
  --set autoscaling.maxReplicas=10
```

## Troubleshooting

### Pods Not Ready

```bash
kubectl describe pod -n jotp-dev <pod-name>
kubectl logs -n jotp-dev <pod-name>
```

### Connection Issues

```bash
# Check services
kubectl get svc -n jotp-dev

# Test from pod
kubectl exec -n jotp-dev <pod-name> -- curl http://localhost:8080/health/ready
```

### Resource Issues

```bash
# Check resource usage
kubectl top pods -n jotp-dev
kubectl top nodes
```

## Next Steps

1. Read [README.md](./README.md) for detailed configuration options
2. See [INSTALL.md](./INSTALL.md) for comprehensive installation guide
3. Review [examples/](./examples/) for backup and monitoring configurations
4. Check [DELIVERABLES.md](./DELIVERABLES.md) for complete feature list

## Support

- Documentation: https://jotp.io/docs
- GitHub: https://github.com/seanchatmangpt/jotp
- Issues: https://github.com/seanchatmangpt/jotp/issues
