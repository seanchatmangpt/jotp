# JOTP Kubernetes Deployment - Complete Index

## 📁 File Structure

```
k8s/
├── 📋 Core Manifests
│   ├── statefulset.yaml          # StatefulSet for 3-node JOTP cluster
│   ├── service.yaml              # Headless + ClusterIP services + Ingress
│   ├── configmap.yaml            # JVM config, distributed node config
│   ├── serviceaccount.yaml       # RBAC (ServiceAccount, Role, ClusterRole)
│   ├── PodDisruptionBudget.yaml  # PDB for maintenance safety
│   └── StorageClass.yaml         # SSD storage classes (AWS, GCE, Azure)
│
├── 🚀 Deployment & Automation
│   ├── deploy.sh                 # Automated deployment script
│   └── kustomization.yaml        # Kustomize base configuration
│
├── 📚 Documentation
│   ├── README.md                 # Comprehensive deployment guide (496 lines)
│   ├── SUMMARY.md                # Executive summary and overview (360 lines)
│   ├── QUICK-START.md            # 5-minute deployment guide (136 lines)
│   ├── DEPLOYMENT-CHECKLIST.md   # Production readiness checklist (264 lines)
│   ├── KUSTOMIZE-GUIDE.md        # Kustomize usage guide (443 lines)
│   └── INDEX.md                  # This file
│
├── 🔧 Environment Overlays (Kustomize)
│   └── overlays/
│       ├── production/
│       │   └── kustomization.yaml # Production config (5 replicas, 8GB heap)
│       └── staging/
│           └── kustomization.yaml # Staging config (2 replicas, 2GB heap)
│
└── 📊 Examples
    └── examples/
        ├── scale-test.yaml              # 5-node cluster example
        ├── high-memory.yaml             # 5M+ processes config (16GB heap)
        ├── prometheus-rules.yaml        # Alerting rules (75 lines)
        └── grafana-dashboard.json       # Grafana dashboard (313 lines)
```

## 🚀 Quick Navigation

### For First-Time Users
1. **[QUICK-START.md](QUICK-START.md)** - Deploy JOTP in 5 minutes
2. **[deploy.sh](deploy.sh)** - Run automated deployment script
3. **[examples/](examples/)** - Browse example configurations

### For Production Deployments
1. **[DEPLOYMENT-CHECKLIST.md](DEPLOYMENT-CHECKLIST.md)** - Production readiness checklist
2. **[overlays/production/](overlays/production/)** - Production Kustomize overlay
3. **[examples/prometheus-rules.yaml](examples/prometheus-rules.yaml)** - Alerting setup
4. **[examples/grafana-dashboard.json](examples/grafana-dashboard.json)** - Monitoring dashboard

### For Custom Deployments
1. **[KUSTOMIZE-GUIDE.md](KUSTOMIZE-GUIDE.md)** - Environment-specific configs
2. **[kustomization.yaml](kustomization.yaml)** - Base Kustomize config
3. **[overlays/staging/](overlays/staging/)** - Staging overlay template

