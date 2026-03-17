# JOTP Kubernetes Operations Quick Reference

**Last Updated:** 2025-03-17
**Version:** 1.0.0

---

## 🚨 Critical Commands

### Immediate Actions

```bash
# Check cluster health
kubectl get pods -n jotp-prod -l app=jotp
kubectl top pod -n jotp-prod -l app=jotp

# Kill a stuck pod
kubectl delete pod jotp-0 -n jotp-prod --grace-period=0 --force

# Emergency scale down
kubectl scale statefulset jotp --replicas=1 -n jotp-prod

# Emergency scale up
kubectl scale statefulset jotp --replicas=5 -n jotp-prod

# Check logs for errors
kubectl logs jotp-0 -n jotp-prod --tail=100 | grep -i error

# Port-forward for debugging
kubectl port-forward jotp-0 9091:9091 -n jotp-prod
```

---

## 📊 Monitoring

### Health Checks

```bash
# Overall health
kubectl exec jotp-0 -n jotp-prod -- curl -s http://localhost:9091/actuator/health | jq .

# Process count
kubectl exec jotp-0 -n jotp-prod -- curl -s http://localhost:9091/actuator/metrics/jotp.process.count

# Message throughput
kubectl exec jotp-0 -n jotp-prod -- curl -s http://localhost:9091/actuator/metrics/jotp.messages.per.second

# Memory usage
kubectl exec jotp-0 -n jotp-prod -- curl -s http://localhost:9091/actuator/metrics/jvm.memory.used

# GC metrics
kubectl exec jotp-0 -n jotp-prod -- curl -s http://localhost:9091/actuator/metrics/jvm.gc.pause
```

### Resource Usage

```bash
# Real-time metrics
kubectl top pod -n jotp-prod -l app=jotp
kubectl top node

# Detailed metrics
kubectl exec jotp-0 -n jotp-prod -- jstat -gc 1

# Heap dump (if memory issues)
kubectl exec jotp-0 -n jotp-prod -- jcmd 1 GC.heap_dump /logs/heapdump.hprof

# Thread dump (if stuck)
kubectl exec jotp-0 -n jotp-prod -- jcmd 1 Thread.print > threads.txt
```

---

## 🔧 Scaling Operations

### Scale Up

```bash
# Scale to 5 replicas
kubectl scale statefulset jotp --replicas=5 -n jotp-prod

# Monitor scale-up
kubectl get pods -n jotp-prod -w

# Verify all pods ready
kubectl wait --for=condition=ready pod -l app=jotp -n jotp-prod --timeout=600s
```

**Timeline:**
- 3 → 5 nodes: ~60 seconds
- 5 → 10 nodes: ~90 seconds
- 10 → 20 nodes: ~180 seconds

### Scale Down

```bash
# Scale to 3 replicas
kubectl scale statefulset jotp --replicas=3 -n jotp-prod

# Wait for scale-down completion
sleep 30

# Verify
kubectl get pods -n jotp-prod -l app=jotp
```

---

## 🔄 Rolling Updates

### Update Image

```bash
# Update to new version
kubectl set image statefulset/jotp jotp=jotp:1.1.0 -n jotp-prod

# Monitor rollout
kubectl rollout status statefulset/jotp -n jotp-prod

# Check rollout history
kubectl rollout history statefulset/jotp -n jotp-prod
```

### Rollback

```bash
# Rollback to previous version
kubectl rollout undo statefulset/jotp -n jotp-prod

# Rollback to specific revision
kubectl rollout undo statefulset/jotp --to-revision=2 -n jotp-prod

# Verify rollback
kubectl rollout status statefulset/jotp -n jotp-prod
```

### Canary Deployment

```bash
# Update 1 pod first (partitioned rollout)
kubectl patch statefulset jotp -n jotp-prod -p '{"spec":{"updateStrategy":{"rollingUpdate":{"partition":2}}}}'

# Verify canary
kubectl get pods -n jotp-prod -l app=jotp -o jsonpath='{.items[*].spec.containers[0].image}'

# Full rollout
kubectl patch statefulset jotp -n jotp-prod -p '{"spec":{"updateStrategy":{"rollingUpdate":{"partition":0}}}}'
```

---

## 🚨 Troubleshooting

### Pods Not Starting

```bash
# Describe pod
kubectl describe pod jotp-0 -n jotp-prod

# Check events
kubectl get events -n jotp-prod --sort-by='.lastTimestamp'

# Check init container logs
kubectl logs jotp-0 -n jotp-prod -c wait-for-peers

# Common issues:
# - Image pull error → Check image name/registry
# - Resource limits → Check node capacity
# - Init container failure → Check peer DNS
```

### High Memory Usage

