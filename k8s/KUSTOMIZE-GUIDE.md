# Kustomize Deployment Guide for JOTP

This guide shows how to use Kustomize for flexible, environment-specific deployments of JOTP on Kubernetes.

## Overview

Kustomize allows you to manage different deployment configurations (dev, staging, production) without duplicating manifests. It uses base manifests and applies overlays for environment-specific customizations.

## Directory Structure

```
k8s/
├── kustomization.yaml              # Base configuration
├── serviceaccount.yaml
├── configmap.yaml
├── service.yaml
├── statefulset.yaml
├── PodDisruptionBudget.yaml
├── overlays/
│   ├── production/
│   │   └── kustomization.yaml     # Production overrides
│   └── staging/
│       └── kustomization.yaml     # Staging overrides
└── examples/
    ├── high-memory.yaml
    └── scale-test.yaml
```

## Quick Start

### Deploy to Default Namespace

```bash
# Deploy using base configuration
cd k8s
kubectl apply -k .

# Or use the deploy script
./deploy.sh
```

### Deploy to Staging

```bash
# Deploy to staging with reduced resources
kubectl apply -k overlays/staging
```

### Deploy to Production

```bash
# Deploy to production with full resources and 5 replicas
kubectl apply -k overlays/production
```

## Configuration Options

### Base Configuration (kustomization.yaml)

The base configuration includes:
- **3 replicas** (default)
- **4GB heap** per pod
- **6GB memory** request, **8GB limit**
- **2 CPU cores** request, **4 CPU cores** limit
- **Standard** logging level (INFO)
- **DNS** peer discovery

### Staging Configuration (overlays/staging)

Staging overrides:
- **2 replicas** (reduced for testing)
- **2GB heap** per pod
- **2GB memory** request, **4GB limit
- **1 CPU core** request, **2 CPU cores** limit
- **DEBUG** logging level
- **staging** namespace

### Production Configuration (overlays/production)

Production overrides:
- **5 replicas** (high availability)
- **8GB heap** per pod
- **8GB memory** request, **12GB limit
- **4 CPU cores** request, **8 CPU cores** limit
- **WARN** logging level (reduced noise)
- **production** namespace
- **Pod annotations** for Prometheus scraping

## Customization Guide

### Changing Image Registry

Edit `kustomization.yaml`:

```yaml
images:
  - name: jotp
    newName: your-registry.example.com/jotp  # Change this
    newTag: "1.0.0"                          # And this
```

Then deploy:
```bash
kubectl apply -k .
```

### Adjusting Cluster Size

Edit the `replicas` section:

```yaml
replicas:
  - name: jotp
    count: 7  # Scale to 7 nodes
```

### Changing JVM Options

Edit the `configMapGenerator` section:

```yaml
configMapGenerator:
  - name: jotp-config
    literals:
      - JAVA_OPTS=--enable-preview -Xms8g -Xmx8g -XX:+UseZGC
      - JOTP_CLUSTER_SIZE=7
```

### Adding Environment-Specific Secrets

Create a secret generator:

```yaml
secretGenerator:
  - name: jotp-secrets
    literals:
      - DATABASE_URL=postgresql://prod-db:5432/jotp
      - DATABASE_PASSWORD=prod-secret-password
    type: Opaque
```

Reference in your StatefulSet:

```yaml
envFrom:
  - secretRef:
      name: jotp-secrets
```

### Adding Pod Affinity Rules

Add a patch to `kustomization.yaml`:

```yaml
patches:
  - patch: |-
      - op: add
        path: /spec/template/spec/affinity
        value:
          podAffinity:
            requiredDuringSchedulingIgnoredDuringExecution:
              - labelSelector:
                  matchExpressions:
                    - key: app
                      operator: In
                      values:
                        - jotp
                topologyKey: topology.kubernetes.io/zone
      target:
        kind: StatefulSet
        name: jotp
```

## Advanced Usage

### Creating a Custom Overlay

1. Create a new directory:

```bash
mkdir -p k8s/overlays/custom
```

2. Create `kustomization.yaml`:

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

# Base manifests
resources:
  - ../../

# Custom replicas
replicas:
  - name: jotp
    count: 10

# Custom resources
patches:
  - patch: |-
      - op: replace
        path: /spec/template/spec/containers/0/resources/limits/memory
        value: "16Gi"
      target:
        kind: StatefulSet
        name: jotp

# Custom namespace
namespace: custom

# Custom labels
commonLabels:
  environment: custom
  team: platform
