# JOTP Kubernetes Deployment Checklist

## Pre-Deployment Checklist

### Cluster Requirements
- [ ] Kubernetes version 1.25+ installed
- [ ] kubectl configured and connected to cluster
- [ ] Sufficient node capacity (minimum 3 nodes with 8GB RAM each)
- [ ] StorageClass available (or use default)
- [ ] Network policies allow pod-to-pod communication
- [ ] Load balancer / ingress controller installed (if external access needed)

### Application Preparation
- [ ] Docker image built: `docker build -t jotp:1.0 .`
- [ ] Image pushed to registry: `docker push your-registry/jotp:1.0`
- [ ] Image pull secret configured (if using private registry)
- [ ] Environment variables configured in ConfigMap
- [ ] JVM options tuned for target process count
- [ ] Health check endpoints configured in application

### Configuration Review
- [ ] Replicas set to 3 (or desired cluster size)
- [ ] Resource requests/limits appropriate for workload
- [ ] Heap size configured with 25% headroom
- [ ] Storage class selected for PVCs
- [ ] Pod anti-affinity configured for HA
- [ ] RBAC permissions configured

## Deployment Steps

### 1. Namespace Setup
```bash
kubectl create namespace jotp
```

### 2. Deploy RBAC
```bash
kubectl apply -f k8s/serviceaccount.yaml -n jotp
```

### 3. Deploy Configuration
```bash
kubectl apply -f k8s/configmap.yaml -n jotp
```

### 4. Deploy Storage (if needed)
```bash
kubectl apply -f k8s/StorageClass.yaml -n jotp
```

### 5. Deploy Services
```bash
kubectl apply -f k8s/service.yaml -n jotp
```

### 6. Deploy PodDisruptionBudget
```bash
kubectl apply -f k8s/PodDisruptionBudget.yaml -n jotp
```

### 7. Deploy StatefulSet
```bash
kubectl apply -f k8s/statefulset.yaml -n jotp
```

### 8. Verify Deployment
```bash
kubectl get all -n jotp -l app=jotp
kubectl get pods -n jotp -w
```

## Post-Deployment Verification

### Health Checks
- [ ] All pods in `Running` state
- [ ] All pods ready (2/2 containers)
- [ ] No pods restarting frequently
- [ ] Liveness probe passing
- [ ] Readiness probe passing
- [ ] Startup probe passed

### Peer Discovery
- [ ] DNS records resolve for all pods
- [ ] Pods can communicate via gRPC
- [ ] Leader election completed
- [ ] No connection errors in logs

### Functionality Tests
- [ ] Create test process via HTTP API
- [ ] Send message between processes
- [ ] Verify process state persistence
- [ ] Test supervisor restart
- [ ] Test leader failover

### Performance Validation
- [ ] Heap usage < 80% of max
- [ ] GC pauses < 100ms (p99)
- [ ] Message latency < 1ms (p99)
- [ ] CPU usage within limits
- [ ] Network throughput as expected

## Monitoring Setup

### Metrics Collection
- [ ] Prometheus scraping endpoints configured
- [ ] ServiceMonitor created (if using Prometheus Operator)
- [ ] Metrics visible in Prometheus UI
- [ ] Alert rules configured

### Alerting Configuration
- [ ] Pod down alerts configured
- [ ] High memory alerts configured
- [ ] High latency alerts configured
- [ ] Supervisor restart alerts configured
- [ ] Leader election alerts configured
- [ ] Alert notifications configured (Slack, email, etc.)

### Logging
- [ ] Log aggregation configured (ELK, Loki, etc.)
- [ ] Log retention policy set
- [ ] Log queries working
- [ ] Error logs visible

### Dashboards
- [ ] Grafana dashboard imported
- [ ] Dashboard showing correct data
- [ ] Panels configured and working
- [ ] Alerts integrated with dashboard

## Scaling Testing

### Scale Up Test
- [ ] Scale from 3 to 5 nodes: `kubectl scale statefulset jotp --replicas=5`
- [ ] Verify new pods join cluster
- [ ] Verify leader election completes
- [ ] Verify load distribution
- [ ] No message loss during scale