```bash
# Check memory usage
kubectl top pod jotp-0 -n jotp-prod

# Check heap usage
kubectl exec jotp-0 -n jotp-prod -- jmap -heap 1

# Check for memory leaks
kubectl exec jotp-0 -n jotp-prod -- jmap -histo:live 1 | head -20

# Take heap dump
kubectl exec jotp-0 -n jotp-prod -- jcmd 1 GC.heap_dump /logs/heapdump.hprof

# Copy heap dump locally
kubectl cp jotp-prod/jotp-0:/logs/heapdump.hprof heapdump.hprof

# Scale up if needed
kubectl set resources statefulset jotp -n jotp-prod \
  --limits=memory:32Gi --requests=memory:24Gi
```

### Leader Election Issues

```bash
# Check leader election logs
kubectl logs jotp-0 -n jotp-prod | grep -i election

# Check network connectivity
kubectl exec jotp-0 -n jotp-prod -- nc -zv jotp-1.jotp-headless 50051

# Force restart if stuck
kubectl delete pod jotp-0 -n jotp-prod

# Check DNS resolution
kubectl exec jotp-0 -n jotp-prod -- nslookup jotp-headless.jotp-prod.svc.cluster.local
```

### High CPU Usage

```bash
# Check CPU usage
kubectl top pod -n jotp-prod -l app=jotp

# Profile CPU
kubectl exec jotp-0 -n jotp-prod -- jcmd 1 VM.native_memory summary

# Check for thread contention
kubectl exec jotp-0 -n jotp-prod -- jcmd 1 Thread.print | grep -A 10 "java.lang.Thread.State: RUNNABLE"

# Scale up if needed
kubectl set resources statefulset jotp -n jotp-prod \
  --limits=cpu=8 --requests=cpu=4
```

---

## 🔄 Disaster Recovery

### Pod Failure Recovery

```bash
# Kubernetes auto-recovers failed pods
# Just verify recovery

# Check pod status
kubectl get pod jotp-0 -n jotp-prod

# Check logs
kubectl logs jotp-0 -n jotp-prod --tail=50

# Verify process count restored
kubectl exec jotp-0 -n jotp-prod -- curl -s http://localhost:9091/actuator/metrics/jotp.process.count
```

### Node Failure Recovery

```bash
# Identify failed node
kubectl get nodes -l kubernetes.io/hostname=failed-node

# Cordone and drain
kubectl cordon failed-node
kubectl drain failed-node --ignore-daemonsets --force

# Verify pods rescheduled
kubectl get pods -n jotp-prod -o wide

# Uncordon when node is fixed
kubectl uncordon failed-node
```

### Data Recovery

```bash
# Identify corrupted PVC
kubectl describe pvc jotp-data-jotp-0 -n jotp-prod

# Stop affected pod
kubectl scale statefulset jotp --replicas=2 -n jotp-prod

# Delete and recreate PVC
kubectl delete pvc jotp-data-jotp-0 -n jotp-prod
kubectl apply -f jotp-data-jotp-0-backup.yaml -n jotp-prod

# Restart pod
kubectl scale statefulset jotp --replicas=3 -n jotp-prod

# Verify data integrity
kubectl exec jotp-0 -n jotp-prod -- curl -s http://localhost:9091/actuator/health
```

---

## 🧪 Testing

### Smoke Tests

```bash
# Basic health
kubectl exec jotp-0 -n jotp-prod -- curl -s http://localhost:9091/actuator/health

# Create test process
kubectl exec jotp-0 -n jotp-prod -- curl -X POST http://localhost:8080/api/processes

# Send message
kubectl exec jotp-0 -n jotp-prod -- curl -X POST http://localhost:8080/api/processes/test/messages -d '{"text":"hello"}'

# Check metrics
kubectl exec jotp-0 -n jotp-prod -- curl -s http://localhost:9091/actuator/metrics | grep jotp
```

### Load Testing

```bash
# Deploy load generator
kubectl run load-test --image=williamyeh/wrk --restart=Never -n jotp-prod -- \
  -t 4 -c 100 -d 30s http://jotp-0.jotp-headless.jotp-prod:8080/api/health

# Check results
kubectl logs load-test -n jotp-prod

# Cleanup
kubectl delete pod load-test -n jotp-prod
```

---

## 📈 Performance Tuning

### JVM Tuning

```bash
# Edit ConfigMap
kubectl edit configmap jotp-config -n jotp-prod

# Update JAVA_OPTS
JAVA_OPTS="
  -Xms16G
  -Xmx16G
  -XX:+UseZGC
  -XX:ConcGCThreads=4
  -XX:ParallelGCThreads=8
  -XX:+AlwaysPreTouch
"

# Rollout restart
kubectl rollout restart statefulset jotp -n jotp-prod
```

### Resource Tuning

```bash
# Update resource limits
kubectl set resources statefulset jotp -n jotp-prod \
  --limits=cpu=8,memory=32Gi \
  --requests=cpu=4,memory=24Gi

# Verify
kubectl describe statefulset jotp -n jotp-prod
```

---

## 🔍 Debugging

### Port Forwarding