```

3. Deploy:

```bash
kubectl apply -k overlays/custom
```

### Managing Multiple Environments

Create a script to manage deployments:

```bash
#!/bin/bash
# deploy-all.sh

set -e

ENVIRONMENTS=(staging production)

for env in "${ENVIRONMENTS[@]}"; do
  echo "Deploying to $env..."
  kubectl apply -k "overlays/$env"
  kubectl rollout status statefulset/jotp -n "$env"
  echo "✓ Deployed to $env"
done

echo "All environments deployed successfully!"
```

### Dry Run and Diff

Before applying changes, see what will change:

```bash
# Dry run (print manifests to stdout)
kubectl apply -k overlays/production --dry-run=client

# Diff against live cluster
kubectl diff -k overlays/production
```

### Build Manifests Without Applying

Generate YAML files for review or version control:

```bash
# Build manifests
kustomize build overlays/production > production-manifests.yaml

# Review the file
cat production-manifests.yaml

# Apply later
kubectl apply -f production-manifests.yaml
```

## Validation

### Validate Manifests

```bash
# Validate syntax
kubectl apply -k overlays/production --dry-run=server

# Validate with kubeval
kustomize build overlays/production | kubeval

# Validate with conftest
kustomize build overlays/production | conftest test -
```

### Test Configuration

```bash
# Test staging deployment
kubectl apply -k overlays/staging --dry-run=client

# View generated manifests
kustomize build overlays/staging
```

## Troubleshooting

### Image Pull Issues

If pods can't pull the image:

```bash
# Verify image configuration
kustomize build . | grep image:

# Update image pull secret
kubectl create secret docker-registry regcred \
  --docker-server=your-registry \
  --docker-username=user \
  --docker-password=pass \
  --dry-run=client -o yaml | kubectl apply -f -
```

### ConfigMap Not Updating

If ConfigMap changes aren't applied:

```bash
# Force recreation by adding a suffix
configMapGenerator:
  - name: jotp-config
    behavior: replace  # Instead of merge
```

Then redeploy:

```bash
kubectl apply -k .
kubectl rollout restart statefulset/jotp
```

### Resource Conflicts

If resource conflicts occur:

```bash
# Check what exists
kubectl get all -n production

# Delete existing resources before applying
kubectl delete all -l app=jotp -n production
kubectl apply -k overlays/production
```

## Best Practices

### 1. Version Control

Commit your `kustomization.yaml` files to Git:

```bash
git add k8s/kustomization.yaml
git add k8s/overlays/*/kustomization.yaml
git commit -m "Update Kustomize configurations"
```

### 2. Separate Secrets

Don't commit secrets to Git. Use:

```bash
# Create secret from file
kubectl create secret generic jotp-secrets \
  --from-file=./secrets/db_password.txt \
  --dry-run=client -o yaml | kubectl apply -f -

# Reference in kustomization.yaml
resources:
  - jotp-secrets.yaml
```

### 3. Label Consistency

Use consistent labels across environments:

```yaml
commonLabels:
  app.kubernetes.io/name: jotp
  app.kubernetes.io/version: "1.0"
  app.kubernetes.io/component: distributed-node
  app.kubernetes.io/managed-by: kustomize
```

### 4. Gradual Rollouts

For production changes:

```bash
# Deploy to staging first
kubectl apply -k overlays/staging

# Verify staging works
kubectl wait --for=condition=ready pod -l app=jotp -n staging

# Then deploy to production
kubectl apply -k overlays/production
```

### 5. Resource Validation

Always validate before applying:

```bash
# Dry run validation
kubectl apply -k overlays/production --dry-run=server

# Check resource limits
kustomize build overlays/production | grep -A 5 resources
```

## Migration from Plain Manifests

If you're currently using plain manifests:

```bash
# 1. Create base kustomization
echo "resources:\n  - statefulset.yaml\n  - service.yaml" > kustomization.yaml

# 2. Test it works
kubectl apply -k . --dry-run=client

# 3. Start using overlays for environments
mkdir -p overlays/production
echo "resources:\n  - ../../" > overlays/production/kustomization.yaml

# 4. Deploy with kustomize
kubectl apply -k overlays/production
```

## Related Documentation

- [Kustomize Documentation](https://kustomize.io/)
- [Main Deployment Guide](README.md)
- [Quick Start Guide](QUICK-START.md)
- [Deployment Checklist](DEPLOYMENT-CHECKLIST.md)

## Support

- **Issues**: [GitHub Issues](https://github.com/seanchatmangpt/jotp/issues)
- **Documentation**: [JOTP Docs](../README.md)
- **Examples**: [k8s/examples/](examples/)
