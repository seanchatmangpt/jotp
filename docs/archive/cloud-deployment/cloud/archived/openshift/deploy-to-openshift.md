# Deploy to OpenShift (Archived)

**Status:** 📋 **Planned** - This platform is not yet implemented. This documentation is provided for future implementation and community contribution.

This archived guide shows how to deploy a JOTP application to Red Hat OpenShift using Terraform and containerized deployment.

> **⚠️ Platform Status:** OpenShift deployment is currently not implemented. The infrastructure code referenced in this guide does not yet exist. See [Cloud Deployment Status](../status.md) for details.

## Prerequisites

- Terraform >= 1.6.0 installed
- OpenShift cluster access
- OpenShift CLI (`oc`) installed
- Container image built and pushed to registry

## Steps

### 1. Build and Push Container Image

```bash
# Build JAR
./mvnw package -Dshade

# Build container image
docker build -t jotp:latest .

# Push to registry
docker tag jotp:latest <registry>/jotp:latest
docker push <registry>/jotp:latest
```

### 2. Login to OpenShift

```bash
# Login via token
oc login --token=<token> --server=https://api.<cluster>:6443

# Verify login
oc whoami
```

### 3. Navigate to Terraform Directory

```bash
cd docs/infrastructure/terraform/openshift
```

**Note:** This directory does not yet exist. Implementation is pending.

### 4. Initialize Terraform

```bash
terraform init
```

### 5. Configure Variables

Create `terraform.tfvars`:

```hcl
openshift_server_url = "https://api.<cluster>:6443"
openshift_token      = "<your-oc-token>"
namespace            = "jotp-app"
app_name             = "jotp"
image                = "<registry>/jotp:latest"
replicas             = 2
}
```

### 6. Review Deployment Plan

```bash
terraform plan
```

### 7. Apply Configuration

```bash
terraform apply
```

Type `yes` to confirm.

### 8. Verify Deployment

```bash
# Get route URL
oc get route jotp -n jotp-app

# Test application
curl http://jotp-jotp-app.<cluster>/health
```

## Alternative: Deploy with OpenShift CLI

### Create Project

```bash
oc new-project jotp-app
```

### Deploy from Image

```bash
oc new-app --name jotp \
  --docker-image=<registry>/jotp:latest

oc expose svc/jotp
```

### Deploy with YAML

Create `deployment.yaml`:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: jotp
  labels:
    app: jotp
spec:
  replicas: 2
  selector:
    matchLabels:
      app: jotp
  template:
    metadata:
      labels:
        app: jotp
    spec:
      containers:
      - name: app
        image: <registry>/jotp:latest
        ports:
        - containerPort: 8080
        livenessProbe:
          httpGet:
            path: /health
            port: 8080
          initialDelaySeconds: 30
        readinessProbe:
          httpGet:
            path: /health
            port: 8080
          initialDelaySeconds: 5
---
apiVersion: v1
kind: Service
metadata:
  name: jotp
spec:
  selector:
    app: jotp
  ports:
  - port: 8080
    targetPort: 8080
---
apiVersion: route.openshift.io/v1
kind: Route
metadata:
  name: jotp
spec:
  to:
    kind: Service
    name: jotp
  port:
    targetPort: 8080
```

Apply:

```bash
oc apply -f deployment.yaml
```

## Production Configuration

### Add Horizontal Pod Autoscaler

```hcl
resource "kubernetes_horizontal_pod_autoscaler_v2" "app" {
  metadata {
    name      = var.app_name
    namespace = var.namespace
  }

  spec {
    scale_target_ref {
      api_version = "apps/v1"
      kind        = "Deployment"
      name        = var.app_name
    }

    min_replicas = 2
    max_replicas = 10

    metric {
      type = "Resource"
      resource {
        name = "cpu"
        target {
          type               = "Utilization"
          average_utilization = 70
        }
      }
    }
  }
}
```

### Add ConfigMap and Secrets

```hcl
resource "kubernetes_config_map" "app" {
  metadata {
    name      = "${var.app_name}-config"
    namespace = var.namespace
  }

  data = {
    LOG_LEVEL = "INFO"
    JAVA_OPTS = "-Xmx512m"
  }
}

resource "kubernetes_secret" "app" {
  metadata {
    name      = "${var.app_name}-secrets"
    namespace = var.namespace
  }

  data = {
    DB_PASSWORD = base64encode(var.db_password)
  }
}
```

## Contributing

To help implement OpenShift support:

1. **Review this documentation** and identify missing components
2. **Create infrastructure code** in `docs/infrastructure/terraform/openshift/`
3. **Test deployment** in a development environment
4. **Open a GitHub issue** to share progress
5. **Submit a pull request** with implementation

See [Cloud Deployment Status](../status.md) for contribution guidelines.

## Related Resources

- [Terraform OpenShift Provider](https://registry.terraform.io/providers/openshift/openshift/latest/docs)
- [OpenShift Documentation](https://docs.openshift.com/)
- [Red Hat Developer Sandbox](https://developers.redhat.com/developer-sandbox)