### For Troubleshooting
1. **[README.md#troubleshooting](README.md#troubleshooting)** - Troubleshooting guide
2. **[README.md#monitoring](README.md#monitoring)** - Health checks and debugging
3. **[QUICK-START.md#troubleshooting](QUICK-START.md#troubleshooting)** - Quick fixes

### For Understanding Architecture
1. **[SUMMARY.md](SUMMARY.md)** - Executive summary and diagrams
2. **[README.md#architecture](README.md#architecture)** - Detailed architecture
3. **[statefulset.yaml](statefulset.yaml)** - Pod configuration

## 📖 Documentation Summary

### Core Manifests (5 files)

| File | Lines | Purpose |
|------|-------|---------|
| **statefulset.yaml** | 162 | 3-node StatefulSet with health probes, PVCs, graceful shutdown |
| **service.yaml** | 100 | Headless service (peers) + ClusterIP (clients) + Ingress |
| **configmap.yaml** | 127 | JVM flags (4GB heap), distributed config, logging |
| **serviceaccount.yaml** | 75 | RBAC: ServiceAccount, Role, ClusterRole, bindings |
| **PodDisruptionBudget.yaml** | 25 | Ensures 2 pods available during maintenance |

### Documentation (5 files)

| File | Lines | Purpose |
|------|-------|---------|
| **README.md** | 496 | Comprehensive deployment guide, troubleshooting, tuning |
| **KUSTOMIZE-GUIDE.md** | 443 | Kustomize usage, overlays, environment management |
| **DEPLOYMENT-CHECKLIST.md** | 264 | Pre/post-deployment checklists, testing procedures |
| **SUMMARY.md** | 360 | Executive summary, architecture diagrams, quick reference |
| **QUICK-START.md** | 136 | 5-minute deployment, common commands |

### Examples (4 files)

| File | Lines | Purpose |
|------|-------|---------|
| **grafana-dashboard.json** | 313 | Pre-built Grafana dashboard for monitoring |
| **prometheus-rules.yaml** | 75 | Comprehensive alerting rules |
| **high-memory.yaml** | 41 | 5M+ processes config (16GB heap) |
| **scale-test.yaml** | 9 | 5-node cluster example |

### Automation (2 files)

| File | Lines | Purpose |
|------|-------|---------|
| **deploy.sh** | 165 | Automated deployment with health checks |
| **kustomization.yaml** | 89 | Base Kustomize configuration |

## 🎯 Common Tasks

### Deploy to Development
```bash
cd k8s
./deploy.sh
```
- **Files used**: All core manifests
- **Configuration**: 3 replicas, 4GB heap, 6GB memory
- **Time**: ~5 minutes

### Deploy to Staging
```bash
cd k8s
kubectl apply -k overlays/staging
```
- **Files used**: `overlays/staging/kustomization.yaml`
- **Configuration**: 2 replicas, 2GB heap, 4GB memory
- **Time**: ~3 minutes

### Deploy to Production
```bash
cd k8s
kubectl apply -k overlays/production
```
- **Files used**: `overlays/production/kustomization.yaml`
- **Configuration**: 5 replicas, 8GB heap, 12GB memory
- **Time**: ~10 minutes

### Scale Cluster
```bash
kubectl scale statefulset jotp --replicas=7
```
- **Reference**: [README.md#scaling](README.md#scaling)
- **Time**: ~2 minutes per pod

### Update JVM Options
```bash
kubectl edit configmap jotp-config
kubectl rollout restart statefulset/jotp
```
- **Reference**: [README.md#configuration](README.md#configuration)
- **Time**: ~5 minutes

### Setup Monitoring
```bash
kubectl apply -f examples/prometheus-rules.yaml
kubectl apply -f examples/grafana-dashboard.json
```
- **Files used**: `examples/prometheus-rules.yaml`, `examples/grafana-dashboard.json`
- **Tools**: Prometheus, Grafana
- **Time**: ~10 minutes

## 🔍 Key Concepts

### Distributed Architecture
- **Leader Election**: Automatic via DistributedNode primitive
- **Peer Discovery**: Kubernetes DNS (headless service)
- **Failover**: Configurable timeout (3s default)
- **Communication**: gRPC on port 50051

### Performance Characteristics
- **Per Pod**: 1M+ processes, 3.6M msg/sec, <1µs latency
- **Cluster**: 3M+ processes, 10M+ msg/sec (3 pods)
- **Memory**: ~3.9KB per process
- **GC**: ZGC with <100µs pause time (p99)

### High Availability
- **Replicas**: 3+ (recommended for production)
- **Pod Anti-Affinity**: Spread across nodes
- **PodDisruptionBudget**: 2 pods minimum during maintenance
- **Graceful Shutdown**: 30s termination grace period

### Monitoring
- **Metrics**: Prometheus on port 9090
- **Health**: Actuator endpoints on port 9091
- **Logs**: Structured logging with Logback
- **Dashboards**: Grafana dashboard provided

## 📊 Deployment Scenarios

### Development (3 nodes, 1M processes)
- **Replicas**: 3
- **Heap**: 4GB per pod
- **Memory**: 6GB request, 8GB limit per pod
- **CPU**: 2 cores request, 4 cores limit per pod
- **Storage**: 10GB SSD per pod
- **Deploy**: `./deploy.sh` or `kubectl apply -k .`

### Staging (2 nodes, 500K processes)
- **Replicas**: 2
- **Heap**: 2GB per pod
- **Memory**: 2GB request, 4GB limit per pod
- **CPU**: 1 core request, 2 cores limit per pod
- **Storage**: 5GB SSD per pod
- **Deploy**: `kubectl apply -k overlays/staging`

### Production (5 nodes, 5M processes)
- **Replicas**: 5
- **Heap**: 8GB per pod
- **Memory**: 8GB request, 12GB limit per pod
- **CPU**: 4 cores request, 8 cores limit per pod
- **Storage**: 10GB SSD per pod
- **Deploy**: `kubectl apply -k overlays/production`

### High-Scale (10 nodes, 10M processes)
- **Replicas**: 10
- **Heap**: 16GB per pod
- **Memory**: 24GB request, 32GB limit per pod
- **CPU**: 8 cores request, 16 cores limit per pod
- **Storage**: 20GB SSD per pod
- **Deploy**: Use `examples/high-memory.yaml` as template

## 🛠️ Troubleshooting Quick Reference

| Issue | Solution | Documentation |
|-------|----------|---------------|
| Pods stuck in Init | Check DNS resolution | [README.md#troubleshooting](README.md#troubleshooting) |
| OOMKilled | Reduce heap size | [QUICK-START.md#troubleshooting](QUICK-START.md#troubleshooting) |
| Peer connection failed | Test gRPC connectivity | [README.md#troubleshooting](README.md#troubleshooting) |
| High CPU during startup | Expected (JIT compilation) | [README.md#troubleshooting](README.md#troubleshooting) |
| Slow leader election | Check failover timeout | [README.md#troubleshooting](README.md#troubleshooting) |

## 📝 Configuration Reference

### Environment Variables
- `JOTP_NODE_NAME`: Pod name (from metadata)
- `JOTP_POD_IP`: Pod IP address
- `JOTP_PEER_DISCOVERY`: Discovery method (dns)
- `JOTP_PEER_SERVICE`: Headless service FQDN
- `JOTP_CLUSTER_SIZE`: Number of replicas

### JVM Options
- `--enable-preview`: Java 26 preview features
- `-Xms4g -Xmx4g`: Initial and max heap
- `-XX:+UseZGC -XX:+ZGenerational`: Garbage collector
- `-Djdk.virtualThreadScheduler.parallelism=16`: Virtual threads

### Ports
- `50051`: gRPC (peer communication)
- `8080`: HTTP (client access)
- `9090`: Prometheus metrics
- `9091`: Actuator admin/health

### Resources
- **1M processes**: 4GB heap, 6GB memory
- **5M processes**: 16GB heap, 24GB memory
- **10M processes**: 32GB heap, 40GB memory

## 🔗 Related Resources

### JOTP Project
- [Project README](../README.md)
- [Performance Claims](../docs/validation/performance/honest-performance-claims.md)
- [DistributedNode API](../src/main/java/io/github/seanchatmangpt/jotp/distributed/DistributedNode.java)

### Kubernetes
- [StatefulSet Documentation](https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/)
- [Kustomize Documentation](https://kustomize.io/)
- [Pod Disruption Budgets](https://kubernetes.io/docs/concepts/workloads/pods/disruptions/)

### Monitoring
- [Prometheus Documentation](https://prometheus.io/docs/)
- [Grafana Documentation](https://grafana.com/docs/)
- [Spring Boot Actuator](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html)

## ✅ Deployment Checklist Summary

### Pre-Deployment
- [ ] Kubernetes cluster 1.25+ available
- [ ] kubectl configured
- [ ] Docker image built and pushed
- [ ] StorageClass available
- [ ] Environment variables configured

### Deployment
- [ ] Namespace created
- [ ] RBAC deployed
- [ ] ConfigMap deployed
- [ ] Services deployed
- [ ] StatefulSet deployed
- [ ] Pods ready

### Post-Deployment
- [ ] Health checks passing
- [ ] Peer discovery working
- [ ] Leader election completed
- [ ] Monitoring configured
- [ ] Alerting configured
- [ ] Documentation updated

**Full checklist**: [DEPLOYMENT-CHECKLIST.md](DEPLOYMENT-CHECKLIST.md)

## 📞 Support

### Documentation
- **Deployment Guide**: [README.md](README.md)
- **Quick Start**: [QUICK-START.md](QUICK-START.md)
- **Kustomize Guide**: [KUSTOMIZE-GUIDE.md](KUSTOMIZE-GUIDE.md)
- **Checklist**: [DEPLOYMENT-CHECKLIST.md](DEPLOYMENT-CHECKLIST.md)

### Community
- **Issues**: [GitHub Issues](https://github.com/seanchatmangpt/jotp/issues)
- **Documentation**: [JOTP Docs](../docs/)
- **Architecture**: [ARCHITECTURE.md](../docs/architecture/)

---

## 🎉 Ready to Deploy?

**Choose your path:**

1. **Quick Start**: [QUICK-START.md](QUICK-START.md) - Deploy in 5 minutes
2. **Production**: [DEPLOYMENT-CHECKLIST.md](DEPLOYMENT-CHECKLIST.md) - Full deployment checklist
3. **Custom**: [KUSTOMIZE-GUIDE.md](KUSTOMIZE-GUIDE.md) - Environment-specific configs

**Happy deploying! 🚀**