```bash
# Forward admin port
kubectl port-forward jotp-0 9091:9091 -n jotp-prod

# Forward gRPC port
kubectl port-forward jotp-0 50051:50051 -n jotp-prod

# Forward HTTP port
kubectl port-forward jotp-0 8080:8080 -n jotp-prod

# Access locally
curl http://localhost:9091/actuator/health
```

### Exec into Pod

```bash
# Open shell
kubectl exec -it jotp-0 -n jotp-prod -- bash

# Check process tree
ps aux

# Check network connections
netstat -tulpn

# Check file descriptors
lsof | wc -l

# Check disk usage
df -h

# Check memory
free -m
```

### Packet Capture

```bash
# Install tcpdump
kubectl exec jotp-0 -n jotp-prod -- apt-get update && apt-get install -y tcpdump

# Capture packets
kubectl exec jotp-0 -n jotp-prod -- tcpdump -i any -w /tmp/capture.pcap port 50051

# Copy capture locally
kubectl cp jotp-prod/jotp-0:/tmp/capture.pcap capture.pcap

# Analyze with Wireshark
open capture.pcap
```

---

## 🎯 Common Scenarios

### Scenario 1: High Error Rate

```bash
# Check error rate
kubectl logs jotp-0 -n jotp-prod --tail=1000 | grep -i error | wc -l

# Check recent errors
kubectl logs jotp-0 -n jotp-prod --since=5m | grep -i error

# Check all pods
kubectl logs -n jotp-prod -l app=jotp --tail=100 | grep -i error

# Rollback if needed
kubectl rollout undo statefulset/jotp -n jotp-prod
```

### Scenario 2: Slow Responses

```bash
# Check latency metrics
kubectl exec jotp-0 -n jotp-prod -- curl -s http://localhost:9091/actuator/metrics/jotp.message.latency

# Check CPU usage
kubectl top pod -n jotp-prod -l app=jotp

# Check GC pauses
kubectl exec jotp-0 -n jotp-prod -- jstat -gc 1 | grep "FGC"

# Scale up if needed
kubectl scale statefulset jotp --replicas=5 -n jotp-prod
```

### Scenario 3: Disk Space Full

```bash
# Check disk usage
kubectl exec jotp-0 -n jotp-prod -- df -h

# Clean old logs
kubectl exec jotp-0 -n jotp-prod -- find /logs -name "*.log" -mtime +7 -delete

# Clean heap dumps
kubectl exec jotp-0 -n jotp-prod -- find /logs -name "*.hprof" -mtime +1 -delete

# Resize PVC if needed
kubectl patch pvc jotp-data-jotp-0 -n jotp-prod -p '{"spec":{"resources":{"requests":{"storage":"20Gi"}}}}'
```

---

## 📞 Escalation

### When to Escalate

- Cluster down >5 minutes
- Data loss detected
- Security breach
- Performance degradation >50%
- Multiple pods crashing

### Escalation Path

1. **Level 1 (On-Call):** First 15 minutes
   - Check alerts
   - Run diagnostics
   - Attempt recovery

2. **Level 2 (Engineering Lead):** After 15 minutes
   - Deep dive into logs
   - Coordinate response
   - Communicate status

3. **Level 3 (CTO/VP Engineering):** After 30 minutes
   - Major incident declaration
   - Customer communication
   - Executive updates

### Contact Information

- **On-Call:** [Phone number]
- **Slack:** #jotp-oncall
- **Email:** oncall@example.com
- **Runbooks:** https://runbooks.example.com/jotp

---

## 📚 Quick Links

- **Full Documentation:** `/Users/sac/jotp/k8s/PRODUCTION-DEPLOYMENT-GUIDE.md`
- **Breaking Point Analysis:** `/Users/sac/jotp/KUBERNETES-DEPLOYMENT-ANALYSIS.md`
- **Test Execution Summary:** `/Users/sac/jotp/KUBERNETES-TEST-EXECUTION-SUMMARY.md`
- **Deployment Checklist:** `/Users/sac/jotp/k8s/DEPLOYMENT-CHECKLIST.md`
- **Quick Start:** `/Users/sac/jotp/k8s/QUICK-START.md`

---

## 🔧 Useful Aliases

```bash
# Add to ~/.bashrc or ~/.zshrc

alias jotp-k='kubectl -n jotp-prod'
alias jotp-logs='jotp-k logs -f -l app=jotp'
alias jotp-pods='jotp-k get pods -l app=jotp'
alias jotp-top='jotp-k top pod -l app=jotp'
alias jotp-health='jotp-k exec jotp-0 -- curl -s http://localhost:9091/actuator/health'
alias jotp-scale-up='jotp-k scale statefulset jotp --replicas=5'
alias jotp-scale-down='jotp-k scale statefulset jotp --replicas=3'
alias jotp-restart='jotp-k rollout restart statefulset jotp'
alias jotp-rollback='jotp-k rollout undo statefulset/jotp'
```

---

**Version:** 1.0.0
**Last Updated:** 2025-03-17
**Maintained By:** Platform Team