### Scale Down Test
- [ ] Scale from 5 to 3 nodes: `kubectl scale statefulset jotp --replicas=3`
- [ ] Verify graceful shutdown
- [ ] Verify leader re-election
- [ ] Verify no data loss
- [ ] Verify cluster stability

### Load Testing
- [ ] Deploy load testing tool
- [ ] Run sustained load test (1M processes)
- [ ] Measure throughput (target: 3.6M+ msg/sec)
- [ ] Measure latency (target: <1µs p50, <100µs p99)
- [ ] Measure supervisor restart time (target: <1ms)
- [ ] Verify no message loss
- [ ] Verify no process crashes

## Failure Scenario Testing

### Pod Failure
- [ ] Kill pod: `kubectl delete pod jotp-0`
- [ ] Verify automatic restart
- [ ] Verify leader re-election
- [ ] Verify no message loss
- [ ] Verify recovery time < 30s

### Node Failure
- [ ] Cordone node: `kubectl cordon <node>`
- [ ] Drain node: `kubectl drain <node> --ignore-daemonsets`
- [ ] Verify pods reschedule
- [ ] Verify cluster stability
- [ ] Uncordon node when done

### Network Partition
- [ ] Simulate network partition
- [ ] Verify split-brain prevention
- [ ] Verify leader election handles partition
- [ ] Verify automatic recovery
- [ ] Verify no data corruption

### Resource Exhaustion
- [ ] Spike CPU: `kubectl run stress --image=progrium/stress --cpu=4`
- [ ] Verify throttling works
- [ ] Verify no crashes
- [ ] Verify recovery when load decreases

## Production Readiness Checklist

### High Availability
- [ ] 3+ replicas deployed
- [ ] Pod anti-affinity configured
- [ ] PodDisruptionBudget configured
- [ ] Multi-AZ deployment (if available)
- [ ] Automatic pod restart configured

### Disaster Recovery
- [ ] PVCs backed up regularly
- [ ] Backup restoration tested
- [ ] DR plan documented
- [ ] RTO/RDO defined
- [ ] Failover procedure tested

### Security
- [ ] Network policies configured
- [ ] RBAC least privilege applied
- [ ] Secrets encrypted at rest
- [ ] TLS configured for external access
- [ ] Security scan completed
- [ ] Vulnerability scan passed

### Performance
- [ ] Load testing completed
- [ ] Performance baselines established
- [ ] SLOs defined and measured
- [ ] Capacity planning completed
- [ ] Autoscaling configured (if needed)

### Documentation
- [ ] Runbooks documented
- [ ] Onboarding guide created
- [ ] Architecture diagrams updated
- [ ] Configuration documented
- [ ] Troubleshooting guide created

### Observability
- [ ] Metrics collection working
- [ ] Alerting configured and tested
- [ ] Log aggregation working
- [ ] Distributed tracing configured
- [ ] Dashboards created and shared

## Go-Live Checklist

### Final Verification
- [ ] All checklists completed
- [ ] Stakeholder sign-off obtained
- [ ] Deployment window scheduled
- [ ] Rollback plan documented
- [ ] Communication plan prepared

### Deployment Execution
- [ ] Pre-deployment checklist verified
- [ ] Deployment executed
- [ ] Smoke tests passed
- [ ] Monitoring verified
- [ ] Stakeholders notified

### Post-Deployment
- [ ] Monitor for 24 hours
- [ ] Address any issues promptly
- [ ] Document lessons learned
- [ ] Update runbooks
- [ ] Celebrate success! 🎉

## Support Contacts

- **On-Call Engineer**: [Name, Contact]
- **Engineering Lead**: [Name, Contact]
- **Product Owner**: [Name, Contact]
- **Escalation Path**: [Document escalation process]

## Related Documents

- [Deployment Guide](README.md)
- [Quick Start](QUICK-START.md)
- [Performance Claims](../docs/validation/performance/honest-performance-claims.md)
- [Architecture Documentation](../docs/architecture/)
